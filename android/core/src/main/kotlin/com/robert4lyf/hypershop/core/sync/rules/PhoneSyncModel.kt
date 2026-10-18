package com.robert4lyf.hypershop.core.sync.rules

import com.robert4lyf.hypershop.core.sync.OpResult
import com.robert4lyf.hypershop.core.sync.WatchMessage
import com.robert4lyf.hypershop.core.sync.WatchOp

/**
 * The phone's side of the sync protocol (PLAN §3.6, docs/garmin-protocol.md),
 * over a small in-memory list. Pure and deterministic: the Room repository
 * (Step 5.2d) implements the same rules in one transaction per batch, and the
 * vectors in testdata/sync replay against both.
 */
class PhoneSyncModel(
    var epoch: Int,
) {
    data class Contribution(
        val id: Int,
        val createdListRev: Int,
        var coveredBy: Int? = null,
    )

    data class Item(
        val id: Int,
        val name: String,
        val contributions: MutableList<Contribution> = mutableListOf(),
        var rev: Int = 1,
        var deleted: Boolean = false,
    ) {
        val done: Boolean get() = contributions.isNotEmpty() && contributions.all { it.coveredBy != null }
        val uncovered: Int get() = contributions.count { it.coveredBy == null }
    }

    data class Tick(
        val id: Int,
        val itemId: Int,
        val fromWatch: Boolean,
        val installId: Int?,
        val seq: Int?,
        /** Epoch ms, for the pager's "ticked in the last 30 minutes". */
        val at: Long = 0,
    )

    class Install(
        var lastSeq: Int,
    ) {
        /**
         * The results the watch has not settled yet, by seq (at most
         * [RESULT_HISTORY]), so a duplicate is re-acked correctly. A seq the
         * watch reports as acked is never asked about again, so it goes.
         */
        val results = LinkedHashMap<Int, OpResult>()

        fun settledThrough(seq: Int) {
            results.keys.removeAll { it <= seq }
        }
    }

    /** What the phone sends back: always, to hello and to every batch. */
    data class Reply(
        val installId: Int,
        val ackedThrough: Int,
        val ackExceptions: List<Pair<Int, OpResult>>,
        /** True when the list changed (or on hello): send the whole snapshot, else an ack-only page. */
        val fullSnapshot: Boolean,
    )

    var listRev: Int = 1
        private set
    val items = LinkedHashMap<Int, Item>()
    val ticks = mutableListOf<Tick>()
    val aliases = HashMap<Int, Int>()
    val installs = HashMap<Int, Install>()
    private var nextContribution = 1
    private var nextTick = 1
    private var nextItem = 1

    // ---- Phone-side list changes -------------------------------------------------

    fun addItem(
        name: String,
        contributions: Int = 1,
    ): Int {
        val id = nextItem++
        items[id] = Item(id, name)
        bump()
        repeat(contributions) { addContributionNoBump(id) }
        return id
    }

    /** A recipe or manual add that also needs this item: a new, uncovered contribution. */
    fun addContribution(itemId: Int) {
        bump()
        addContributionNoBump(itemId)
        items.getValue(itemId).rev++
    }

    private fun addContributionNoBump(itemId: Int) {
        items.getValue(itemId).contributions += Contribution(nextContribution++, listRev)
    }

    /** A tick on the phone covers every uncovered contribution. */
    fun phoneTick(
        itemId: Int,
        at: Long = 0,
    ): Boolean {
        val item = items.getValue(itemId)
        val open = item.contributions.filter { it.coveredBy == null }
        if (open.isEmpty()) return false
        val t = Tick(nextTick++, itemId, fromWatch = false, installId = null, seq = null, at = at)
        ticks += t
        open.forEach { it.coveredBy = t.id }
        bump(item)
        return true
    }

    /** Undoes the item's most recent tick, whoever made it. */
    fun phoneUntick(itemId: Int): Boolean {
        val t = ticks.lastOrNull { it.itemId == itemId } ?: return false
        undo(t)
        bump(items.getValue(itemId))
        return true
    }

    /** A use removed or rescaled to nothing: its contribution goes, covered or not. */
    fun removeContribution(itemId: Int) {
        val item = items.getValue(itemId)
        item.contributions.removeAt(item.contributions.lastIndex)
        bump(item)
    }

    fun deleteItem(itemId: Int) {
        items.getValue(itemId).deleted = true
        bump(items.getValue(itemId))
    }

    /** Catalogue re-key: [oldId] merges into [newId]; watch ops naming [oldId] are redirected. */
    fun rekey(
        oldId: Int,
        newId: Int,
    ) {
        val old = items.getValue(oldId)
        val target = items.getValue(newId)
        target.contributions += old.contributions
        ticks.replaceAll { if (it.itemId == oldId) it.copy(itemId = newId) else it }
        old.contributions.clear()
        old.deleted = true
        aliases[oldId] = newId
        target.rev = maxOf(target.rev, old.rev) + 1
        bump()
    }

    /** A restore: a new list epoch. Install tracking restarts from what each watch says. */
    fun newEpoch(newEpoch: Int) {
        epoch = newEpoch
        installs.clear()
        bump()
    }

    // ---- Watch messages ----------------------------------------------------------

    /** Watch clock for ticks made from the watch (epoch ms); the glue sets it from `watchNow` + offset. */
    var now: Long = 0

    fun receive(m: WatchMessage): Reply? =
        when (m) {
            is WatchMessage.Hello -> hello(m)
            is WatchMessage.Ops -> ops(m)
            is WatchMessage.Bye -> null
        }

    private fun hello(m: WatchMessage.Hello): Reply {
        val install = installs.getOrPut(m.installId) { Install(m.ackedThrough) }
        // A watch that adopted a new epoch voids the seqs it discarded by
        // reporting them as settled; jump over them so go-back-N has no gap.
        if (m.ackedThrough > install.lastSeq) install.lastSeq = m.ackedThrough
        install.settledThrough(m.ackedThrough)
        return reply(m.installId, full = true)
    }

    private fun ops(m: WatchMessage.Ops): Reply {
        val install = installs.getOrPut(m.installId) { Install(m.watchAckedThrough) }
        // The batch starts right after what the watch has settled: only results
        // from there on can still be asked for, so the reply carries only those.
        // (Sending every non-ok result in history could never fit the payload cap
        // once a handful of noops had piled up, and the watch then resent for ever.)
        // A batch with a gap is ignored below, and its claim is ignored with it.
        if (m.watchAckedThrough <= install.lastSeq) install.settledThrough(m.watchAckedThrough)
        var changed = false
        for (op in m.ops) {
            when {
                op.seq <= install.lastSeq -> Unit // duplicate: its result is re-sent from history
                op.seq != install.lastSeq + 1 -> break // gap: ignore the rest, the watch goes back
                else -> {
                    val result = if (m.epoch != epoch) OpResult.EPOCH else apply(m.installId, op)
                    if (result == OpResult.OK || result == OpResult.PARTIAL || result == OpResult.STILL_DONE) changed = true
                    install.lastSeq = op.seq
                    install.results[op.seq] = result
                    while (install.results.size > RESULT_HISTORY) install.results.remove(install.results.keys.first())
                }
            }
        }
        return reply(m.installId, full = changed)
    }

    private fun apply(
        installId: Int,
        op: WatchOp,
    ): OpResult {
        val item = resolve(op.itemId) ?: return OpResult.REJECTED
        return if (op.done) tickFromWatch(item, installId, op) else untickFromWatch(item, installId)
    }

    private fun tickFromWatch(
        item: Item,
        installId: Int,
        op: WatchOp,
    ): OpResult {
        val open = item.contributions.filter { it.coveredBy == null }
        val toCover = open.filter { it.createdListRev <= op.seenListRev }
        val later = open.size - toCover.size
        if (toCover.isEmpty()) return if (later > 0) OpResult.PARTIAL else OpResult.NOOP
        val t = Tick(nextTick++, item.id, fromWatch = true, installId = installId, seq = op.seq, at = now)
        ticks += t
        toCover.forEach { it.coveredBy = t.id }
        bump(item)
        return if (later > 0) OpResult.PARTIAL else OpResult.OK
    }

    private fun untickFromWatch(
        item: Item,
        installId: Int,
    ): OpResult {
        val t = ticks.lastOrNull { it.itemId == item.id && it.fromWatch && it.installId == installId } ?: return OpResult.NOOP
        undo(t)
        bump(item)
        return if (item.done) OpResult.STILL_DONE else OpResult.OK
    }

    private fun undo(t: Tick) {
        items.getValue(t.itemId).contributions.filter { it.coveredBy == t.id }.forEach { it.coveredBy = null }
        ticks.remove(t)
    }

    /** Follows ItemAlias; null when unknown or deleted. */
    fun resolve(id: Int): Item? {
        var cur = id
        repeat(8) {
            val item = items[cur]
            if (item != null && !item.deleted) return item
            cur = aliases[cur] ?: return null
        }
        return null
    }

    private fun reply(
        installId: Int,
        full: Boolean,
    ): Reply {
        val install = installs.getValue(installId)
        val ax = install.results.filter { it.value != OpResult.OK }.map { it.key to it.value }
        return Reply(installId, install.lastSeq, ax, full)
    }

    private fun bump(item: Item? = null) {
        listRev++
        if (item != null) item.rev++
    }

    /** When the item was last ticked, if it is ticked. */
    fun tickedAt(itemId: Int): Long? = ticks.filter { it.itemId == itemId }.maxOfOrNull { it.at }

    /** The phone's view: id → DONE, for live items. */
    fun doneView(): Map<Int, Boolean> = items.values.filter { !it.deleted }.associate { it.id to it.done }

    companion object {
        const val RESULT_HISTORY = 64
    }
}
