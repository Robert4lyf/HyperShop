package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.model.Rational

enum class UseKind { RECIPE, MANUAL }

enum class TickSource { PHONE, WATCH }

data class ListItem(
    val id: Long,
    val mergeKey: String,
    val catalogId: String?,
    val normName: String,
    val displayName: String,
    val aisle: AisleCode,
    val position: Int,
    val rev: Long = 1,
    val deletedAt: Long? = null,
)

data class RecipeUse(
    val id: Long,
    val recipeId: Long?,
    val kind: UseKind,
    val servings: Int,
    val addedAt: Long,
    val archivedAt: Long? = null,
)

/** Already scaled, with "I have some" already subtracted (PLAN §3.3). */
data class Contribution(
    val id: Long,
    val useId: Long,
    val recipeLineId: Long?,
    val itemId: Long,
    val qty: QtyBase,
    val noScale: Boolean,
    val createdListRev: Long,
    val coveredBy: Long? = null,
    /** The line's own unit, for the "as written" units setting. */
    val unitHint: QtyUnit? = null,
)

data class Tick(
    val id: Long,
    val itemId: Long,
    val at: Long,
    val source: TickSource,
    val installId: Int? = null,
    val seq: Int? = null,
)

/**
 * The whole list as pure data, with every list operation as a function that
 * returns a new list (PLAN §3.3). `:data` runs these inside one Room
 * transaction each and writes the difference; the property tests run them
 * directly.
 *
 * Every operation that changes an item's contributions, ticks or aisle bumps
 * that item's `rev`, and every operation bumps `listRev` once.
 */
