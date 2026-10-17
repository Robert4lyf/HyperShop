package com.robert4lyf.hypershop.core.sync

/** One list item as the pager sees it. */
data class PagerItem(
    val id: Int,
    val name: String,
    val sub: String,
    val aisleCode: String,
    val done: Boolean,
    val rev: Int,
    /** When the item was last ticked (epoch ms), for the "ticked in the last 30 minutes" rule. */
    val tickedAt: Long? = null,
)

data class PagerAisle(
    val code: String,
    val name: String,
    val hidden: Boolean,
)

data class SnapshotInput(
    val epoch: Int,
    val listRev: Int,
    val ackedThrough: Int,
    val ackExceptions: List<Pair<Int, OpResult>>,
    /** Aisles in store order. */
    val aisles: List<PagerAisle>,
    /** Items in store order (aisle order, then position). */
    val items: List<PagerItem>,
    val nowMs: Long,
    /** The aisle the watch was last ticking in, if known. Its DONE items are kept. */
    val currentAisle: String? = null,
)

/**
 * Builds the paged snapshot (PLAN §3.6).
 *
 * Every page carries the envelope and the ack data — reserved first, so an
 * ack always fits. Aisles then items fill each page up to [PayloadCap.bytes].
 * When everything would need more than [PayloadCap.maxPages] pages, items are
 * dropped in this order until it fits:
 *  1. DONE items, except those ticked in the last 30 minutes and those in the
 *     current aisle;
 *  2. every name shortened to 16 characters;
 *  3. unticked items, from the end of the store order.
 * Dropped items are counted in `more` ("+N on phone").
 */
object SnapshotPager {
    const val RECENT_MS = 30 * 60 * 1000L
    const val SHORT_NAME = 16
    private const val MAX_AX = 64

    fun build(
        input: SnapshotInput,
        cap: PayloadCap = PayloadCap(),
    ): List<SnapshotPage> {
        var items = input.items
        var dropped = 0
        paginate(input, items, dropped, cap)?.let { return it }

        // 1. Old DONE items outside the current aisle.
        val keep =
            items.filter {
                !it.done ||
                    (it.tickedAt != null && input.nowMs - it.tickedAt <= RECENT_MS) ||
                    it.aisleCode == input.currentAisle
            }
        dropped += items.size - keep.size
        items = keep
        paginate(input, items, dropped, cap)?.let { return it }

        // 2. Short names.
        items = items.map { it.copy(name = it.name.take(SHORT_NAME)) }
        paginate(input, items, dropped, cap)?.let { return it }

        // 3. Unticked items from the end of the store order (DONE ones that
        //    survived step 1 stay: they are recent or in the current aisle).
        val list = items.toMutableList()
        while (true) {
            val i = list.indexOfLast { !it.done }.takeIf { it >= 0 } ?: list.lastIndex
            if (i < 0) break
            list.removeAt(i)
            dropped++
            paginate(input, list, dropped, cap)?.let { return it }
        }
        return paginate(input, emptyList(), dropped, cap)
            ?: error("payload cap ${cap.bytes} cannot hold even the envelope and acks")
    }

    /** Pages for exactly these items, or null when more than maxPages would be needed. */
    private fun paginate(
        input: SnapshotInput,
        items: List<PagerItem>,
        dropped: Int,
        cap: PayloadCap,
    ): List<SnapshotPage>? {
        // Only aisles with something shown; `left` still counts every unticked
        // item, so the watch can show "+N on phone" for what was left off.
        val left = input.items.filter { !it.done }.groupingBy { it.aisleCode }.eachCount()
        val shownCodes = items.map { it.aisleCode }.toSet()
        val aisles =
            input.aisles.filter { it.code in shownCodes }.map {
                SnapAisle(it.code, it.name, left[it.code] ?: 0, it.hidden)
            }
        val aisleIndex = aisles.withIndex().associate { it.value.code to it.index }
        val snapItems =
            items.mapNotNull { i ->
                aisleIndex[i.aisleCode]?.let { SnapItem(i.id, i.name, i.sub, it, i.done, i.rev) }
            }
        val ax = input.ackExceptions.takeLast(MAX_AX)

        val pages = mutableListOf<SnapshotPage>()
        var ai = 0
        var ii = 0
        while (ai < aisles.size || ii < snapItems.size || pages.isEmpty()) {
            if (pages.size >= cap.maxPages) return null
            var page =
                SnapshotPage(input.epoch, input.listRev, input.ackedThrough, ax, pages.size, 0, emptyList(), ai, emptyList(), dropped)
            if (size(page) > cap.bytes) return null
            while (ai < aisles.size) {
                val next = page.copy(aisles = page.aisles + aisles[ai])
                if (size(next) > cap.bytes) break
                page = next
                ai++
            }
            if (ai == aisles.size) {
                while (ii < snapItems.size) {
                    val next = page.copy(items = page.items + snapItems[ii])
                    if (size(next) > cap.bytes) break
                    page = next
                    ii++
                }
            }
            if (page.aisles.isEmpty() && page.items.isEmpty() && (ai < aisles.size || ii < snapItems.size)) {
                return null // a single entry is bigger than a page
            }
            pages += page
        }
        val n = pages.size
        // `pgs` is filled in last; it can only grow the page by a digit, so leave headroom.
        return pages.map { it.copy(pages = n) }.takeIf { all -> all.all { size(it) <= cap.bytes } }
    }

    fun size(p: SnapshotPage): Int = PayloadSize.of(WatchCodec.encodePage(p.copy(pages = maxOf(p.pages, 1))))
}
