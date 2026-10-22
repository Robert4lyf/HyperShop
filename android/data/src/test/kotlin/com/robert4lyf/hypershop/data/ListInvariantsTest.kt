package com.robert4lyf.hypershop.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.merge.ShoppingList
import com.robert4lyf.hypershop.core.merge.UseKind
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import kotlin.random.Random

/**
 * Release A: random runs of what a week of shopping does to a list — recipes
 * sharing ingredients, ticks and unticks, finished trips and their Undo,
 * servings up and down, recipes removed and restored, items typed in — with
 * the list's rules checked after every step. Seeded, so a failure replays.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ListInvariantsTest {
    @get:Rule val r = DbRule()

    private val recipes =
        listOf(
            listOf("2 onions", "1 carrot", "500g beef mince"),
            listOf("1 onion", "200g red lentils", "1 carrot"),
            listOf("3 onions", "2 garlic cloves"),
            listOf("200 ml milk", "6 eggs"),
        )

    private fun check(
        l: ShoppingList,
        step: String,
    ) {
        fun bad(what: String): Nothing = fail("after $step: $what") as Nothing
        val ticks = l.ticks.map { it.id }.toSet()
        for (c in l.contributions) {
            if (c.itemId !in l.items) bad("contribution ${c.id} on a missing item")
            if (c.useId !in l.uses) bad("contribution ${c.id} of a missing use")
            if (c.coveredBy != null && c.coveredBy !in ticks) bad("contribution ${c.id} covered by a missing tick")
        }
        // A row on the list is there because something needs or needed it.
        for (i in l.liveItems) if (l.contributionsOf(i.id).isEmpty()) bad("live row ${i.displayName} with nothing on it")
        val keys = l.liveItems.map { it.mergeKey }
        if (keys.size != keys.toSet().size) bad("two live rows share a key")
        // Each recipe's count and the meals headline describe the same rows as the list.
        val openRows = l.liveItems.filter { i -> l.contributionsOf(i.id).any { it.coveredBy == null } }.map { it.id }.toSet()
        for (u in l.uses.values) {
            val own = l.contributionsOfUse(u.id).filter { it.coveredBy == null && it.itemId in openRows }.map { it.itemId }.toSet()
            if (l.progress(u.id).toBuy != own.size) bad("use ${u.id}: progress ${l.progress(u.id)} but ${own.size} rows still needed")
        }
        val recipeUses = l.uses.values.filter { it.kind == UseKind.RECIPE }.map { it.id }
        val headline = l.rowsStillNeeded(recipeUses)
        if (!openRows.containsAll(headline)) bad("the meals headline counts a row the list does not need")
        if (headline.size > recipeUses.sumOf { l.progress(it).toBuy }) bad("the headline counts more than the recipes need")
        // A done row is all bought; a row with something open is not done.
        for (i in l.liveItems) {
            val cs = l.contributionsOf(i.id)
            if (l.isDone(i.id) != cs.all { it.coveredBy != null }) bad("${i.displayName}: done does not match its contributions")
        }
    }

    private fun run(seed: Int) =
        runBlocking {
            val rnd = Random(seed)
            val stamps = ArrayList<Long>()
            val removed = ArrayList<UseRemoval>()
            val trail = ArrayList<String>()
            for (n in 0 until 120) {
                val l = r.repo.snapshot()
                val live = l.liveItems
                val recipeUses = l.uses.values.filter { it.kind == UseKind.RECIPE && it.archivedAt == null }
                val pick = rnd.nextInt(10)
                trail += "step $n: op $pick"
                val step =
                    try {
                        when (pick) {
                            0, 1 -> {
                                val i = rnd.nextInt(recipes.size)
                                r.import(*recipes[i].toTypedArray(), url = "https://example.com/$i-$n")
                                "import recipe $i"
                            }
                            2, 3 ->
                                live.randomOrNull(rnd)?.let {
                                    r.repo.toggle(it.id)
                                    "toggle ${it.displayName}"
                                } ?: "nothing to toggle"
                            4 ->
                                recipeUses.randomOrNull(rnd)?.let { u ->
                                    val s = 1 + rnd.nextInt(10)
                                    r.repo.rescaleUse(u.id, s)
                                    "rescale use ${u.id} to $s"
                                } ?: "nothing to rescale"
                            5 -> {
                                r.now += 1000
                                stamps += r.repo.finishShopping()
                                "finish"
                            }
                            6 ->
                                stamps.removeLastOrNull()?.let {
                                    r.repo.undoDelete(it)
                                    "undo finish"
                                } ?: "nothing to undo"
                            7 ->
                                recipeUses.randomOrNull(rnd)?.let { u ->
                                    r.repo.removeUse(u.id)?.let { removed += it }
                                    "remove use ${u.id}"
                                } ?: "nothing to remove"
                            8 ->
                                removed.removeLastOrNull()?.let {
                                    r.repo.restoreUse(it)
                                    "restore a use"
                                } ?: "nothing to restore"
                            else -> {
                                val name = listOf("onion", "bread", "milk", "bananas").random(rnd)
                                r.repo.manualAdd(name, QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), null)
                                "add $name by hand"
                            }
                        }
                    } catch (e: Exception) {
                        fail("seed $seed, step $n threw $e; trail:\n${trail.takeLast(12).joinToString("\n")}") as Nothing
                    }
                trail[trail.lastIndex] = "step $n: $step"
                check(r.repo.snapshot(), "step $n ($step), seed $seed")
            }
        }

    @Test
    fun aWeekOfShoppingKeepsTheListsRules() {
        for (seed in 1..12) {
            r.close()
            r.open()
            run(seed)
        }
        assertTrue(true)
    }
}
