package com.robert4lyf.hypershop.data

import androidx.room.withTransaction
import com.robert4lyf.hypershop.core.merge.Display
import com.robert4lyf.hypershop.core.merge.LineStatus
import com.robert4lyf.hypershop.core.merge.ShoppingList
import com.robert4lyf.hypershop.core.merge.UseKind
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.parse.QuantityInput
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine

/** One ingredient in the At home check, however many recipes want it. */
data class HomeRow(
    val mergeKey: String,
    val name: String,
    /** The recipes that want it ("Chilli, Soup"). */
    val from: List<String>,
    /** Set aside as at home for this shop (false: on the list, to buy). */
    val have: Boolean,
    /** A staple: usually at home (Settings › Ingredients), whatever this shop says. */
    val usual: Boolean,
    /** Y.19: what every recipe on the list needs together ("520 g"), so a cupboard with some in can be weighed against it. */
    val needed: String? = null,
    /** Y.19: what was said to be at home of it ("300 g"); only the rest is bought. */
    val onHand: String? = null,
    val likelyCupboard: Boolean = true,
    val remaining: String? = null,
    val bought: String? = null,
    val exactOnHand: QtyBase? = null,
)

/** Y.19: what "Have some" did with the amount typed. */
enum class HaveSome { SET, ENOUGH, NOT_AN_AMOUNT, DIFFERENT_MEASURE, ALREADY_ALLOCATED }

data class HomeSomeUndo(
    val before: ShoppingList,
    val decisions: List<LineDecisionEntity>,
    val afterRevision: Long,
    val epoch: Long,
)

data class HomeSomeChange(val result: HaveSome, val undo: HomeSomeUndo?)

/**
 * The At home check (docs/design/release-b.md): before shopping, the cupboard
 * ingredients the recipes on the list still need, one decision per ingredient.
 * It changes nothing new about what "at home" means: "Have it" is the review's
 * "in stock" for each of those recipe lines ([LineStatus.IN_STOCK], the line off
 * the list), and "Need it" is the List tab's own "Need it". Only lines not bought
 * yet move; a bought amount stays bought, and hand-added amounts are never
 * touched (they were asked for). Staples are not changed: "usually at home" stays
 * a separate, lasting choice.
 */
