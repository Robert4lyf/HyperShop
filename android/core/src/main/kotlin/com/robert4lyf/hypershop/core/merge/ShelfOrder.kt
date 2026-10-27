package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.model.AisleCode

/**
 * X.9: the order things are found in within an aisle, which the shopper can arrange
 * and which outlives the trip. Groups keep together (the herbs, the salad), in the
 * catalogue's order until they are arranged; within a group what was arranged comes
 * first, in that order, and the rest follow as they were added.
 *
 * Ranks are keyed by [groupKey] and [itemKey]. An item's key carries its aisle and
 * group, so an item that moves aisle starts unarranged there rather than borrowing
 * a rank from somewhere it no longer is.
 */
data class ShelfOrder(
    val ranks: Map<String, Int> = emptyMap(),
) {
    /** A row's place in its aisle: compare these, then the item's position. */
    data class Slot(
        val aisle: AisleCode,
        val group: String?,
        val mergeKey: String,
        val position: Int,
    )

    /** Where [group] sits in [aisle]: as arranged, else after everything arranged, in the catalogue's order. */
    fun groupSort(
        aisle: AisleCode,
        group: String?,
        catalogueRank: Int,
    ): Long = ranks[groupKey(aisle, group)]?.toLong() ?: (UNRANKED + catalogueRank)

    fun itemSort(s: Slot): Long = ranks[itemKey(s.aisle, s.group, s.mergeKey)]?.toLong() ?: UNRANKED

    /**
     * Moves the group [group] past its neighbour among [present] (the aisle's groups
     * as shown), [by] -1 for up and +1 for down. [known] is every group the aisle can
     * have, so groups not on today's list keep their places relative to the rest.
     * Null when there is no neighbour that way.
     */
    fun movedGroup(
        aisle: AisleCode,
        present: List<String?>,
        known: List<String?>,
        group: String?,
        by: Int,
        catalogueRank: (String?) -> Int,
    ): ShelfOrder? {
        val all = (known + present).distinct().sortedBy { groupSort(aisle, it, catalogueRank(it)) }
        return moved(all.map { groupKey(aisle, it) }, present.map { groupKey(aisle, it) }, groupKey(aisle, group), by)
    }

    /**
     * Moves [item] past its neighbour among [present] (its group's rows as shown, in order).
     * Items arranged before but not on today's list keep their places relative to the rest.
     */
    fun movedItem(
        present: List<Slot>,
        item: Slot,
        by: Int,
    ): ShelfOrder? {
        val prefix = itemKey(item.aisle, item.group, "")
        val shownKeys = present.map { itemKey(it.aisle, it.group, it.mergeKey) }
        val absent = ranks.filterKeys { it.startsWith(prefix) && it !in shownKeys }
        val ranked = (
            absent.keys.map { it to absent.getValue(it).toLong() } +
                present.map {
                        k ->
                    itemKey(k.aisle, k.group, k.mergeKey) to itemSort(k)
                }
        )
        // Stable: unranked rows keep the order they are shown in.
        val all = ranked.withIndex().sortedWith(compareBy({ it.value.second }, { it.index })).map { it.value.first }
        return moved(all, shownKeys, itemKey(item.aisle, item.group, item.mergeKey), by)
    }

    private fun moved(
        all: List<String>,
        shown: List<String>,
        key: String,
        by: Int,
    ): ShelfOrder? {
        val at = shown.indexOf(key)
        val neighbour = shown.getOrNull(at + by)
        if (at < 0 || neighbour == null) return null
        val rest = all - key
        val n = rest.indexOf(neighbour)
        val order = rest.toMutableList().apply { add(if (by < 0) n else n + 1, key) }
        return copy(ranks = ranks + order.withIndex().associate { (i, k) -> k to i })
    }

    companion object {
        /** Past any rank an arrangement gives (they count from 0). */
        private const val UNRANKED = 1_000_000L

        fun groupKey(
            aisle: AisleCode,
            group: String?,
        ): String = "g:${aisle.name}:${group.orEmpty()}"

        fun itemKey(
            aisle: AisleCode,
            group: String?,
            mergeKey: String,
        ): String = "i:${aisle.name}:${group.orEmpty()}:$mergeKey"
    }
}
