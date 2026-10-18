package com.robert4lyf.hypershop.watch

import com.robert4lyf.hypershop.core.catalog.Catalog
import com.robert4lyf.hypershop.core.merge.Display
import com.robert4lyf.hypershop.core.merge.TickSource
import com.robert4lyf.hypershop.core.merge.UnitsSetting
import com.robert4lyf.hypershop.core.sync.PagerAisle
import com.robert4lyf.hypershop.core.sync.PagerItem
import com.robert4lyf.hypershop.core.sync.PayloadCap
import com.robert4lyf.hypershop.core.sync.SnapshotInput
import com.robert4lyf.hypershop.core.sync.SnapshotPage
import com.robert4lyf.hypershop.core.sync.SnapshotPager
import com.robert4lyf.hypershop.core.sync.WatchCodec
import com.robert4lyf.hypershop.core.sync.WatchMessage
import com.robert4lyf.hypershop.data.ListRepository
import com.robert4lyf.hypershop.data.WatchListView
import com.robert4lyf.hypershop.data.WatchReply
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import timber.log.Timber

/**
 * The phone's half of the sync (step 7.2), modelled on BangGym's
 * `GarminSyncCoordinator`.
 *
 * - Every watch payload is decoded by [WatchCodec] (untrusted input: types,
 *   ranges and sizes checked; anything else dropped), rate-capped, and
 *   handled one at a time.
 * - `hello` and op batches are **always** answered: a full snapshot when the
 *   watch needs the list (hello, or a batch that changed it), an ack-only
 *   page otherwise. Ops are applied by [ListRepository.applyWatch] in one
 *   transaction, which also keeps the clock offset from `hello`'s `wn`.
 * - The last snapshot is re-sent when the link reaches APP_READY, and a new
 *   one when the list changes (`distinctUntilChanged`, and not again for a
 *   listRev the watch already has).
 * - The payload probe is answered at the size asked for.
 */
class WatchSyncCoordinator(
    private val link: WatchLink,
    private val repository: ListRepository,
    private val catalog: Catalog,
    private val units: suspend () -> UnitsSetting,
    private val scope: CoroutineScope,
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    /** Raised from the probe's measurement at Hand-off B. */
    var cap: PayloadCap = PayloadCap(),
) {
    private val inbox = Channel<Map<*, *>>(INBOX)
    private val sending = Mutex()
    private val arrivals = ArrayDeque<Long>()

    /** The install that spoke last: unsolicited snapshots go to it. */
    @Volatile var install: Int? = null
        private set

    /** The listRev of the last full snapshot sent, so a reply and a change push are not both sent. */
    @Volatile private var sentRev: Int = -1

    fun start() {
        link.onReceive { payload ->
            if (!admit()) {
                log("watch: over the rate cap, message dropped")
            } else if (inbox.trySend(payload).isFailure) {
                log("watch: inbox full, message dropped")
            }
        }
        scope.launch {
            for (p in inbox) {
                try {
                    handle(p)
                } catch (e: Exception) {
                    Timber.e(e, "Watch message failed")
                    log("watch: handling failed: ${e.javaClass.simpleName}")
                }
            }
        }
        scope.launch {
            link.state.collect { if (it == WatchLinkState.APP_READY) push(force = true) }
        }
        scope.launch {
            repository.list.map { it.listRev }.distinctUntilChanged().collect { push(force = false) }
        }
    }

    /** At most [RATE] messages in any [RATE_WINDOW_MS]: a watch never needs more. */
    @Synchronized
    private fun admit(): Boolean {
        val now = clock()
        while (arrivals.isNotEmpty() && now - arrivals.first() > RATE_WINDOW_MS) arrivals.removeFirst()
        if (arrivals.size >= RATE) return false
        arrivals.addLast(now)
        return true
    }

    /** One inbound payload. Public for the end-to-end test, which drives it directly. */
    suspend fun handle(payload: Map<*, *>) {
        WatchCodec.decodeProbe(payload)?.let { probe ->
            install = probe.installId
            log("watch: probe in ${probe.received} B, reply ${probe.want} B")
            sending.withLock { link.send(WatchCodec.encodeProbeReply(probe)) }
            return
        }
        val m = WatchCodec.decode(payload)
        if (m == null) {
            log("watch: unreadable message dropped")
            return
        }
        install = m.installId
        val reply = repository.applyWatch(m)
        when {
            m is WatchMessage.Bye -> Unit
            reply.fullSnapshot -> sendSnapshot(m.installId, reply)
            else -> sendAckOnly(reply)
        }
    }

    /** The whole list to the last install heard from; [force] even when it already has this listRev. */
    suspend fun push(force: Boolean) {
        val to = install ?: return
        if (link.state.value != WatchLinkState.APP_READY) return
        val view = repository.watchView()
        if (!force && view.list.listRev.toInt() == sentRev) return
        sendSnapshot(to, repository.watchAck(to), view)
    }

    private suspend fun sendAckOnly(reply: WatchReply) {
        val view = repository.watchView()
        val page = SnapshotPager.ackOnly(view.epoch, view.list.listRev.toInt(), reply.ackedThrough, reply.ackExceptions, cap)
        send(listOf(page))
    }

    private suspend fun sendSnapshot(
        installId: Int,
        reply: WatchReply,
        view: WatchListView? = null,
    ) {
        val v = view ?: repository.watchView()
        val pages = SnapshotPager.build(input(v, installId, reply), cap)
        if (send(pages)) sentRev = v.list.listRev.toInt()
    }

    private suspend fun send(pages: List<SnapshotPage>): Boolean =
        sending.withLock {
            for (p in pages) {
                if (!link.send(WatchCodec.encodePage(p))) {
                    log("watch: send failed at page ${p.page + 1} of ${maxOf(p.pages, 1)}")
                    return@withLock false
                }
            }
            true
        }

    private suspend fun input(
        v: WatchListView,
        installId: Int,
        reply: WatchReply,
    ): SnapshotInput {
        val list = v.list
        val u = units()
        val shown = v.aisles.filter { !it.hidden }
        val order = shown.withIndex().associate { it.value.code to it.index }
        val lastTick = HashMap<Long, Long>()
        for (t in list.ticks) lastTick[t.itemId] = maxOf(lastTick[t.itemId] ?: 0L, t.at)
        val current =
            list.ticks.lastOrNull { it.source == TickSource.WATCH && it.installId == installId }
                ?.let { list.items[it.itemId]?.aisle?.name }
        val items =
            list.liveItems
                .filter { it.aisle in order }
                .sortedWith(compareBy({ order.getValue(it.aisle) }, { it.position }))
                .map { item ->
                    val text = Display.row(list, item, item.catalogId?.let { catalog.byId[it] }, u)
                    PagerItem(
                        id = item.id.toInt(),
                        name = text.main,
                        sub = text.sub.orEmpty(),
                        aisleCode = item.aisle.name,
                        done = list.isDone(item.id),
                        rev = item.rev.toInt(),
                        tickedAt = lastTick[item.id],
                    )
                }
        return SnapshotInput(
            epoch = v.epoch,
            listRev = list.listRev.toInt(),
            ackedThrough = reply.ackedThrough,
            ackExceptions = reply.ackExceptions,
            aisles = shown.map { PagerAisle(it.code.name, it.name, false) },
            items = items,
            nowMs = clock(),
            currentAisle = current,
        )
    }

    private companion object {
        const val INBOX = 64
        const val RATE = 40
        const val RATE_WINDOW_MS = 10_000L
    }
}