class HomeCheck(
    private val repo: ListRepository,
) {
    private val db = repo.db
    private val dao = repo.dao

    fun observe(): Flow<List<HomeRow>> = combine(repo.list, dao.observeStaples()) { l, _ -> view(l) }

    suspend fun view(l: ShoppingList): List<HomeRow> =
        db.withTransaction {
            val staples = repo.staples()
            val titles = titles(l)
            val need =
                open(l).groupBy { it.first }.mapNotNull { (itemId, cs) ->
                    val item = l.items[itemId] ?: return@mapNotNull null
                    val entry = repo.catalog.byId[item.catalogId ?: ""]

                    fun text(q: QtyBase) =
                        Display.amount(
                            q,
                            produce = item.aisle == AisleCode.PRODUCE,
                            gramsPerCup = entry?.gramsPerCup,
                            policy = entry?.displayPolicy,
                        )
                    HomeRow(
                        item.mergeKey,
                        item.displayName,
                        cs.mapNotNull { titles[it.second.useId] }.distinct(),
                        have = false,
                        usual = item.mergeKey in staples,
                        needed =
                            text(
                                l.contributionsOf(item.id).filter { l.uses[it.useId]?.kind == UseKind.RECIPE }
                                    .fold(QtyBase.PRESENCE) { q, c -> q + c.qty },
                            ),
                        bought = text(l.purchased(item.id)) ?: "0",
                        likelyCupboard = item.aisle in CUPBOARD || item.mergeKey in staples,
                        remaining = text(l.toBuy(item.id)) ?: "0",
                        onHand = item.onHand?.let { text(it) },
                        exactOnHand = item.onHand,
                    )
                }
            val have =
                repo.inStockOnList(l, stillShopping = true).groupBy { it.target.mergeKey }.map { (key, lines) ->
                    HomeRow(
                        key,
                        lines.first().target.displayName,
                        lines.map { it.recipeTitle }.distinct(),
                        have = true,
                        usual = key in staples,
                        likelyCupboard = lines.first().target.aisle in CUPBOARD || key in staples,
                    )
                }
            (need + have.filter { h -> need.none { it.mergeKey == h.mergeKey } }).sortedBy { it.name.lowercase() }
        }

    /**
     * Recipe lines on live rows not bought yet, by item: what "Have it" can take off.
     * Covered portions stay intact; Need it restores only the current requirement minus covered shares.
     */
    private fun open(l: ShoppingList): List<Pair<Long, com.robert4lyf.hypershop.core.merge.Contribution>> {
        return l.contributions
            .filter { c ->
                c.coveredBy == null && c.recipeLineId != null && l.items[c.itemId]?.deletedAt == null &&
                    l.uses[c.useId]?.let { it.kind == UseKind.RECIPE && it.archivedAt == null } == true
            }.map { it.itemId to it }
    }

    private suspend fun titles(l: ShoppingList): Map<Long, String> {
        val recipes = l.uses.values.mapNotNull { u -> u.recipeId?.let { u.id to it } }
        val byId = recipes.map { it.second }.distinct().chunked(500).flatMap { dao.recipes(it) }.associateBy { it.id }
        return recipes.associate { (useId, recipeId) -> useId to (byId[recipeId]?.title ?: "Recipe") }
    }

    /**
     * "Have it" for [keys], in one transaction: each one's unbought recipe lines
     * come off the list and are marked at home. The (use, line) pairs it moved, for
     * Undo ([need] with them).
     */
    suspend fun have(keys: Collection<String>): List<Pair<Long, Long>> =
        db.withTransaction {
            val old = repo.load()
            val wanted = keys.toSet()
            val moving = open(old).map { it.second }.filter { old.items[it.itemId]?.mergeKey in wanted }
            val next = old.withdraw(moving.map { it.id }.toSet()) ?: return@withTransaction emptyList()
            var changed = next
            for (id in moving.map { it.itemId }.distinct()) {
                val consumed = old.homeConsumed(id).takeUnless { it.isPresence }
                changed.setOnHand(id, consumed)?.let { changed = it }
            }
            repo.persist(old, changed)
            val lines = moving.map { it.useId to it.recipeLineId!! }.distinct()
            dao.putDecisions(lines.map { (u, line) -> LineDecisionEntity(u, line, LineStatus.IN_STOCK.name) })
            lines
        }

    /**
     * Y.19 "Have some": [text] ("300 g") of [key] is at home, out of everything the recipes need together; only
     * the rest is bought. As much as is needed, or more, is "Have it". The amount must be in the list's own measure
     * (a weight for a weighed item): a count against grams cannot be taken off.
     */
    suspend fun haveSome(
        key: String,
        text: String,
    ): HaveSome {
        val have = QuantityInput.parse(text) ?: return HaveSome.NOT_AN_AMOUNT
        return db.withTransaction {
            val old = repo.load()
            val item = old.itemByKey(key) ?: return@withTransaction HaveSome.DIFFERENT_MEASURE
            val need = open(old).filter { it.first == item.id }.fold(QtyBase.PRESENCE) { q, c -> q + c.second.qty }
            if (have.keys.any { it !in need.keys } || need.isPresence) return@withTransaction HaveSome.DIFFERENT_MEASURE
            val consumed = old.homeConsumed(item.id)
            if (consumed.parts.any { (k, v) -> (have.parts[k] ?: com.robert4lyf.hypershop.core.model.Rational.ZERO) < v }) {
                return@withTransaction HaveSome.ALREADY_ALLOCATED
            }
            val homeRequired = need + consumed
            if (homeRequired.minusClamped(have).isPresence) {
                // Same transaction as the decision: no watch tick or servings edit can slip between them.
                have(listOf(key))
                HaveSome.ENOUGH
            } else {
                old.setOnHand(item.id, have)?.let { repo.persist(old, it) }
                HaveSome.SET
            }
        }
    }

    /** Capture exactly this change for Undo, including an earlier partial amount. */
    suspend fun changeSome(
        key: String,
        text: String,
    ): HomeSomeChange =
        db.withTransaction {
            val before = repo.load()
            val decisions = dao.allDecisions()
            val result = haveSome(key, text)
            val after = repo.load()
            HomeSomeChange(result, HomeSomeUndo(before, decisions, after.listRev, dao.meta()!!.listEpoch).takeIf { before != after })
        }

    /** Refuse a stale Undo rather than overwrite a later phone/watch action or reuse ids. */
    suspend fun undoSome(undo: HomeSomeUndo): Boolean =
        db.withTransaction {
            val current = repo.load()
            if (current.listRev != undo.afterRevision || dao.meta()!!.listEpoch != undo.epoch) return@withTransaction false
            val restored =
                undo.before.copy(
                    listRev = current.listRev + 1,
                    nextId = maxOf(current.nextId, undo.before.nextId),
                    items = undo.before.items.mapValues { (id, item) -> item.copy(rev = maxOf(item.rev, current.items[id]?.rev ?: 0) + 1) },
                )
            repo.persist(current, restored)
            dao.clearDecisions()
            dao.putDecisions(undo.decisions)
            true
        }

    /** Takes back "Have some": the whole of [key] to buy again. */
    suspend fun clearSome(key: String) =
        db.withTransaction {
            val old = repo.load()
            val item = old.itemByKey(key) ?: return@withTransaction
            old.setOnHand(item.id, old.homeConsumed(item.id).takeUnless { it.isPresence })?.let { repo.persist(old, it) }
        }

    /** Undo of [need]: exactly those lines at home again. */
    suspend fun haveLines(lines: Collection<Pair<Long, Long>>): List<Pair<Long, Long>> =
        db.withTransaction {
            val old = repo.load()
            val wanted = lines.toSet()
            val moving = open(old).map { it.second }.filter { (it.useId to it.recipeLineId) in wanted }
            val next = old.withdraw(moving.map { it.id }.toSet()) ?: return@withTransaction emptyList()
            repo.persist(old, next)
            val moved = moving.map { it.useId to it.recipeLineId!! }.distinct()
            dao.putDecisions(moved.map { (u, line) -> LineDecisionEntity(u, line, LineStatus.IN_STOCK.name) })
            moved
        }

    /** "Need it" for [keys]: their at-home lines back on the list, at the recipes' servings now. For Undo, the lines. */
    suspend fun need(keys: Collection<String>): List<Pair<Long, Long>> {
        val wanted = keys.toSet()
        return repo.needLines { it.target.mergeKey in wanted }
    }

    /** Undo of [have]: exactly those lines on the list again. */
    suspend fun needLines(lines: Collection<Pair<Long, Long>>): List<Pair<Long, Long>> {
        val wanted = lines.toSet()
        return repo.needLines { (it.useId to it.recipeLineId) in wanted }
    }

    companion object {
        /** Default compact filter only; fresh, chilled and frozen food remains available. */
        val CUPBOARD =
            setOf(
                AisleCode.BAKING,
                AisleCode.OILS_VINEGARS,
                AisleCode.SPICES_CONDIMENTS,
                AisleCode.PASTA_RICE_GRAINS,
                AisleCode.TINS_JARS,
                AisleCode.NUTS_DRIED_FRUIT,
                AisleCode.WORLD_FOODS,
            )
    }
}
