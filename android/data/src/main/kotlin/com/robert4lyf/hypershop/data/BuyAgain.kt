package com.robert4lyf.hypershop.data

import androidx.room.withTransaction
import com.robert4lyf.hypershop.core.merge.ShoppingList
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.QtyUnit
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** One Buy again chip. */
data class Regular(
    val mergeKey: String,
    val name: String,
    /** What was added by hand last time (presence when it was just "milk"). */
    val qty: QtyBase,
    val unitHint: QtyUnit?,
    val pinned: Boolean,
    /** Of the last [BuyAgain.LOOKBACK] trips, how many it was bought by hand on. */
    val trips: Int,
    /** A live row with its key is on the list: adding it would double it, so it is shown as there instead. */
    val onList: Boolean,
)

/** Buy again's view: the suggestions, and what "Repeat last shop" would add. */
data class BuyAgainView(
    val regulars: List<Regular> = emptyList(),
    /** The last trip's hand-added items not on the list now. */
    val lastShop: List<Regular> = emptyList(),
    /** When the last trip finished; null with no history. */
    val lastShopAt: Long? = null,
    val hasHistory: Boolean = false,
)

/**
 * Buy again (docs/design/release-b.md): household items from finished trips.
 * Transparent rules, no guessing: an item counts once per trip however many
 * recipes wanted it, and only trips where some of it was added *by hand* count,
 * so the onions four recipes needed are not a regular. Suggested when pinned, or
 * bought by hand on [MIN_TRIPS] of the last [LOOKBACK] trips; a hidden one never.
 * Everything here stays on the phone.
 */
class BuyAgain(
    private val repo: ListRepository,
) {
    private val db = repo.db
    private val dao = repo.dao

    fun observe(): Flow<BuyAgainView> = combine(dao.observeTripCount(), dao.observeRegulars(), repo.list) { _, _, l -> view(l) }

    /** The view, given the list as already read ([observe] has it from the repository's flow). */
    suspend fun view(list: ShoppingList? = null): BuyAgainView =
        db.withTransaction {
            val trips = dao.trips()
            if (trips.isEmpty() && dao.regulars().none { it.pinned }) return@withTransaction BuyAgainView()
            val recent = trips.take(LOOKBACK)
            val items = if (recent.isEmpty()) emptyList() else dao.tripItems(recent.map { it.id })
            val order = recent.withIndex().associate { (i, t) -> t.id to i }
            val byHand = items.filter { it.byHand }.sortedBy { order[it.tripId] }
            val counts = byHand.groupingBy { it.mergeKey }.eachCount()
            // The latest hand-added purchase of each key, for its name and amount.
            val latest = byHand.distinctBy { it.mergeKey }.associateBy { it.mergeKey }
            val prefs = dao.regulars().associateBy { it.mergeKey }
            val l = list ?: repo.load()
            val live = l.liveItems.map { it.mergeKey }.toSet()

            fun regular(
                key: String,
                name: String,
                row: TripItemEntity?,
            ) = Regular(
                mergeKey = key,
                name = name,
                qty = row?.qty?.let { decode(it) } ?: QtyBase.PRESENCE,
                unitHint = row?.unitHint?.let { QtyUnit.fromSerial(it) },
                pinned = prefs[key]?.pinned == true,
                trips = counts[key] ?: 0,
                onList = key in live,
            )
            val suggested =
                (
                    counts.filter {
                            (k, n) ->
                        n >= MIN_TRIPS && prefs[k] == null
                    }.keys + prefs.values.filter { it.pinned }.map { it.mergeKey }
                )
                    .distinct()
                    .map { k -> regular(k, latest[k]?.displayName ?: prefs.getValue(k).displayName, latest[k]) }
                    .sortedWith(compareByDescending<Regular> { it.pinned }.thenByDescending { it.trips }.thenBy { it.name })
            val last = trips.firstOrNull()
            val lastShop =
                last?.let { t ->
                    items.filter { it.tripId == t.id && it.byHand && it.mergeKey !in live }.map { regular(it.mergeKey, it.displayName, it) }
                }.orEmpty()
            BuyAgainView(suggested, lastShop, last?.finishedAt, trips.isNotEmpty())
        }

    private fun decode(qty: String): QtyBase? = runCatching { repo.json.decodeFromString(QtyBase.serializer(), qty) }.getOrNull()

    /**
     * Adds [regulars] by hand, in one write, each at its last amount; one already on
     * the list is left as it is (never doubled). The contributions added, for Undo
     * ([undo]); empty when every one was there already.
     */
    suspend fun add(regulars: List<Regular>): Set<Long> =
        repo.write { l0 ->
            var l = l0
            val added = HashSet<Long>()
            val overrides = repo.overrides()
            for (r in regulars) {
                val target = repo.merger.target(r.name, null, overrides)
                if (l.itemByKey(target.mergeKey) != null || l.itemByKey(r.mergeKey) != null) continue
                val (next, _) = l.manualAdd(target, r.qty, r.unitHint, repo.clock())
                added += next.contributions.last().id
                l = next
            }
            if (added.isEmpty()) null else l to added.toSet()
        }.orEmpty()

    /** Undo of [add]: what it added and nobody has bought since. */
    suspend fun undo(contributions: Set<Long>) = repo.write { l -> l.withdraw(contributions)?.let { it to Unit } }

    /** Pin: always offered. */
    suspend fun pin(r: Regular) = dao.putRegular(RegularEntity(r.mergeKey, r.name, pinned = true))

    /** "Not a regular": never suggested again (until pinned, or the history is cleared). */
    suspend fun hide(r: Regular) = dao.putRegular(RegularEntity(r.mergeKey, r.name, pinned = false))

    /** Back to the plain rules. */
    suspend fun reset(r: Regular) = dao.deleteRegular(r.mergeKey)

    /** Settings › Clear shopping history: every trip, and what was hidden; pins stay (they were chosen). */
    suspend fun clearHistory() =
        db.withTransaction {
            dao.clearTrips()
            for (r in dao.regulars()) if (!r.pinned) dao.deleteRegular(r.mergeKey)
        }

    companion object {
        /** Trips looked at for suggestions: about two months of weekly shops. */
        const val LOOKBACK = 8

        /** Bought by hand on this many of them, it is a regular. */
        const val MIN_TRIPS = 2
    }
}
