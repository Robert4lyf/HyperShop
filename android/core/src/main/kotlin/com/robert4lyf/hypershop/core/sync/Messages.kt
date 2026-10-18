package com.robert4lyf.hypershop.core.sync

/** Op result codes (PLAN §3.6). */
enum class OpResult(
    val wire: String,
) {
    OK(WatchProtocol.Result.OK),
    NOOP(WatchProtocol.Result.NOOP),
    PARTIAL(WatchProtocol.Result.PARTIAL),
    STILL_DONE(WatchProtocol.Result.STILL_DONE),
    REJECTED(WatchProtocol.Result.REJECTED),
    EPOCH(WatchProtocol.Result.EPOCH),
    ;

    companion object {
        private val byWire = entries.associateBy { it.wire }

        fun fromWire(s: String): OpResult? = byWire[s]
    }
}

/** A tick or untick from the watch: `[seq, itemId, done, seenListRev, ts]`. */
data class WatchOp(
    val seq: Int,
    val itemId: Int,
    val done: Boolean,
    val seenListRev: Int,
    /** Watch clock, epoch seconds. */
    val ts: Long,
)

sealed interface WatchMessage {
    val epoch: Int
    val installId: Int

    data class Hello(
        override val epoch: Int,
        override val installId: Int,
        val listRev: Int,
        val watchNow: Long,
        val ackedThrough: Int,
    ) : WatchMessage

    data class Ops(
        override val epoch: Int,
        override val installId: Int,
        val ops: List<WatchOp>,
    ) : WatchMessage {
        init {
            require(ops.isNotEmpty()) { "an op batch holds at least one op" }
        }

        /** The watch's own ackedThrough: its outbox is a contiguous run from there. */
        val watchAckedThrough: Int get() = ops.first().seq - 1
    }

    data class Bye(
        override val epoch: Int,
        override val installId: Int,
    ) : WatchMessage
}

/** `[id, name, sub, aisleIdx, done, rev]`. [aisleIdx] indexes the snapshot's whole aisle list. */
data class SnapItem(
    val id: Int,
    val name: String,
    val sub: String,
    val aisleIdx: Int,
    val done: Boolean,
    val rev: Int,
)

/** `[code, name, left, hidden]`. [left] counts every unticked item in the aisle, sent or not. */
data class SnapAisle(
    val code: String,
    val name: String,
    val left: Int,
    val hidden: Boolean,
)

/** One page of a snapshot. A snapshot is complete once pages 0 until [pages] of one [listRev] arrived. */
data class SnapshotPage(
    val epoch: Int,
    val listRev: Int,
    val ackedThrough: Int,
    /** `[seq, result]` for acked seqs whose result is not `ok`. */
    val ackExceptions: List<Pair<Int, OpResult>>,
    val page: Int,
    val pages: Int,
    /** Aisles in store order. Each page carries a contiguous slice; [aisleBase] is its first index. */
    val aisles: List<SnapAisle>,
    val aisleBase: Int,
    val items: List<SnapItem>,
    /** Items left off the snapshot entirely ("+N on phone"). */
    val more: Int,
)
