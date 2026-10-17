package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.parse.Normaliser

/** What a re-key did, so the repository can move overrides and staples. */
data class RekeyResult(
    val list: ShoppingList,
    /** mergeKey changes, old → new. */
    val renamed: Map<String, String>,
    /** Items merged away, loser id → survivor id (also recorded as ItemAlias). */
    val merged: Map<Long, Long>,
)

/**
 * Catalogue re-key (PLAN §3.3). After a catalogue update, an item keyed by
 * `name:` whose name now resolves to a catalogue id gets the `cat:` key. When
 * that clashes with an existing item:
 * - the lower id survives and the contributions (and ticks) move to it;
 * - the survivor's aisle and position win;
 * - its new rev is the higher of the two plus 1;
 * - `ItemAlias(old → new)` redirects watch ops that still use the old id.
 * Override precedence (yours over seeded) is applied by the repository from [RekeyResult].
 */
object Rekey {
    fun rekey(
        list: ShoppingList,
        normaliser: Normaliser,
    ): RekeyResult {
        var l = list
        val renamed = LinkedHashMap<String, String>()
        val merged = LinkedHashMap<Long, Long>()
        for (item in list.liveItems.sortedBy { it.id }) {
            if (!item.mergeKey.startsWith("name:")) continue
            val entry = normaliser.entry(item.normName) ?: continue
            val newKey = entry.mergeKey
            val current = l.items.getValue(item.id)
            val clash = l.liveItems.firstOrNull { it.mergeKey == newKey && it.id != item.id }
            if (clash == null) {
                l = l.copy(items = l.items + (item.id to current.copy(mergeKey = newKey, catalogId = entry.id))).bumped(setOf(item.id))
                renamed[item.mergeKey] = newKey
                continue
            }
            val survivor = if (clash.id < item.id) clash else current
            val loser = if (survivor.id == clash.id) current else clash
            val rev = maxOf(survivor.rev, loser.rev) + 1
            l =
                l.copy(
                    items =
                        l.items - loser.id +
                            (survivor.id to survivor.copy(mergeKey = newKey, catalogId = entry.id, rev = rev - 1)),
                    contributions = l.contributions.map { if (it.itemId == loser.id) it.copy(itemId = survivor.id) else it },
                    ticks = l.ticks.map { if (it.itemId == loser.id) it.copy(itemId = survivor.id) else it },
                    aliases = l.aliases + (loser.id to survivor.id),
                ).bumped(setOf(survivor.id))
            renamed[item.mergeKey] = newKey
            merged[loser.id] = survivor.id
        }
        return RekeyResult(l, renamed, merged)
    }
}