data class ShoppingList(
    val items: Map<Long, ListItem> = emptyMap(),
    val uses: Map<Long, RecipeUse> = emptyMap(),
    val contributions: List<Contribution> = emptyList(),
    val ticks: List<Tick> = emptyList(),
    val aliases: Map<Long, Long> = emptyMap(),
    val listRev: Long = 1,
    val nextId: Long = 1,
) {
    val liveItems: List<ListItem> get() = items.values.filter { it.deletedAt == null }

    fun contributionsOf(itemId: Long): List<Contribution> = contributions.filter { it.itemId == itemId }

    fun isDone(itemId: Long): Boolean = contributionsOf(itemId).let { cs -> cs.isNotEmpty() && cs.all { it.coveredBy != null } }

    fun isPartial(itemId: Long): Boolean =
        contributionsOf(itemId).let { cs ->
            cs.any {
                it.coveredBy != null
            } && cs.any { it.coveredBy == null }
        }

    fun total(itemId: Long): QtyBase = contributionsOf(itemId).fold(QtyBase.PRESENCE) { a, c -> a + c.qty }

    fun uncovered(itemId: Long): QtyBase =
        contributionsOf(
            itemId,
        ).filter { it.coveredBy == null }.fold(QtyBase.PRESENCE) { a, c -> a + c.qty }

    fun covered(itemId: Long): QtyBase =
        contributionsOf(
            itemId,
        ).filter { it.coveredBy != null }.fold(QtyBase.PRESENCE) { a, c -> a + c.qty }

    fun itemByKey(mergeKey: String): ListItem? = items.values.firstOrNull { it.mergeKey == mergeKey && it.deletedAt == null }

    /** Follows ItemAlias (catalogue re-keys). */
    fun resolve(id: Long): ListItem? {
        var cur = id
        repeat(8) {
            items[cur]?.let { if (it.deletedAt == null) return it }
            cur = aliases[cur] ?: return null
        }
        return null
    }

    // ---- ticking (3.2b) ---------------------------------------------------------

    /** A phone tick covers every uncovered contribution. Null when there is nothing to cover. */
    fun tick(
        itemId: Long,
        at: Long,
        source: TickSource = TickSource.PHONE,
    ): ShoppingList? {
        if (contributionsOf(itemId).none { it.coveredBy == null }) return null
        val t = Tick(nextId, itemId, at, source)
        return copy(
            ticks = ticks + t,
            contributions = contributions.map { if (it.itemId == itemId && it.coveredBy == null) it.copy(coveredBy = t.id) else it },
            nextId = nextId + 1,
        ).bumped(setOf(itemId))
    }

    /** Uncovers exactly the contributions of the item's most recent tick. */
    fun untick(itemId: Long): ShoppingList? {
        val t = ticks.lastOrNull { it.itemId == itemId } ?: return null
        return copy(
            ticks = ticks - t,
            contributions = contributions.map { if (it.coveredBy == t.id) it.copy(coveredBy = null) else it },
        ).bumped(setOf(itemId))
    }

    /**
     * Soft-deletes DONE items (stamped [now], so [undoDelete] can bring exactly
     * them back) and archives uses fully covered for 7 days.
     */
    fun finishShopping(now: Long): ShoppingList {
        val done = liveItems.filter { isDone(it.id) }.map { it.id }.toSet()
        val archived =
            uses.mapValues { (id, u) ->
                val cs = contributions.filter { it.useId == id }
                val coveredFor7Days =
                    cs.isNotEmpty() &&
                        cs.all { c -> c.coveredBy != null && (ticks.firstOrNull { it.id == c.coveredBy }?.at ?: now) <= now - SEVEN_DAYS }
                if (u.archivedAt == null && coveredFor7Days) u.copy(archivedAt = now) else u
            }
        return copy(items = items.mapValues { (id, i) -> if (id in done) i.copy(deletedAt = now) else i }, uses = archived).bumped(done)
    }

    fun clearAll(now: Long): ShoppingList {
        val live = liveItems.map { it.id }.toSet()
        return copy(items = items.mapValues { (id, i) -> if (id in live) i.copy(deletedAt = now) else i }).bumped(live)
    }

    fun softDelete(
        itemId: Long,
        now: Long,
    ): ShoppingList = copy(items = items.mapValues { (id, i) -> if (id == itemId) i.copy(deletedAt = now) else i }).bumped(setOf(itemId))

    /** Undoes a soft delete, a "Finish shopping" or a "Clear all" stamped [stamp]. */
    fun undoDelete(stamp: Long): ShoppingList {
        val back = items.values.filter { it.deletedAt == stamp }.map { it.id }.toSet()
        return copy(items = items.mapValues { (id, i) -> if (id in back) i.copy(deletedAt = null) else i }).bumped(back)
    }

    // ---- uses (3.2a, 5.2c) ------------------------------------------------------

    /**
     * Removes a use and everything that cascades from it: its contributions,
     * ticks left covering nothing, and items left with no contributions. This
     * is also Undo of an import.
     */
    fun removeUse(useId: Long): ShoppingList {
        val gone = contributions.filter { it.useId == useId }
        val affected = gone.map { it.itemId }.toSet()
        val rest = contributions.filter { it.useId != useId }
        val liveTicks = ticks.filter { t -> rest.any { it.coveredBy == t.id } }
        val empty = affected.filter { id -> rest.none { it.itemId == id } }.toSet()
        return copy(
            uses = uses - useId,
            contributions = rest,
            ticks = liveTicks,
            items = items - empty,
        ).bumped(affected - empty)
    }

    /** Rescales a recipe use to new servings; `noScale` contributions stay. */
    fun rescaleUse(
        useId: Long,
        servings: Int,
    ): ShoppingList {
        val use = uses[useId] ?: return this
        if (servings <= 0 || servings == use.servings) return this
        val f = Rational.of(servings.toLong(), use.servings.toLong())
        val affected = contributions.filter { it.useId == useId && !it.noScale }.map { it.itemId }.toSet()
        return copy(
            uses = uses + (useId to use.copy(servings = servings)),
            contributions = contributions.map { if (it.useId == useId && !it.noScale) it.copy(qty = it.qty.times(f)) else it },
        ).bumped(affected)
    }

    // ---- manual changes ---------------------------------------------------------

    /** The single MANUAL use, created on first need. */
    fun manualUse(now: Long): Pair<ShoppingList, Long> {
        uses.values.firstOrNull { it.kind == UseKind.MANUAL }?.let { return this to it.id }
        val u = RecipeUse(nextId, null, UseKind.MANUAL, 1, now)
        return copy(uses = uses + (u.id to u), nextId = nextId + 1) to u.id
    }

    /**
     * Adds an item by hand (the "Add item" field): a new contribution in the
     * MANUAL use, on the existing item for this mergeKey or a new one.
     */
    fun manualAdd(
        target: NewItem,
        qty: QtyBase,
        unitHint: QtyUnit?,
        now: Long,
    ): Pair<ShoppingList, Long> {
        val (l, useId) = manualUse(now)
        val (l2, itemId) = l.ensureItem(target)
        val rev = l2.listRev + 1
        val c = Contribution(l2.nextId, useId, null, itemId, qty, noScale = qty.isPresence, createdListRev = rev, unitHint = unitHint)
        return l2.copy(contributions = l2.contributions + c, nextId = l2.nextId + 1).bumped(setOf(itemId)) to itemId
    }

    /**
     * Sets the item's total to [newTotal] per dimension by changing its MANUAL
     * contribution (which may go negative). The shown total is clamped at 0.
     */
    fun editQuantity(
        itemId: Long,
        newTotal: QtyBase,
        now: Long,
    ): ShoppingList {
        val (l, useId) = manualUse(now)
        val current = l.total(itemId)
        val keys = (current.parts.keys + newTotal.parts.keys)
        val delta =
            QtyBase(
                keys.associateWith {
                        k ->
                    (newTotal.parts[k] ?: Rational.ZERO) - (current.parts[k] ?: Rational.ZERO)
                }.filterValues { !it.isZero },
            )
        if (delta.isPresence) return l
        val existing = l.contributions.firstOrNull { it.itemId == itemId && it.useId == useId && it.coveredBy == null }
        val rev = l.listRev + 1
        val cs =
            if (existing != null) {
                l.contributions.map { if (it.id == existing.id) it.copy(qty = it.qty + delta) else it }
            } else {
                l.contributions + Contribution(l.nextId, useId, null, itemId, delta, noScale = false, createdListRev = rev)
            }
        return l.copy(contributions = cs, nextId = if (existing == null) l.nextId + 1 else l.nextId).bumped(setOf(itemId))
    }

    fun moveAisle(
        itemId: Long,
        aisle: AisleCode,
    ): ShoppingList {
        val i = items[itemId] ?: return this
        return copy(items = items + (itemId to i.copy(aisle = aisle, position = nextPosition(aisle)))).bumped(setOf(itemId))
    }

    // ---- helpers ----------------------------------------------------------------

    internal fun ensureItem(t: NewItem): Pair<ShoppingList, Long> {
        itemByKey(t.mergeKey)?.let { return this to it.id }
        val item = ListItem(nextId, t.mergeKey, t.catalogId, t.normName, t.displayName, t.aisle, nextPosition(t.aisle))
        return copy(items = items + (item.id to item), nextId = nextId + 1) to item.id
    }

    internal fun nextPosition(aisle: AisleCode): Int = (items.values.filter { it.aisle == aisle }.maxOfOrNull { it.position } ?: -1) + 1

    /** One transaction's worth of bookkeeping: listRev + 1, and each touched item's rev + 1. */
    internal fun bumped(itemIds: Set<Long>): ShoppingList =
        copy(
            listRev = listRev + 1,
            items = items.mapValues { (id, i) -> if (id in itemIds) i.copy(rev = i.rev + 1) else i },
        )

    companion object {
        const val SEVEN_DAYS = 7L * 24 * 60 * 60 * 1000
    }
}

/** What an incoming line needs to become an item. */
data class NewItem(
    val mergeKey: String,
    val catalogId: String?,
    val normName: String,
    val displayName: String,
    val aisle: AisleCode,
)
