package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.Dimension
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

/**
 * A use's shopping, counted in items ([ShoppingList.progress]). [partlyBought] are
 * among [toBuy]: some of the item is bought, more is still needed.
 */
data class UseProgress(
    val toBuy: Int,
    val partlyBought: Int,
    val bought: Int,
    val removed: Int,
) {
    val state: UseState
        get() =
            when {
                toBuy > 0 -> UseState.STILL_NEEDED
                bought == 0 && removed == 0 -> UseState.NOTHING_TO_BUY
                bought == 0 -> UseState.DROPPED
                removed == 0 -> UseState.ALL_BOUGHT
                else -> UseState.BOUGHT_THE_REST
            }
}

/**
 * - [STILL_NEEDED]: something it needs is on the list, unbought.
 * - [ALL_BOUGHT]: it needed shopping, and every item was bought.
 * - [BOUGHT_THE_REST]: bought what it needed, after taking some items off unbought.
 * - [NOTHING_TO_BUY]: it added nothing (every line at home).
 * - [DROPPED]: its items were taken off unbought, none bought ("Clear all", deleted by hand).
 *
 * "No longer planned" is not a state here: that is the use removed or archived.
 */
enum class UseState { STILL_NEEDED, ALL_BOUGHT, BOUGHT_THE_REST, NOTHING_TO_BUY, DROPPED }

data class RecipeUse(
    val id: Long,
    val recipeId: Long?,
    val kind: UseKind,
    val servings: Int,
    val addedAt: Long,
    val archivedAt: Long? = null,
)

/** One recipe line for [ShoppingList.addToUse], already scaled. */
data class UseLineAdd(
    val target: NewItem,
    val recipeLineId: Long,
    val qty: QtyBase,
    val noScale: Boolean,
    val unitHint: QtyUnit?,
)

