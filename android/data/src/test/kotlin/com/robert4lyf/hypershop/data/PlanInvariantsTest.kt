package com.robert4lyf.hypershop.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.merge.ShoppingList
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
 * repeated, removed and restored, weeks put on the list (sometimes twice), servings
 * changed, ticks, finished trips and their Undo, a cleared list, Buy again and the
 * At home check with their Undo — with the plan's and the list's rules checked
 * after every step. Seeded, so a failure replays.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlanInvariantsTest {
    @get:Rule val r = DbRule()

    private suspend fun check(
        planner: Planner,
        step: String,
    ) {
        fun bad(what: String): Nothing = fail("after $step: $what") as Nothing
        val l: ShoppingList = r.repo.snapshot()
        val meals = r.db.dao().allPlannedMeals()
        // A meal's link points at a use that is there, and no two meals share one: one meal, one shopping.
        for (m in meals) if (m.useId != null && m.useId !in l.uses) bad("meal ${m.id} linked to a missing use ${m.useId}")
        val linked = meals.mapNotNull { it.useId }
        if (linked.size != linked.toSet().size) bad("two meals share one use")
        // A week's add is idempotent: nothing waiting means a second add does nothing.
        for (w in meals.map { it.weekStart }.distinct()) {
            val week = com.robert4lyf.hypershop.core.plan.Week(w)
            val waiting = planner.week(week).count { it.shopping.pending }
            if (waiting == 0 && planner.previewWeek(week).meals != 0) bad("week $w previews meals to add with none waiting")
        }
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
            val home = HomeCheck(r.repo)
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
                                undo += { planner.unplanNew(listOf(id)) }
                                "plan"
                            }
                            2, 3 -> {
                                planner.addWeekToList(week)?.let { a -> undo += { planner.undoAddWeek(a) } }
                                "add week"
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
                                planner.previousPlannedWeek(week)?.let {
                                        from ->
                                    planner.repeatWeek(from, week).let { ids -> undo += { planner.unplanNew(ids) } }
                                }
                                "repeat"
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
                                val rows = home.view(r.repo.snapshot())
                                val pick = rows.filter { !it.have }.shuffled(rnd).take(2).map { it.mergeKey }
                                home.have(pick).let { lines -> undo += { home.needLines(lines) } }
                                rows.filter { it.have }.randomOrNull(rnd)?.let { row -> home.need(listOf(row.mergeKey)) }
                                "at home"
                            }
                            else -> {
                                undo.removeLastOrNull()?.invoke()
                                "undo"
                            }
                        }
                    } catch (e: Exception) {
                        throw AssertionError("seed $seed step $step (op $op) threw", e)
                    }
                check(planner, "seed $seed step $step ($what)")
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
