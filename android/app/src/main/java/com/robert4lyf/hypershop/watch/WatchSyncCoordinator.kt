package com.robert4lyf.hypershop.watch

import com.robert4lyf.hypershop.core.catalog.Catalog
import com.robert4lyf.hypershop.core.merge.Display
import com.robert4lyf.hypershop.core.merge.ListItem
import com.robert4lyf.hypershop.core.merge.ShelfOrder
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
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
 *   one when the list changes (listRev observed without loading the list; the
 *   list is loaded only once a watch is ready and the push is due, and not
 *   again for a listRev the watch already has).
 * - Pushes go to the install that spoke last, or, after the phone process
 *   restarted, to the last one the repository recorded: neither side has to
 *   speak first.
 * - The payload probe is answered at the size asked for.
 */
class WatchSyncCoordinator(
    private val link: WatchLink,
    private val repository: ListRepository,
    private val catalog: Catalog,
    private val units: suspend () -> UnitsSetting,
    private val scope: CoroutineScope,
    /** Which store's walking order the watch gets (missing 4 of the review). */
    private val store: suspend () -> Long = { 1L },
    private val log: (String) -> Unit = {},
    private val clock: () -> Long = System::currentTimeMillis,
    /** Raised from the probe's measurement at Hand-off B. */
    var cap: PayloadCap = PayloadCap(),
    /**
     * Returns once the app's data is safe to read and write ([AppContainer.awaitReady]):
     * a restore the process died in is finished first. Messages wait in the inbox until then.
     */
    private val ready: suspend () -> Unit = {},
) {
    private val inbox = Channel<Map<*, *>>(INBOX)
    private val sending = Mutex()
    private val arrivals = ArrayDeque<Long>()

    /** The install that spoke last (or was last recorded): unsolicited snapshots go to it. */
    @Volatile var install: Int? = null

    /** The install heard from since this process started: the only one whose acks are known to be its own. */
    @Volatile private var spoken: Int? = null
        private set

    /** The listRev of the last full snapshot sent, so a reply and a change push are not both sent. */
    private val sentRevFlow = MutableStateFlow(-1)
    private var sentRev: Int
        get() = sentRevFlow.value
        set(v) {
            sentRevFlow.value = v
        }

    /** The listRev the watch last got in full (-1 for none this process): the shop header's "Watch synced". */
    val sent: StateFlow<Int> get() = sentRevFlow

    fun start() {
        link.onReceive { payload ->
            if (!admit()) {
                log("watch: over the rate cap, message dropped")
            } else if (inbox.trySend(payload).isFailure) {
                log("watch: inbox full, message dropped")
            }
        }
        scope.launch {
            ready()
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
            // Not repository.list: that would keep the shared list flow hot and
            // load the whole list on every write, watch or no watch.
            repository.observeListRev().collect { push(force = false) }
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
            spoken = probe.installId
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
        spoken = m.installId
        val reply = repository.applyWatch(m)
        when {
            m is WatchMessage.Bye -> Unit
            // A hello always gets the list. Ticks change the list, so the change push may
            // already have sent this listRev, built after the write and so carrying these
            // acks: sending it twice put the next tick behind both.
            reply.fullSnapshot -> sendSnapshot(m.installId, onlyIfNew = m !is WatchMessage.Hello) { reply }
            else -> sendAckOnly(reply)
        }
    }

    /**
     * The whole list to the last install heard from (or recorded); [force]
     * even when it already has this listRev. Nothing is loaded until a watch
     * is ready and the push is due.
     */
    suspend fun push(force: Boolean) {
        ready()
        if (link.state.value != WatchLinkState.APP_READY) return
        val to = install ?: repository.lastInstall()?.also { install = it } ?: return
        if (!force && repository.epochAndRev().second.toInt() == sentRev) return
        // Connect IQ delivers to the device, whatever install runs there. Acks belong to the install that
        // sent the ops; one that has not spoken since this process started gets the list and no acks
        // (nothing has seq <= 0), rather than another install's ackedThrough (review D.34).
        sendSnapshot(to, onlyIfNew = !force) { if (spoken == to) repository.watchAck(to) else WatchReply(0, emptyList(), true) }
    }

    private suspend fun sendAckOnly(reply: WatchReply) {
        val (epoch, listRev) = repository.epochAndRev()
        val page = SnapshotPager.ackOnly(epoch.toInt(), listRev.toInt(), reply.ackedThrough, reply.ackExceptions, cap)
        send(listOf(page))
    }

    /** Read and sent under the lock, so [onlyIfNew] sees a snapshot that went out while this one waited. */
    private suspend fun sendSnapshot(
        installId: Int,
        onlyIfNew: Boolean,
        reply: suspend () -> WatchReply,
    ) = sending.withLock {
        val v = repository.watchView(store())
        if (onlyIfNew && v.list.listRev.toInt() == sentRev) return@withLock
        val pages = SnapshotPager.build(input(v, installId, reply()), cap)
        if (sendPages(pages)) sentRev = v.list.listRev.toInt()
    }

    private suspend fun send(pages: List<SnapshotPage>): Boolean = sending.withLock { sendPages(pages) }

    private suspend fun sendPages(pages: List<SnapshotPage>): Boolean {
        for (p in pages) {
            if (!link.send(WatchCodec.encodePage(p))) {
                log("watch: send failed at page ${p.page + 1} of ${maxOf(p.pages, 1)}")
                return false
            }
        }
        return true
    }

    private fun groupOf(item: ListItem): String? = item.catalogId?.let { catalog.byId[it] }?.group

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
                // Set aside on the phone for this trip (X.8): not something to look for on the wrist either.
                // Ids and revs travel as 32-bit numbers; a row beyond that (a crafted backup) is left off.
                .filter { it.aisle in order && it.unavailableAt == null && it.id <= Int.MAX_VALUE && it.rev <= Int.MAX_VALUE }
                .sortedWith(
                    compareBy(
                        { order.getValue(it.aisle) },
                        { v.shelf.groupSort(it.aisle, groupOf(it), catalog.groupRank(it.aisle, groupOf(it))) },
                        { v.shelf.itemSort(ShelfOrder.Slot(it.aisle, groupOf(it), it.mergeKey, it.position)) },
                        { it.position },
                    ),
                )
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
            build = nextBuild(),
        )
    }

    /** A number no other build of this process carries, never 0 (0 means "not said" on the wire). */
    private fun nextBuild(): Int = builds.incrementAndGet().let { if (it == 0) builds.incrementAndGet() else it }

    private val builds = java.util.concurrent.atomic.AtomicInteger(java.util.Random().nextInt())

    private companion object {
        const val INBOX = 64
        const val RATE = 40
        const val RATE_WINDOW_MS = 10_000L
    }
}
