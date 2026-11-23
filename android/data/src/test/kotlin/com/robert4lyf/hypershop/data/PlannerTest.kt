package com.robert4lyf.hypershop.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.merge.ApplyResult
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.plan.Week
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

    /** Step Z "Add to list" on a saved recipe: on the list, planned for this week and linked. */
    private suspend fun add(key: String) = planner.addToList(key) as ApplyResult.Applied

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
    fun twoRecipesSharingOnionsAreOneOnionRowAndTwoOfThisWeeksMeals() =
        runBlocking {
            val chilli = r.save("Chilli", "2 onions", "500 g beef mince")
            val bolognese = r.save("Bolognese", "1 onion", "400 g tinned tomatoes")
            val a = add(chilli)
            val b = add(bolognese)
            assertEquals(3, r.repo.snapshot().liveItems.size)
            assertEquals(1, r.repo.snapshot().liveItems.count { it.mergeKey == "cat:onion" })
            assertEquals(Rational.of(3), onions())
            assertEquals(listOf(a.useId, b.useId), meals().map { it.useId })
            assertTrue(meals().all { it.shopping == MealShopping.TO_BUY })
        }

    @Test
    fun anOnionAlreadyOnTheListJoinsAndStaplesStayAtHome() =
        runBlocking {
            r.repo.manualAdd("onion", com.robert4lyf.hypershop.core.model.QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), null)
            add(r.save("Chilli", "2 onions", "Salt, to taste"))
            // The hand-added onion is still there, with the chilli's two beside it.
            assertEquals(Rational.of(3), onions())
            assertNull(r.repo.snapshot().itemByKey("cat:salt"))
        }

    @Test
    fun moreServingsChangeOnlyThatMealsShareAndKeepWhatWasBought() =
        runBlocking {
            add(r.save("Chilli", "2 onions"))
            add(r.save("Soup", "2 onions"))
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
            r.import("1 carrot")
            planner.plan(week, r.save("Chilli", "2 onions"))
            val before = listDump()
            val m = meals().single { it.useId == null }
            assertTrue(planner.setServings(m.id, 2))
            assertEquals(2, meals().single { it.id == m.id }.servings)
            assertEquals(before, listDump())
            assertNull(onions())
        }

    @Test
    fun removingAMealBeforeShoppingTakesItsItemsOffAndUndoPutsThemBack() =
        runBlocking {
            add(r.save("Chilli", "2 onions", "1 courgette"))
            add(r.save("Soup", "1 onion"))
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
            add(r.save("Chilli", "2 onions", "1 courgette"))
            add(r.save("Soup", "1 onion"))
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
            add(r.save("Chilli", "2 onions", "1 courgette"))
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
    fun undoOfAddToListTakesBackARecipeThatPutNothingOnTheList() =
        runBlocking {
            // KNOWN_ISSUES #50: every line at home, so nothing to buy; Undo left it "On your list".
            val added = add(r.save("Seasoning", "Salt, to taste"))
            assertEquals(1, r.repo.usesOnList().size)
            planner.takeBack(added.useId)
            assertTrue(r.repo.usesOnList().isEmpty())
            assertTrue(meals().isEmpty())
        }

    @Test
    fun undoOfAddToListKeepsSomethingBoughtSince() =
        runBlocking {
            val added = add(r.save("Chilli", "2 onions", "1 courgette"))
            r.repo.tick(r.repo.snapshot().itemByKey("cat:onion")!!.id)
            planner.takeBack(added.useId)
            val l = r.repo.snapshot()
            assertTrue(l.isDone(l.itemByKey("cat:onion")!!.id))
            assertNull(l.itemByKey("cat:courgette"))
            assertEquals(1, l.ticks.size)
            assertTrue(meals().isEmpty())
        }

    @Test
    fun undoOfARemovalAfterTheMealWentOnAgainIsRefusedRatherThanDoubled() =
        runBlocking {
            val key = r.save("Chilli", "2 onions")
            add(key)
            val removal = r.repo.removeUse(meals().single().useId!!)!!
            // Add to list again: the meal left planned goes on again, linked to the new use.
            assertEquals(add(key).useId, meals().single().useId)
            assertFalse(r.repo.restoreUse(removal))
            assertEquals(Rational.of(2), onions())
        }

    @Test
    fun aRecipeAlreadyPlannedThatGoesOnFromElsewhereIsThatMeal() =
        runBlocking {
            val key = r.save("Chilli", "2 onions")
            planner.plan(week, key)
            val id = r.db.dao().recipesByKey(key).maxOf { it.id }
            val a = r.repo.addSavedRecipe(id) as ApplyResult.Applied
            assertEquals(1, meals().size)
            assertEquals(MealShopping.TO_BUY, meals().single().shopping)
            assertEquals(a.useId, meals().single().useId)
            assertFalse(meals().single().shopping.pending)
            assertEquals(Rational.of(2), onions())
        }

    @Test
    fun aMealTakenOffUnboughtThatGoesOnFromElsewhereIsThatMealNotASecond() =
        runBlocking {
            val key = r.save("Chilli", "2 onions")
            add(key)
            r.repo.clearAll()
            assertEquals(MealShopping.TAKEN_OFF, meals().single().shopping)
            // "Add to list" on the saved recipe (the Recipes tab, or a share of the same page).
            r.repo.addSavedRecipe(r.db.dao().recipesByKey(key).maxOf { it.id })
            assertEquals(listOf(MealShopping.TO_BUY), meals().map { it.shopping })
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
            add(r.save("Chilli", "2 onions"))
            r.repo.tick(r.repo.snapshot().itemByKey("cat:onion")!!.id)
            val before = listDump()
            val removal = planner.unplan(meals().single().id)!!
            assertNull(removal.use)
            assertEquals(before, listDump())
        }

    @Test
    fun finishingShoppingLeavesTheWeeksMealsPlanned() =
        runBlocking {
            add(r.save("Chilli", "2 onions"))
            r.repo.tick(r.repo.snapshot().itemByKey("cat:onion")!!.id)
            r.repo.finish()
            val m = meals().single()
            assertEquals(MealShopping.BOUGHT, m.shopping)
            assertFalse(m.shopping.pending)
        }

    @Test
    fun clearingTheListMakesTheMealsPendingAgainWithoutDoubling() =
        runBlocking {
            val key = r.save("Chilli", "2 onions")
            add(key)
            r.repo.clearAll()
            assertEquals(MealShopping.TAKEN_OFF, meals().single().shopping)
            assertNotNull(planner.addToList(key))
            assertEquals(Rational.of(2), onions())
            assertEquals(MealShopping.TO_BUY, meals().single().shopping)
        }

    @Test
    fun theSameRecipeTwiceIsTwoMealsAndTwiceTheShopping() =
        runBlocking {
            val chilli = r.save("Chilli", "2 onions")
            add(chilli)
            add(chilli)
            assertEquals(2, meals().size)
            assertEquals(Rational.of(4), onions())
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
            add(r.save("Chilli", "2 onions"))
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
            // Its lines are kept with it, for when that week comes.
            assertEquals(1, r.db.dao().lines(r.db.dao().recipesByKey(key).single().id).size)
        }

    @Test
    fun undoOfAddToListTakesItAllOffAndTheMealsLeaveThePlan() =
        runBlocking {
            r.repo.manualAdd("milk", com.robert4lyf.hypershop.core.model.QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), null)
            val before = r.repo.snapshot().liveItems.map { it.mergeKey }
            val chilli = add(r.save("Chilli", "2 onions"))
            val soup = add(r.save("Soup", "1 onion"))
            // One Undo for both adds, latest first (RecipesViewModel.addToList).
            planner.takeBack(soup.useId)
            planner.takeBack(chilli.useId)
            assertEquals(before, r.repo.snapshot().liveItems.map { it.mergeKey })
            assertTrue(meals().isEmpty())
        }
}
