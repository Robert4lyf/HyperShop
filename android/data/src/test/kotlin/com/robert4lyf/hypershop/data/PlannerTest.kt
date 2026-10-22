package com.robert4lyf.hypershop.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.merge.ApplyResult
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.plan.Week
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private val planParser = com.robert4lyf.hypershop.core.parse.RuleParser()

/** A saved recipe, in "Your recipes" and not on the list. Its library key (the URL). */
internal suspend fun DbRule.save(
    title: String,
    vararg lines: String,
    url: String = "https://example.com/${title.lowercase().replace(' ', '-')}",
    servings: Int? = 4,
): String {
    val stored =
        repo.storeRecipe(
            url, title, servings, UnitLocale.METRIC, "WEB", ParsedBy.RULES, null, false,
            lines.map {
                planParser.parse(it)
            },
        )
    repo.saveToLibrary(stored.recipe.id, null)
    return url
}

/** Release B phase 1: the meal plan, and how it puts meals on the list and stays in step with it. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class PlannerTest {
    @get:Rule val r = DbRule()

    private val planner by lazy { Planner(r.repo) }
    private val week get() = planner.currentWeek()

    private suspend fun onions(): Rational? = r.repo.snapshot().let { l -> l.itemByKey("cat:onion")?.let { l.total(it.id)[BaseKey.ITEMS] } }

    private suspend fun meals(w: Week = week) = planner.week(w)

    /** The list's tables alone: what a plan-only change must leave as it was. */
    private fun listDump() = r.dump().lines().filterNot { it.startsWith("PlannedMeal") }.joinToString("\n")

    @Test
    fun aRecipeImportedOntoTheListIsOneOfThisWeeksMeals() =
        runBlocking {
            val a = r.import("2 onions")
            val m = meals().single()
            assertEquals(a.useId, m.useId)
            assertEquals(MealShopping.TO_BUY, m.shopping)
            assertEquals(4, m.servings)
            // Undo of the import takes the meal with it: it was never planned otherwise.
            r.repo.undoImport(a.useId)
            assertTrue(meals().isEmpty())
        }

    @Test
    fun twoMealsSharingOnionsAreOneOnionRowAndASecondTapAddsNothing() =
        runBlocking {
            val chilli = r.save("Chilli", "2 onions", "500 g beef mince")
            val bolognese = r.save("Bolognese", "1 onion", "400 g tinned tomatoes")
            planner.plan(week, chilli)
            planner.plan(week, bolognese)
            assertTrue(meals().all { it.shopping == MealShopping.NOT_ON_LIST })
            val p = planner.previewWeek(week)
            assertEquals(WeekPreview(meals = 2, newItems = 3, joined = 0, atHome = 0), p)
            // Nothing written by the preview.
            assertTrue(r.repo.snapshot().liveItems.isEmpty())
            val added = planner.addWeekToList(week)!!
            assertEquals(2, added.meals.size)
            assertEquals(1, r.repo.snapshot().liveItems.count { it.mergeKey == "cat:onion" })
            assertEquals(Rational.of(3), onions())
            assertTrue(meals().all { it.shopping == MealShopping.TO_BUY })
            val before = r.dump()
            assertNull(planner.addWeekToList(week))
            assertEquals(before, r.dump())
        }

    @Test
    fun rapidRepeatedAddsFromTwoCoroutinesAddTheWeekOnce() =
        runBlocking {
            planner.plan(week, r.save("Chilli", "2 onions"))
            planner.plan(week, r.save("Soup", "1 onion", "1 carrot"))
            val results = (1..4).map { async { planner.addWeekToList(week) } }.awaitAll()
            assertEquals(1, results.count { it != null })
            assertEquals(Rational.of(3), onions())
            assertEquals(2, r.repo.snapshot().uses.size)
        }

    @Test
    fun anOnionAlreadyOnTheListJoinsAndStaplesStayAtHome() =
        runBlocking {
            r.repo.manualAdd("onion", com.robert4lyf.hypershop.core.model.QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), null)
            planner.plan(week, r.save("Chilli", "2 onions", "Salt, to taste"))
            assertEquals(WeekPreview(meals = 1, newItems = 0, joined = 1, atHome = 1), planner.previewWeek(week))
            planner.addWeekToList(week)
            // The hand-added onion is still there, with the chilli's two beside it.
            assertEquals(Rational.of(3), onions())
            assertNull(r.repo.snapshot().itemByKey("cat:salt"))
        }

    @Test
    fun moreServingsChangeOnlyThatMealsShareAndKeepWhatWasBought() =
        runBlocking {
            planner.plan(week, r.save("Chilli", "2 onions"))
            planner.plan(week, r.save("Soup", "2 onions"))
            planner.addWeekToList(week)
            val onion = r.repo.snapshot().itemByKey("cat:onion")!!.id
            r.repo.tick(onion)
            val chilli = meals().first()
            assertTrue(planner.setServings(chilli.id, 8))
            val l = r.repo.snapshot()
            // 4 bought (2 + 2), the chilli's 2 more still needed.
            assertEquals(Rational.of(6), onions())
            assertEquals(Rational.of(4), l.covered(onion)[BaseKey.ITEMS])
            assertEquals(8, meals().first().servings)
            assertEquals(MealShopping.BOUGHT, meals()[1].shopping)
        }

    @Test
    fun servingsOfAMealNotOnTheListChangeOnlyThePlan() =
        runBlocking {
            planner.plan(week, r.save("Chilli", "2 onions"))
            val m = meals().single()
            assertTrue(planner.setServings(m.id, 2))
            planner.addWeekToList(week)
            assertEquals(Rational.of(1), onions())
        }

    @Test
    fun removingAMealBeforeShoppingTakesItsItemsOffAndUndoPutsThemBack() =
        runBlocking {
            planner.plan(week, r.save("Chilli", "2 onions", "1 courgette"))
            planner.plan(week, r.save("Soup", "1 onion"))
            planner.addWeekToList(week)
            val before = r.dump()
            val chilli = meals().first()
            val removal = planner.unplan(chilli.id)!!
            assertNotNull(removal.use)
            assertEquals(Rational.of(1), onions())
            assertNull(r.repo.snapshot().itemByKey("cat:courgette"))
            assertEquals(1, meals().size)
            planner.restore(removal)
            assertEquals(before, r.dump())
            assertEquals(chilli.copy(), meals().first())
        }

    @Test
    fun removingAMealPartlyBoughtKeepsEveryPurchaseAndUndoPutsTheRestBack() =
        runBlocking {
            planner.plan(week, r.save("Chilli", "2 onions", "1 courgette"))
            planner.plan(week, r.save("Soup", "1 onion"))
            planner.addWeekToList(week)
            r.repo.tick(r.repo.snapshot().itemByKey("cat:onion")!!.id)
            val removal = planner.unplan(meals().first().id)!!
            val l = r.repo.snapshot()
            val onion = l.itemByKey("cat:onion")!!.id
            // Every onion bought stays bought, the chilli's two included (Review B #1); its courgette, not bought, goes.
            assertTrue(l.isDone(onion))
            assertEquals(Rational.of(3), l.covered(onion)[BaseKey.ITEMS])
            assertEquals(1, l.ticks.size)
            assertNull(l.itemByKey("cat:courgette"))
            planner.restore(removal)
            assertTrue(r.repo.snapshot().itemByKey("cat:courgette") != null)
            assertEquals(2, meals().size)
            assertEquals(MealShopping.TO_BUY, meals().single { it.title == "Chilli" }.shopping)
        }

    @Test
    fun removingAMealWhoseOnlyPurchaseIsItsOwnKeepsThatPurchase() =
        runBlocking {
            planner.plan(week, r.save("Chilli", "2 onions", "1 courgette"))
            planner.addWeekToList(week)
            r.repo.tick(r.repo.snapshot().itemByKey("cat:courgette")!!.id)
            planner.unplan(meals().single().id)
            val l = r.repo.snapshot()
            assertTrue(l.isDone(l.itemByKey("cat:courgette")!!.id))
            assertNull(l.itemByKey("cat:onion"))
            r.repo.finish()
            // The trip still records what was bought.
            assertEquals(listOf("cat:courgette"), r.db.dao().allTripItems().map { it.mergeKey })
        }

    @Test
    fun undoOfAddingTheWeekKeepsSomethingBoughtSince() =
        runBlocking {
            planner.plan(week, r.save("Chilli", "2 onions", "1 courgette"))
            val added = planner.addWeekToList(week)!!
            r.repo.tick(r.repo.snapshot().itemByKey("cat:onion")!!.id)
            planner.undoAddWeek(added)
            val l = r.repo.snapshot()
            assertTrue(l.isDone(l.itemByKey("cat:onion")!!.id))
            assertNull(l.itemByKey("cat:courgette"))
            assertEquals(1, l.ticks.size)
        }

    @Test
    fun undoOfARemovalAfterTheMealWentOnAgainIsRefusedRatherThanDoubled() =
        runBlocking {
            planner.plan(week, r.save("Chilli", "2 onions"))
            planner.addWeekToList(week)
            val removal = r.repo.removeUse(meals().single().useId!!)!!
            assertNotNull(planner.addWeekToList(week))
            assertFalse(r.repo.restoreUse(removal))
            assertEquals(Rational.of(2), onions())
        }

    @Test
    fun aRecipeAlreadyPlannedThatGoesOnFromElsewhereIsThatMeal() =
        runBlocking {
            val key = r.save("Chilli", "2 onions")
            planner.plan(week, key)
            val id = r.db.dao().recipesByKey(key).maxOf { it.id }
            r.repo.addSavedRecipe(id)
            assertEquals(1, meals().size)
            assertEquals(MealShopping.TO_BUY, meals().single().shopping)
            assertNull(planner.addWeekToList(week))
            assertEquals(Rational.of(2), onions())
        }

    @Test
    fun aMealTakenOffUnboughtThatGoesOnFromElsewhereIsThatMealNotASecond() =
        runBlocking {
            val key = r.save("Chilli", "2 onions")
            planner.plan(week, key)
            planner.addWeekToList(week)
            r.repo.clearAll()
            assertEquals(MealShopping.TAKEN_OFF, meals().single().shopping)
            // "Add to list" on the saved recipe (the Recipes tab, or a share of the same page).
            r.repo.addSavedRecipe(r.db.dao().recipesByKey(key).maxOf { it.id })
            assertEquals(listOf(MealShopping.TO_BUY), meals().map { it.shopping })
            assertNull(planner.addWeekToList(week))
            assertEquals(Rational.of(2), onions())
        }

    @Test
    fun undoOfAnImportThatWentOnAsAPlannedMealLeavesTheMealPlanned() =
        runBlocking {
            val key = r.save("Chilli", "2 onions")
            val id = planner.plan(week, key)
            r.now += 60_000
            // Sharing the same page: the review's "Add" is that meal going on.
            val a = r.import("2 onions", url = key)
            assertEquals(a.useId, meals().single().useId)
            r.repo.undoImport(a.useId)
            val m = meals().single()
            assertEquals(id, m.id)
            assertNull(m.useId)
            assertEquals(MealShopping.NOT_ON_LIST, m.shopping)
            assertNull(onions())
        }

    @Test
    fun undoOfAnImportKeepsWhatWasBoughtMeanwhile() =
        runBlocking {
            val a = r.import("2 onions", "200 ml milk")
            val l = r.repo.snapshot()
            r.repo.tick(l.itemByKey("cat:onion")!!.id)
            r.repo.undoImport(a.useId)
            val after = r.repo.snapshot()
            assertEquals(Rational.of(2), onions())
            assertTrue(after.isDone(after.itemByKey("cat:onion")!!.id))
            assertNull(after.itemByKey("cat:milk"))
            assertEquals(MealShopping.BOUGHT, meals().single().shopping)
        }

    @Test
    fun removingAMealAlreadyBoughtLeavesTheListAlone() =
        runBlocking {
            planner.plan(week, r.save("Chilli", "2 onions"))
            planner.addWeekToList(week)
            r.repo.tick(r.repo.snapshot().itemByKey("cat:onion")!!.id)
            val before = listDump()
            val removal = planner.unplan(meals().single().id)!!
            assertNull(removal.use)
            assertEquals(before, listDump())
        }

    @Test
    fun finishingShoppingLeavesTheWeeksMealsPlanned() =
        runBlocking {
            planner.plan(week, r.save("Chilli", "2 onions"))
            planner.addWeekToList(week)
            r.repo.tick(r.repo.snapshot().itemByKey("cat:onion")!!.id)
            r.repo.finish()
            val m = meals().single()
            assertEquals(MealShopping.BOUGHT, m.shopping)
            assertNull(planner.addWeekToList(week))
        }

    @Test
    fun clearingTheListMakesTheMealsPendingAgainWithoutDoubling() =
        runBlocking {
            planner.plan(week, r.save("Chilli", "2 onions"))
            planner.addWeekToList(week)
            r.repo.clearAll()
            assertEquals(MealShopping.TAKEN_OFF, meals().single().shopping)
            assertNotNull(planner.addWeekToList(week))
            assertEquals(Rational.of(2), onions())
            assertEquals(MealShopping.TO_BUY, meals().single().shopping)
        }

    @Test
    fun theSameRecipeTwiceIsTwoMealsAndTwiceTheShopping() =
        runBlocking {
            val chilli = r.save("Chilli", "2 onions")
            planner.plan(week, chilli)
            planner.plan(week, chilli)
            planner.addWeekToList(week)
            assertEquals(2, meals().size)
            assertEquals(Rational.of(4), onions())
        }

    @Test
    fun repeatingLastWeekPlansItsMealsOnceAndUndoTakesThemBack() =
        runBlocking {
            val chilli = r.save("Chilli", "2 onions")
            val soup = r.save("Soup", "1 carrot")
            val last = week - 1
            planner.plan(last, chilli)
            planner.plan(last, chilli)
            planner.plan(last, soup)
            planner.setServings(planner.week(last)[2].id, 6)
            planner.plan(week, chilli)
            assertEquals(last, planner.previousPlannedWeek(week))
            val ids = planner.repeatWeek(last, week)
            // One chilli was there already: the second, and the soup at its six servings.
            assertEquals(2, ids.size)
            assertEquals(listOf("Chilli", "Chilli", "Soup"), meals().map { it.title })
            assertEquals(6, meals()[2].servings)
            assertTrue(meals().all { it.useId == null })
            // Last week is untouched.
            assertEquals(3, planner.week(last).size)
            planner.unplanNew(ids)
            assertEquals(1, meals().size)
        }

    @Test
    fun planningNextWeekLeavesTheListAlone() =
        runBlocking {
            r.import("2 onions")
            val chilli = r.save("Chilli", "1 courgette")
            val before = listDump()
            planner.plan(week + 1, chilli)
            assertEquals(before, listDump())
            assertEquals(1, meals().size)
            assertEquals(1, planner.week(week + 1).size)
        }

    @Test
    fun anEditOfAMealOnTheListKeepsItPlanned() =
        runBlocking {
            planner.plan(week, r.save("Chilli", "2 onions"))
            planner.addWeekToList(week)
            val m = meals().single()
            val src = r.repo.editSource(m.useId!!)!!
            val stored =
                r.repo.storeRecipe(
                    src.recipe.url, "Chilli", 4, UnitLocale.METRIC, "WEB", ParsedBy.RULES, null, false, src.lines,
                    src.recipe.libraryKey,
                )
            val edited = r.repo.apply(r.repo.preview(stored, 6, replacing = m.useId), replacing = m.useId) as ApplyResult.Applied
            val after = meals().single()
            assertEquals(m.id, after.id)
            assertEquals(edited.useId, after.useId)
            assertEquals(6, after.servings)
            assertEquals(Rational.of(3), onions())
        }

    @Test
    fun removingFromTheListElsewhereLeavesTheMealPlannedAndUndoRelinksIt() =
        runBlocking {
            val a = r.import("2 onions")
            val removal = r.repo.removeUse(a.useId)!!
            assertEquals(MealShopping.NOT_ON_LIST, meals().single().shopping)
            r.repo.restoreUse(removal)
            assertEquals(a.useId, meals().single().useId)
        }

    @Test
    fun aRecipeRemovedFromYourRecipesStaysInAComingWeeksPlanAndSurvivesThePurge() =
        runBlocking {
            val key = r.save("Chilli", "2 onions")
            // Five weeks ahead: still to come after the purge's thirty days.
            val planned = week + 5
            planner.plan(planned, key)
            r.repo.removeFromLibrary(key)
            r.now += 30L * 24 * 3600 * 1000
            r.repo.purgeDeleted()
            val m = planner.week(planned).single()
            assertEquals("Chilli", m.title)
            assertFalse(m.saved)
            assertNotNull(planner.addWeekToList(planned))
            assertEquals(Rational.of(2), onions())
        }

    @Test
    fun undoOfAddingTheWeekTakesItAllOffAndLeavesTheMealsPlanned() =
        runBlocking {
            r.repo.manualAdd("milk", com.robert4lyf.hypershop.core.model.QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), null)
            val before = r.repo.snapshot().liveItems.map { it.mergeKey }
            planner.plan(week, r.save("Chilli", "2 onions"))
            planner.plan(week, r.save("Soup", "1 onion"))
            val added = planner.addWeekToList(week)!!
            planner.undoAddWeek(added)
            assertEquals(before, r.repo.snapshot().liveItems.map { it.mergeKey })
            assertEquals(2, meals().size)
            assertTrue(meals().all { it.shopping == MealShopping.NOT_ON_LIST })
        }
}
