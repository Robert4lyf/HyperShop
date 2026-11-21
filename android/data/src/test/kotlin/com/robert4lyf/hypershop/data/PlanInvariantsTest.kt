package com.robert4lyf.hypershop.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.merge.ApplyResult
import com.robert4lyf.hypershop.core.merge.ShoppingList
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * Release B: random runs of a few weeks of planning and shopping — meals planned,
 * removed and restored, saved recipes put on the list with Add to list (sometimes
 * twice) and taken back, recipes shared onto the list through the review, servings
 * changed, ticks, finished trips and their Undo, a cleared list and Buy again with
 * their Undo — with the plan's and the list's rules checked after every step.
 * Seeded, so a failure replays.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlanInvariantsTest {
    @get:Rule val r = DbRule()

    private suspend fun check(step: String) {
        fun bad(what: String): Nothing = fail("after $step: $what") as Nothing
        val l: ShoppingList = r.repo.snapshot()
        val meals = r.db.dao().allPlannedMeals()
        // A meal's link points at a use that is there, and no two meals share one: one meal, one shopping.
        for (m in meals) if (m.useId != null && m.useId !in l.uses) bad("meal ${m.id} linked to a missing use ${m.useId}")
        val linked = meals.mapNotNull { it.useId }
        if (linked.size != linked.toSet().size) bad("two meals share one use")
        // The list's own rules.
        for (i in l.liveItems) if (l.contributionsOf(i.id).isEmpty()) bad("live row ${i.displayName} with nothing on it")
        val keys = l.liveItems.map { it.mergeKey }
        if (keys.size != keys.toSet().size) bad("two live rows share a key")
        val ticks = l.ticks.map { it.id }.toSet()
        for (c in l.contributions) {
            if (c.itemId !in l.items || c.useId !in l.uses) bad("contribution ${c.id} points at nothing")
            if (c.coveredBy != null && c.coveredBy !in ticks) bad("contribution ${c.id} covered by a missing tick")
        }
        // A recipe line is never still needed twice on one row: no requirement doubled. (A bought share and
        // the extra more servings asked for since can sit side by side; two open shares cannot.)
        val open = l.contributions.filter { it.recipeLineId != null && it.coveredBy == null }
        val perLine = open.groupingBy { Triple(it.useId, it.recipeLineId, it.itemId) }.eachCount()
        perLine.entries.firstOrNull { it.value > 1 }?.let { bad("use ${it.key.first} needs line ${it.key.second} twice on one row") }
    }

    private fun run(seed: Int) =
        runBlocking {
            val rnd = Random(seed)
            val planner = Planner(r.repo)
            val buy = BuyAgain(r.repo)
            val keys =
                listOf(
                    r.save("Chilli", "2 onions", "200 g basmati rice", "1 tsp ground cumin"),
                    r.save("Dal", "1 onion", "100 g basmati rice", "200 g red lentils"),
                    r.save("Soup", "2 carrots", "1 onion", "1 tbsp olive oil"),
                    r.save("Omelette", "6 eggs", "200 ml milk"),
                )
            val undo = ArrayList<suspend () -> Unit>()
            for (step in 0 until 120) {
                r.now += 1_000
                val week = planner.currentWeek() + rnd.nextInt(-1, 2)
                val op = rnd.nextInt(14)
                val what =
                    try {
                        when (op) {
                            0, 1 -> {
                                val id = planner.plan(week, keys.random(rnd))
                                // Undo of a plan: the meal goes again if it has not gone on the list since.
                                undo += { if (r.db.dao().plannedMeal(id)?.useId == null) planner.unplan(id) }
                                "plan"
                            }
                            2, 3 -> {
                                // Step Z "Add to list" on a saved recipe, and its Undo (only what is still unbought).
                                (planner.addToList(keys.random(rnd)) as? ApplyResult.Applied)?.let { a ->
                                    undo += { planner.takeBack(a.useId) }
                                }
                                "add to list"
                            }
                            4 -> {
                                planner.week(week).randomOrNull(rnd)?.let {
                                        m ->
                                    planner.unplan(m.id)?.let { rm -> undo += { planner.restore(rm) } }
                                }
                                "remove meal"
                            }
                            5 -> {
                                planner.week(week).randomOrNull(rnd)?.let { m -> planner.setServings(m.id, rnd.nextInt(1, 9)) }
                                "servings"
                            }
                            6 -> {
                                // The list's servings on a recipe's group: its meal's and the list's amounts together.
                                r.repo.snapshot().uses.values.filter { it.recipeId != null }.randomOrNull(rnd)?.let {
                                    planner.setUseServings(it.id, rnd.nextInt(1, 9))
                                }
                                "use servings"
                            }
                            7, 8 -> {
                                r.repo.snapshot().liveItems.randomOrNull(rnd)?.let { r.repo.toggle(it.id) }
                                "tick"
                            }
                            9 -> {
                                r.now += 3L * 24 * 3600 * 1000
                                val f = r.repo.finish()
                                undo += { r.repo.undoDelete(f.stamp) }
                                "finish"
                            }
                            10 -> {
                                val stamp = r.repo.clearAll()
                                undo += { r.repo.undoDelete(stamp) }
                                "clear"
                            }
                            11 -> {
                                r.repo.manualAdd(
                                    listOf("milk", "bread", "onion").random(rnd),
                                    QtyBase.of(Rational.ONE, null, UnitLocale.METRIC),
                                    null,
                                )
                                buy.add(buy.view().regulars).let { added -> undo += { buy.undo(added) } }
                                "buy again"
                            }
                            12 -> {
                                // A share of the same page through the review: a meal planned and pending is that meal.
                                val key = keys.random(rnd)
                                val (src, lines) = r.repo.librarySource(r.db.dao().recipesByKey(key).maxOf { it.id })!!
                                val stored =
                                    r.repo.storeRecipe(
                                        key, src.title, src.servingsOriginal, UnitLocale.METRIC, "WEB", ParsedBy.RULES, null, false,
                                        lines, key,
                                    )
                                (r.repo.apply(r.repo.preview(stored, null)) as? ApplyResult.Applied)?.let { a ->
                                    undo += { r.repo.undoImport(a.useId) }
                                }
                                "share"
                            }
                            else -> {
                                undo.removeLastOrNull()?.invoke()
                                "undo"
                            }
                        }
                    } catch (e: Exception) {
                        throw AssertionError("seed $seed step $step (op $op) threw", e)
                    }
                check("seed $seed step $step ($what)")
            }
        }

    @Test
    fun randomWeeksKeepThePlanAndTheListConsistent() {
        for (seed in 1..8) {
            r.close()
            r.open()
            run(seed)
        }
    }
}
