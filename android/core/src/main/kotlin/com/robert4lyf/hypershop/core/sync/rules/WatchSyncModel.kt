package com.robert4lyf.hypershop.core.sync.rules

import com.robert4lyf.hypershop.core.sync.OpResult
import com.robert4lyf.hypershop.core.sync.SnapshotPage
import com.robert4lyf.hypershop.core.sync.WatchMessage
import com.robert4lyf.hypershop.core.sync.WatchOp

/**
 * The watch's side of the sync protocol (PLAN §3.6, docs/garmin-protocol.md).
 * Run 2 ports this to Monkey C (`PhoneLink.mc`/`SetQueue.mc` patterns); the
 * vectors in testdata/sync replay against both.
 *
 * Persistence order matters on the watch: [nextSeq] is written before a batch
 * is sent, and the outbox before the UI shows a tick.
 */
class WatchSyncModel(
    val installId: Int,
    var epoch: Int = 0,
    val batchSize: Int = 8,
    val outboxCap: Int = 32,
) {
    data class Pending(
        val itemId: Int,
        val done: Boolean,
        val seenListRev: Int,
        val ts: Long,
        /** Assigned when first sent; never changes afterwards. */
        var seq: Int? = null,
    )

    data class CachedItem(
        val id: Int,
        val name: String,
        val sub: String,
        val aisleIdx: Int,
        val done: Boolean,
        val rev: Int,
    )

    /** Never reset, not even by an epoch change. */
    var nextSeq: Int = 1
        private set
    var ackedThrough: Int = 0
        private set
    val outbox = mutableListOf<Pending>()
    var inFlight: Boolean = false
        private set

    /** The last complete snapshot. */
    var listRev: Int = 0
        private set
    var items: Map<Int, CachedItem> = emptyMap()
        private set
    private val assembling = HashMap<Int, HashMap<Int, SnapshotPage>>()

    /**
     * Ops the phone acked as `ok` whose effect no complete snapshot shows yet,
     * with the phone's listRev at the ack. Shown on top of the snapshot so a
     * tick does not flicker off between its ack and the next snapshot.
     */
    private val settled = mutableListOf<Pair<Pending, Int>>()

    /**
     * The watch should send `hello`: it adopted a new epoch (the hello voids
     * the seqs it discarded), or an ack said the phone's list is newer than
     * the cached snapshot. Cleared by [hello].
     */
    var helloDue: Boolean = false
        private set

    /** User-visible notices, newest last ("Changed on phone", "1 more", …). */
    val notices = mutableListOf<String>()
    val results = HashMap<Int, OpResult>()

    val outboxFull: Boolean get() = outbox.size >= outboxCap

    fun hello(nowSeconds: Long): WatchMessage.Hello {
        helloDue = false
        return WatchMessage.Hello(epoch, installId, listRev, nowSeconds, ackedThrough)
    }

    /**
     * The user selects an item: tick it if unticked, untick if ticked. Returns
     * false when the outbox is full ("Sync with phone").
     */
    fun toggle(
        itemId: Int,
        nowSeconds: Long,
    ): Boolean {
        val shown = view()[itemId] ?: return false
        val desired = !shown
        // Collapse with an op for the same item that has not been sent yet.
        val unsent = outbox.indexOfLast { it.itemId == itemId && it.seq == null }
        if (unsent >= 0) {
            outbox.removeAt(unsent)
            if (baseline(itemId) == desired) return true
        } else if (outboxFull) {
            return false
        }
        outbox += Pending(itemId, desired, listRev, nowSeconds)
        return true
    }

    /** The item's state as [view] shows it without the unsent ops: the snapshot, the settled ops, then the sent ones. */
    private fun baseline(itemId: Int): Boolean? {
        var d = items[itemId]?.done ?: return null
        for ((p, _) in settled) if (p.itemId == itemId) d = p.done
        for (p in outbox) if (p.itemId == itemId && p.seq != null) d = p.done
        return d
    }

    /** The next batch to send, or null (nothing pending, or one already in flight). */
    fun nextBatch(): WatchMessage.Ops? {
        if (inFlight || outbox.isEmpty()) return null
        val batch = outbox.take(batchSize)
        // Assigned ops are always a prefix of the outbox, so seqs stay contiguous.
        for (p in batch) if (p.seq == null) p.seq = nextSeq++
        inFlight = true
        return WatchMessage.Ops(epoch, installId, batch.map { WatchOp(it.seq!!, it.itemId, it.done, it.seenListRev, it.ts) })
    }

    /** No reply in time: back off (5 s → 15 s → 60 s) and resend from ackedThrough + 1. */
    fun timeout() {
        inFlight = false
    }

    fun receive(page: SnapshotPage) {
        if (page.epoch != epoch) adoptEpoch(page.epoch)

        if (page.ackedThrough > ackedThrough || inFlight) {
            for (p in outbox.filter { (it.seq ?: Int.MAX_VALUE) <= page.ackedThrough }) {
                val r = page.ackExceptions.firstOrNull { it.first == p.seq }?.second ?: OpResult.OK
                results[p.seq!!] = r
                if (r == OpResult.OK) settled += p to page.listRev
                when (r) {
                    OpResult.PARTIAL -> notices += "1 more"
                    OpResult.REJECTED -> notices += "Changed on phone"
                    else -> Unit
                }
            }
            outbox.removeAll { (it.seq ?: Int.MAX_VALUE) <= page.ackedThrough }
            ackedThrough = maxOf(ackedThrough, page.ackedThrough)
            inFlight = false
        }

        // Ack-only pages (pages == 0) and stale pages carry nothing else.
        if (page.pages <= 0) {
            if (page.listRev > listRev) helloDue = true
            return
        }
        if (page.listRev < listRev) return
        val pages = assembling.getOrPut(page.listRev) { HashMap() }
        pages[page.page] = page
        if ((0 until page.pages).all { it in pages }) {
            val all = (0 until page.pages).map { pages.getValue(it) }
            items = all.flatMap { it.items }.associate { it.id to CachedItem(it.id, it.name, it.sub, it.aisleIdx, it.done, it.rev) }
            listRev = page.listRev
            assembling.keys.removeAll { it <= page.listRev }
            settled.removeAll { it.second <= listRev }
        }
    }

    private fun adoptEpoch(newEpoch: Int) {
        val dropped = outbox.size
        if (dropped > 0) notices += "$dropped watch ticks discarded (list replaced)"
        outbox.clear()
        epoch = newEpoch
        items = emptyMap()
        listRev = 0
        assembling.clear()
        settled.clear()
        inFlight = false
        helloDue = true
        // Everything issued so far is settled: the next hello tells the phone so.
        ackedThrough = nextSeq - 1
    }

    /** What the screen shows: the snapshot with pending ops re-applied on top. */
    fun view(): Map<Int, Boolean> {
        val v = items.mapValues { it.value.done }.toMutableMap()
        for ((p, _) in settled) if (p.itemId in v) v[p.itemId] = p.done
        for (p in outbox) if (p.itemId in v) v[p.itemId] = p.done
        return v
    }
}