/** Already scaled (PLAN §3.3). */
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

    // Built once per list, on first use: the list screen asks for every item's
    // contributions several times per redraw. Same order as [contributions].
    private val byItem: Map<Long, List<Contribution>> by lazy { contributions.groupBy { it.itemId } }
    private val byUse: Map<Long, List<Contribution>> by lazy { contributions.groupBy { it.useId } }

    fun contributionsOf(itemId: Long): List<Contribution> = byItem[itemId] ?: emptyList()

    /** A use's contributions, in id order. */
    fun contributionsOfUse(useId: Long): List<Contribution> = byUse[useId] ?: emptyList()

    /**
     * The live rows [useIds] still need something from, each counted once
     * however many of the uses share it: two recipes wanting onions are one
     * onion row on the list, so one item still needed, not two.
     */
    fun rowsStillNeeded(useIds: Collection<Long>): Set<Long> {
        val wanted = useIds.toSet()
        return contributions
            .filter { it.useId in wanted && it.coveredBy == null && items[it.itemId]?.deletedAt == null }
            .map { it.itemId }
            .toSet()
    }

    /**
     * Where a use's shopping stands, item by item, from its own contributions: an
     * item it still needs (an uncovered contribution on a live item), one it has
     * bought (every one of its contributions on the item covered), and one taken
     * off the list unbought (an uncovered contribution on a deleted item). Ticks
     * cover exactly what existed when they were made, so more of an item added
     * later (another recipe, more servings) is still needed by the use that added
     * it, and already bought for the one that didn't. Derived, never stored.
     */

    fun progress(useId: Long): UseProgress {
        var toBuy = 0
        var partly = 0
        var bought = 0
        var removed = 0
        for ((itemId, cs) in contributionsOfUse(useId).groupBy { it.itemId }) {
            val live = items[itemId]?.deletedAt == null
            val open = cs.any { it.coveredBy == null }
            when {
                open && live -> {
                    toBuy++
                    if (isPartial(itemId)) partly++
                }
                open -> removed++
                else -> bought++
            }
        }
        return UseProgress(toBuy, partly, bought, removed)
    }

    /**
     * Archives the recipe uses added before [before] with nothing left on the list
     * (their bought items purged, or every line at home), as [finishShopping] would
     * have: they are not the week's reminder any more. Null when there are none.
     */
    fun archiveSpentUses(
        before: Long,
        now: Long,
    ): ShoppingList? {
        val spent =
            uses.values.filter {
                it.kind == UseKind.RECIPE && it.archivedAt == null && it.addedAt < before && contributionsOfUse(it.id).isEmpty()
            }
        if (spent.isEmpty()) return null
        return copy(uses = uses + spent.map { it.id to it.copy(archivedAt = now) }).bumped(emptySet())
    }

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
        val tickAt = HashMap<Long, Long>(ticks.size).also { m -> ticks.forEach { m.putIfAbsent(it.id, it.at) } }
        val archived =
            uses.mapValues { (id, u) ->
                val cs = contributionsOfUse(id)
                val coveredFor7Days =
                    cs.isNotEmpty() &&
                        cs.all { c -> c.coveredBy != null && (tickAt[c.coveredBy] ?: now) <= now - SEVEN_DAYS }
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

    /**
     * Undoes a soft delete, a "Finish shopping" or a "Clear all" stamped [stamp].
     * An item whose key is live again (re-added since) stays deleted.
     */
    fun undoDelete(stamp: Long): ShoppingList {
        val liveKeys = liveItems.map { it.mergeKey }.toSet()
        // One row per key: two operations stamped in the same millisecond can each have deleted a row
        // with the same key, and only one of them may come back (keys are unique among live rows).
        val back =
            items.values
                .filter { it.deletedAt == stamp && it.mergeKey !in liveKeys }
                .groupBy { it.mergeKey }
                .map { (_, rows) -> rows.maxOf { it.id } }
                .toSet()
        // "Finish shopping" also archived uses with the same stamp.
        return copy(
            items = items.mapValues { (id, i) -> if (id in back) i.copy(deletedAt = null) else i },
            uses = uses.mapValues { (_, u) -> if (u.archivedAt == stamp) u.copy(archivedAt = null) else u },
        ).bumped(back)
    }

    // ---- watch ops (PLAN §3.6, docs/garmin-protocol.md) ----------------------------

    /**
     * A watch tick: covers the uncovered contributions created at or before
     * [seenListRev]. Returns the new list (or null when nothing changed) and the result.
     */
    fun watchTick(
        itemId: Long,
        seenListRev: Long,
        at: Long,
        installId: Int,
        seq: Int,
    ): Pair<ShoppingList?, WatchTickResult> {
        val open = contributionsOf(itemId).filter { it.coveredBy == null }
        val toCover = open.filter { it.createdListRev <= seenListRev }.map { it.id }.toSet()
        val later = open.size - toCover.size
        if (toCover.isEmpty()) return null to if (later > 0) WatchTickResult.PARTIAL else WatchTickResult.NOOP
        val t = Tick(nextId, itemId, at, TickSource.WATCH, installId, seq)
        val l =
            copy(
                ticks = ticks + t,
                contributions = contributions.map { if (it.id in toCover) it.copy(coveredBy = t.id) else it },
                nextId = nextId + 1,
            ).bumped(setOf(itemId))
        return l to if (later > 0) WatchTickResult.PARTIAL else WatchTickResult.OK
    }

    /** A watch untick: undoes this install's most recent watch tick on the item. */
    fun watchUntick(
        itemId: Long,
        installId: Int,
    ): Pair<ShoppingList?, WatchTickResult> {
        val t =
            ticks.lastOrNull { it.itemId == itemId && it.source == TickSource.WATCH && it.installId == installId }
                ?: return null to WatchTickResult.NOOP
        val l =
            copy(
                ticks = ticks - t,
                contributions = contributions.map { if (it.coveredBy == t.id) it.copy(coveredBy = null) else it },
            ).bumped(setOf(itemId))
        return l to if (l.isDone(itemId)) WatchTickResult.STILL_DONE else WatchTickResult.OK
    }

    // ---- uses (3.2a, 5.2c) ------------------------------------------------------

    /**
     * Removes a use and everything that cascades from it: its contributions,
     * ticks left covering nothing, and items left with no contributions. This
     * is also Undo of an import.
     */
    fun removeUse(useId: Long): ShoppingList = removeUseKeeping(useId).first

    /**
     * [removeUse], also returning what went (null when there was no such use),
     * so "Remove from list" can offer Undo through [restoreUse].
     */
    fun removeUseKeeping(useId: Long): Pair<ShoppingList, RemovedUse?> {
        val use = uses[useId]
        val gone = contributions.filter { it.useId == useId }
        val affected = gone.map { it.itemId }.toSet()
        val rest = contributions.filter { it.useId != useId }
        val covering = rest.mapNotNull { it.coveredBy }.toSet()
        val liveTicks = ticks.filter { it.id in covering }
        val empty = affected.filter { id -> rest.none { it.itemId == id } }.toSet()
        val removed =
            use?.let {
                RemovedUse(
                    use = it,
                    // Every row it was on, not only the ones it emptied: a shared row can go later
                    // (another recipe removed, the purge), and Undo must not point at nothing.
                    items = affected.mapNotNull { id -> items[id] },
                    contributions = gone,
                    ticks = ticks.filter { t -> t.id !in covering },
                )
            }
        return copy(
            uses = uses - useId,
            contributions = rest,
            ticks = liveTicks,
            items = items - empty,
        ).bumped(affected - empty) to removed
    }

    /**
     * Undo of [removeUse]: the use, its contributions and the ticks that only
     * covered them come back. An item removed with it comes back too, unless its
     * key is live again (re-added since), in which case its contributions join that
     * item. Ids are never reused, so the old ids are free. Null when the use is
     * back already.
     */
    fun restoreUse(r: RemovedUse): ShoppingList? {
        if (r.use.id in uses) return null
        var items = this.items
        val redirect = HashMap<Long, Long>()
        for (i in r.items) {
            if (i.id in items) continue
            val live = itemByKey(i.mergeKey)
            if (live != null) redirect[i.id] = live.id else items = items + (i.id to i)
        }
        // A tick that has gone since (an untick) no longer covers anything: what it covered is needed again.
        val tickIds = ticks.map { it.id }.toSet() + r.ticks.map { it.id }
        val cs =
            r.contributions.map { c ->
                val moved = redirect[c.itemId]?.let { c.copy(itemId = it) } ?: c
                if (moved.coveredBy != null && moved.coveredBy !in tickIds) moved.copy(coveredBy = null) else moved
            }
        val ts = r.ticks.map { t -> redirect[t.itemId]?.let { t.copy(itemId = it) } ?: t }
        return copy(
            uses = uses + (r.use.id to r.use),
            items = items,
            contributions = contributions + cs,
            ticks = ticks + ts,
        ).bumped(cs.map { it.itemId }.toSet())
    }

    /** Rescales a recipe use to new servings; `noScale` contributions stay. */
    fun rescaleUse(
        useId: Long,
        servings: Int,
    ): ShoppingList {
        val use = uses[useId] ?: return this
        if (servings <= 0 || servings == use.servings) return this
        val f = Rational.of(servings.toLong(), use.servings.toLong())
        val scaled = contributions.filter { it.useId == useId && !it.noScale }
        val rev = listRev + 1
        var nextId = nextId
        val replaced = HashMap<Long, Contribution?>()
        val added = ArrayList<Contribution>()
        val created = LinkedHashMap<Long, ListItem>()
        // By recipe line, not by row: after a finished trip a line's bought part stays on the finished
        // (deleted) row and its extra lives on a new one, and the two have to be scaled as one amount.
        for (group in scaled.groupBy { it.recipeLineId ?: -it.itemId }.values) {
            val covered = group.filter { it.coveredBy != null }
            val open = group.filter { it.coveredBy == null }
            val target = group.fold(QtyBase.PRESENCE) { acc, c -> acc + c.qty }.times(f)
            val bought = covered.fold(QtyBase.PRESENCE) { acc, c -> acc + c.qty }
            when {
                covered.isEmpty() || target.isPresence -> group.forEach { replaced[it.id] = it.copy(qty = it.qty.times(f)) }
                // What was bought stays bought, and only the difference is still needed: a
                // tick covers the amount there was when it was made, never an amount added later.
                bought.parts.all { (k, v) -> (target.parts[k] ?: Rational.ZERO) >= v } -> {
                    val rest = target.minusClamped(bought)
                    open.forEach { replaced[it.id] = null }
                    if (!rest.isPresence) {
                        val first = open.firstOrNull()
                        if (first != null) {
                            replaced[first.id] = first.copy(qty = rest)
                        } else {
                            // Bought and finished: the extra goes back on the list, onto the live row for it if
                            // there is one, else a new row. The finished row stays finished with what was bought:
                            // reviving it would leave a bought row on the list once the extra went again.
                            val c = covered.first()
                            val item = items.getValue(c.itemId)
                            val onto =
                                if (item.deletedAt == null) {
                                    item.id
                                } else {
                                    itemByKey(item.mergeKey)?.id
                                        ?: created.values.firstOrNull { it.mergeKey == item.mergeKey }?.id
                                        ?: item.copy(
                                            id = nextId++,
                                            rev = 1,
                                            deletedAt = null,
                                            position = nextPosition(item.aisle) + created.size,
                                        ).also { created[it.id] = it }.id
                                }
                            added += Contribution(nextId++, useId, c.recipeLineId, onto, rest, false, rev, unitHint = c.unitHint)
                        }
                    }
                }
                // Fewer servings than were bought for: nothing left to buy, the bought amount shrinks with it.
                else -> {
                    val k = bought.parts.keys.first()
                    val r = (target.parts[k] ?: Rational.ZERO) / bought.parts.getValue(k)
                    covered.forEach { replaced[it.id] = it.copy(qty = it.qty.times(r)) }
                    open.forEach { replaced[it.id] = null }
                }
            }
        }
        val kept = contributions.mapNotNull { if (it.id in replaced) replaced[it.id] else it } + added
        // A live row this left with nothing on it (the extra of a finished trip, scaled away again) goes:
        // it was never bought, so there is no tick to keep. A row still holding anything stays.
        val touched = scaled.map { it.itemId }.toSet() + added.map { it.itemId }
        val holding = kept.map { it.itemId }.toSet()
        val emptied = touched.filter { id -> id !in holding && (items[id] ?: created[id])?.deletedAt == null }.toSet()
        return copy(
            uses = uses + (useId to use.copy(servings = servings)),
            items = items + created - emptied,
            contributions = kept,
            ticks = ticks.filter { it.itemId !in emptied },
            nextId = nextId,
        ).bumped(touched - emptied)
    }

    /**
     * "Need it" on an in-stock ingredient: the recipe lines left out on review go
     * on the list as contributions of their own use, created now, so a tick from
     * before never covers them. Null when there is nothing to add.
     */
    fun addToUse(
        useId: Long,
        adds: List<UseLineAdd>,
    ): ShoppingList? {
        if (useId !in uses || adds.isEmpty()) return null
        val rev = listRev + 1
        var l = this
        val touched = HashSet<Long>()
        for (a in adds) {
            val (l2, itemId) = l.ensureItem(a.target)
            val c = Contribution(l2.nextId, useId, a.recipeLineId, itemId, a.qty, a.noScale, rev, unitHint = a.unitHint)
            l = l2.copy(contributions = l2.contributions + c, nextId = l2.nextId + 1)
            touched += itemId
        }
        return l.bumped(touched)
    }

    /**
     * Takes back contributions not bought yet ([ids]; a covered one stays, so a
     * purchase is never undone by this): the Undo of Buy again, and "Have it" in
     * the At home check. A row left with nothing on it goes, as [removeUse] does.
     * Null when none of [ids] could go.
     */
    fun withdraw(ids: Set<Long>): ShoppingList? {
        val gone = contributions.filter { it.id in ids && it.coveredBy == null }
        if (gone.isEmpty()) return null
        val goneIds = gone.map { it.id }.toSet()
        val rest = contributions.filter { it.id !in goneIds }
        val affected = gone.map { it.itemId }.toSet()
        val empty = affected.filter { id -> rest.none { it.itemId == id } }.toSet()
        return copy(
            items = items - empty,
            contributions = rest,
            ticks = ticks.filter { it.itemId !in empty },
        ).bumped(affected - empty)
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
     * Sets the item's total to [newTotal] per dimension. An increase is a new
     * MANUAL contribution created at the new listRev, so a tick from before the
     * edit never covers it. A decrease comes off the existing contributions,
     * uncovered ones first (then covered ones, so a DONE item stays DONE), the
     * MANUAL use's before recipes', newest first; nothing goes below zero.
     */
    fun editQuantity(
        itemId: Long,
        newTotal: QtyBase,
        now: Long,
    ): ShoppingList {
        val current = total(itemId)
        val newTotal = sameCountUnit(current, newTotal)
        val delta =
            (current.parts.keys + newTotal.parts.keys)
                .associateWith { k -> (newTotal.parts[k] ?: Rational.ZERO) - (current.parts[k] ?: Rational.ZERO) }
                .filterValues { !it.isZero }
        if (delta.isEmpty()) return this
        val manualId = uses.values.firstOrNull { it.kind == UseKind.MANUAL }?.id
        var cs = contributions
        val emptied = HashSet<Long>()
        for ((k, d) in delta) {
            if (d > Rational.ZERO) continue
            var left = Rational.ZERO - d
            val order =
                cs.filter { it.itemId == itemId && (it.qty.parts[k] ?: Rational.ZERO) > Rational.ZERO }
                    .sortedWith(compareBy<Contribution>({ it.coveredBy != null }, { it.useId != manualId }).thenByDescending { it.id })
            for (c in order) {
                if (left.isZero) break
                val take = minOf(c.qty.parts.getValue(k), left)
                left -= take
                val qty = c.qty.minusClamped(QtyBase(mapOf(k to take)))
                if (qty.isPresence && c.coveredBy == null) emptied += c.id
                cs = cs.map { if (it.id == c.id) it.copy(qty = qty) else it }
            }
        }
        var l = copy(contributions = cs)
        val increase = QtyBase(delta.filterValues { it > Rational.ZERO })
        if (!increase.isPresence) {
            val (withUse, useId) = l.manualUse(now)
            val c = Contribution(withUse.nextId, useId, null, itemId, increase, noScale = false, createdListRev = withUse.listRev + 1)
            l = withUse.copy(contributions = withUse.contributions + c, nextId = withUse.nextId + 1)
        }
        // An uncovered contribution brought down to nothing goes, unless it is all the item has.
        if (emptied.isNotEmpty() && l.contributions.any { it.itemId == itemId && it.id !in emptied }) {
            l = l.copy(contributions = l.contributions.filter { it.id !in emptied })
        }
        return l.bumped(setOf(itemId))
    }

    fun moveAisle(
        itemId: Long,
        aisle: AisleCode,
    ): ShoppingList {
        val i = items[itemId] ?: return this
        return copy(items = items + (itemId to i.copy(aisle = aisle, position = nextPosition(aisle)))).bumped(setOf(itemId))
    }

    /** The units setting changed: every live item's shown amount did, so every live rev bumps. */
    fun unitsChanged(): ShoppingList = bumped(liveItems.map { it.id }.toSet())

    /**
     * "Swap for maple syrup": the item becomes [target]. When [target] is already
     * on the list, this item's contributions and ticks move to it (it keeps its
     * aisle and place) and this one is merged away, as in a catalogue re-key;
     * otherwise this item is renamed in place (moving to the target's aisle).
     * Amounts carry over as written. Null when nothing would change.
     */
    fun swapItem(
        itemId: Long,
        target: NewItem,
    ): ShoppingList? {
        val item = items[itemId]?.takeIf { it.deletedAt == null } ?: return null
        if (item.mergeKey == target.mergeKey) return null
        val existing = itemByKey(target.mergeKey)
        if (existing == null) {
            val position = if (target.aisle == item.aisle) item.position else nextPosition(target.aisle)
            val renamed =
                item.copy(
                    mergeKey = target.mergeKey,
                    catalogId = target.catalogId,
                    normName = target.normName,
                    displayName = target.displayName,
                    aisle = target.aisle,
                    position = position,
                )
            return copy(items = items + (itemId to renamed)).bumped(setOf(itemId))
        }
        val rev = maxOf(existing.rev, item.rev) + 1
        return copy(
            items = items - itemId + (existing.id to existing.copy(rev = rev - 1)),
            contributions = contributions.map { if (it.itemId == itemId) it.copy(itemId = existing.id) else it },
            ticks = ticks.map { if (it.itemId == itemId) it.copy(itemId = existing.id) else it },
            aliases = aliases + (itemId to existing.id),
        ).bumped(setOf(existing.id))
    }

    /** A new catalogue version: every live item's text may change, so all are bumped. */
    fun catalogueChanged(): ShoppingList = bumped(liveItems.map { it.id }.toSet())

    // ---- helpers ----------------------------------------------------------------

    /**
     * A bare number typed for an item counted in one unit means that unit:
     * "3" for "2 cloves garlic" is 3 cloves, not 3 garlic and no cloves.
     */
    private fun sameCountUnit(
        current: QtyBase,
        typed: QtyBase,
    ): QtyBase {
        val bare = BaseKey.ITEMS.encode()
        val n = typed.parts[bare] ?: return typed
        if (typed.parts.size != 1 || current.parts.containsKey(bare)) return typed
        val counts = current.parts.keys.filter { BaseKey.decode(it).dimension == Dimension.COUNT }
        return if (counts.size == 1) QtyBase(mapOf(counts.single() to n)) else typed
    }

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

enum class WatchTickResult { OK, NOOP, PARTIAL, STILL_DONE }

/** What [ShoppingList.removeUseKeeping] took off the list, for Undo. */
data class RemovedUse(
    val use: RecipeUse,
    /** Every row the use had contributions on, as they were: Undo re-adds the ones gone since. */
    val items: List<ListItem>,
    val contributions: List<Contribution>,
    /** Ticks that covered nothing else. */
    val ticks: List<Tick>,
)

/** What an incoming line needs to become an item. */
data class NewItem(
    val mergeKey: String,
    val catalogId: String?,
    val normName: String,
    val displayName: String,
    val aisle: AisleCode,
)
