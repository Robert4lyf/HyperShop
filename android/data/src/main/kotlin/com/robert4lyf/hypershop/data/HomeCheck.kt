package com.robert4lyf.hypershop.data

import androidx.room.withTransaction
import com.robert4lyf.hypershop.core.merge.Display
import com.robert4lyf.hypershop.core.merge.LineStatus
import com.robert4lyf.hypershop.core.merge.ShoppingList
import com.robert4lyf.hypershop.core.merge.UseKind
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.RuleParser
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
)

/** Y.19: what "Have some" did with the amount typed. */
enum class HaveSome { SET, ENOUGH, NOT_AN_AMOUNT, DIFFERENT_MEASURE }

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
                    if (item.aisle !in CUPBOARD && item.mergeKey !in staples) return@mapNotNull null
                    val entry = repo.catalog.byId[item.catalogId ?: ""]

                    fun text(q: com.robert4lyf.hypershop.core.model.QtyBase) =
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
                        needed = text(l.uncovered(item.id)),
                        onHand = item.onHand?.let { text(it) },
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
                    )
                }
            (need + have.filter { h -> need.none { it.mergeKey == h.mergeKey } }).sortedBy { it.name.lowercase() }
        }

    /**
     * Recipe lines on live rows not bought yet, by item: what "Have it" can take off.
     * A line part of which is bought is not among them: it stays on the list as it
     * is (its amount can be edited), since setting aside the rest would leave a line
     * both bought and "at home" that Need it could not put back at the right amount.
     */
    private fun open(l: ShoppingList): List<Pair<Long, com.robert4lyf.hypershop.core.merge.Contribution>> {
        val partly = l.contributions.filter { it.coveredBy != null && it.recipeLineId != null }.map { it.useId to it.recipeLineId }.toSet()
        return l.contributions
            .filter { c ->
                c.coveredBy == null && c.recipeLineId != null && l.items[c.itemId]?.deletedAt == null &&
                    (c.useId to c.recipeLineId) !in partly &&
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
            repo.persist(old, next)
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
        val parsed = RuleParser().parse("${text.trim()} x")
        if (parsed.quantity == null) return HaveSome.NOT_AN_AMOUNT
        val have = parsed.shopBase(UnitLocale.METRIC)
        val enough =
            db.withTransaction {
                val old = repo.load()
                val item = old.itemByKey(key) ?: return@withTransaction null
                val need = old.uncovered(item.id)
                if (have.keys.none { it in need.keys }) return@withTransaction null
                if (need.minusClamped(have).isPresence) return@withTransaction true
                old.setOnHand(item.id, have)?.let { repo.persist(old, it) }
                false
            } ?: return HaveSome.DIFFERENT_MEASURE
        if (!enough) return HaveSome.SET
        have(listOf(key))
        return HaveSome.ENOUGH
    }

    /** Takes back "Have some": the whole of [key] to buy again. */
    suspend fun clearSome(key: String) =
        db.withTransaction {
            val old = repo.load()
            val item = old.itemByKey(key) ?: return@withTransaction
            old.setOnHand(item.id, null)?.let { repo.persist(old, it) }
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
        /** Where the things you may well have in already live. Fresh food is bought, not checked for. */
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
