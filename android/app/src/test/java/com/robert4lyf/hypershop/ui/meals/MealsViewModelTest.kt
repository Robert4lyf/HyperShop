package com.robert4lyf.hypershop.ui.meals

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.await
import com.robert4lyf.hypershop.testutil.awaitTrue
import com.robert4lyf.hypershop.testutil.readyJob
import com.robert4lyf.hypershop.ui.common.UiMessage
import com.robert4lyf.hypershop.ui.recipes.RecipesViewModel
import com.robert4lyf.hypershop.ui.review.ReviewState
import com.robert4lyf.hypershop.ui.review.ReviewViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** "Meals this week": the recipes on the list are the meals; picking a saved recipe adds it as the review would. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MealsViewModelTest {
    private fun MealsViewModel.shown(): MealsViewModel {
        CoroutineScope(Dispatchers.Main).launch { ui.collect {} }
        return this
    }

    /** Saves a recipe through the review (it joins the library) and takes it off the list again. */
    private fun save(
        c: AppContainer,
        title: String,
        vararg lines: String,
        servings: Int? = 4,
    ) {
        val vm = ReviewViewModel(c, runBlocking { c.readyJob(*lines, title = title, servings = servings) })
        await(vm.state) { it is ReviewState.Ready }
        vm.confirm()
        val added = await(vm.state) { it is ReviewState.Added } as ReviewState.Added
        runBlocking { c.repository.removeUse(added.useId) }
    }

    private fun messages(vm: MealsViewModel): MutableList<UiMessage> {
        val got = mutableListOf<UiMessage>()
        CoroutineScope(Dispatchers.Main).launch { vm.events.collect { got += it } }
        return got
    }

    @Test
    fun pickingASavedRecipeAddsItsIngredientsWithStaplesAtHomeAndUndo() {
        val c = TestContainer.create()
        save(c, "Weeknight chilli", "2 onions", "500g beef mince", "Salt, to taste")
        save(c, "Red lentil dal", "1 onion", "200g red lentils")
        val vm = MealsViewModel(c).shown()
        val got = messages(vm)
        var ui = await(vm.ui) { it.loaded && it.picks.size == 2 }
        assertTrue(ui.meals.isEmpty())
        assertFalse(ui.picks.any { it.onList })
        vm.add(ui.picks.first { it.title == "Weeknight chilli" })
        ui = await(vm.ui) { it.meals.size == 1 }
        assertEquals("Weeknight chilli", ui.meals.single().title)
        // Salt is a staple: at home, not added. The pick now says it is on the list.
        assertEquals(2, ui.meals.single().progress.toBuy)
        assertEquals(1, ui.meals.single().atHome)
        assertTrue(ui.picks.first { it.title == "Weeknight chilli" }.onList)
        await(vm.ui) { got.size == 1 }
        assertEquals("Added Weeknight chilli · 2 items", got[0].text)
        // The second meal merges with the first: one onion row, three onions.
        vm.add(ui.picks.first { it.title == "Red lentil dal" })
        ui = await(vm.ui) { it.meals.size == 2 }
        val l = runBlocking { c.repository.snapshot() }
        assertEquals(Rational.of(3), l.total(l.itemByKey("cat:onion")!!.id)[BaseKey.ITEMS])
        assertEquals(3, l.liveItems.size)
        // Undo takes the dal off again, and the chilli's onions stay.
        await(vm.ui) { got.size == 2 }
        runBlocking { got[1].undo!!.invoke() }
        ui = await(vm.ui) { it.meals.size == 1 }
        val after = runBlocking { c.repository.snapshot() }
        assertEquals(Rational.of(2), after.total(after.itemByKey("cat:onion")!!.id)[BaseKey.ITEMS])
    }

    @Test
    fun servingsRescaleAndRemoveHasUndo() {
        val c = TestContainer.create()
        save(c, "Weeknight chilli", "2 onions", "500g beef mince")
        val vm = MealsViewModel(c).shown()
        val got = messages(vm)
        var ui = await(vm.ui) { it.loaded && it.picks.size == 1 }
        vm.add(ui.picks.single())
        ui = await(vm.ui) { it.meals.size == 1 }
        assertEquals(4, ui.meals.single().servings)
        vm.setServings(ui.meals.single(), 8)
        ui = await(vm.ui) { it.meals.single().servings == 8 }
        awaitTrue {
            runBlocking { c.repository.snapshot() }.let {
                    l ->
                l.total(l.itemByKey("cat:onion")!!.id)[BaseKey.ITEMS] == Rational.of(4)
            }
        }
        vm.remove(ui.meals.single())
        ui = await(vm.ui) { it.meals.isEmpty() }
        assertTrue(runBlocking { c.repository.snapshot() }.liveItems.isEmpty())
        // The recipe is still saved; Undo brings the meal back at 8.
        assertEquals(1, ui.picks.size)
        await(vm.ui) { got.size == 2 }
        runBlocking { got[1].undo!!.invoke() }
        ui = await(vm.ui) { it.meals.size == 1 }
        assertEquals(8, ui.meals.single().servings)
    }

    @Test
    fun rapidServingsTapsAllCountAndUndoOnceToWhereTheyBegan() {
        val c = TestContainer.create()
        save(c, "Weeknight chilli", "2 onions")
        val vm = MealsViewModel(c).shown()
        val got = messages(vm)
        vm.add(await(vm.ui) { it.loaded && it.picks.size == 1 }.picks.single())
        await(vm.ui) { it.meals.size == 1 && got.size == 1 }
        // Four taps inside one frame: each counts from the last, not from the list's stale 4.
        repeat(4) { vm.stepServings(vm.ui.value.meals.single(), 1) }
        assertEquals(8, vm.ui.value.meals.single().servings)
        awaitTrue { got.size == 2 }
        assertEquals("Weeknight chilli serves 8", got[1].text)
        val onion = { runBlocking { c.repository.snapshot() }.let { l -> l.total(l.itemByKey("cat:onion")!!.id)[BaseKey.ITEMS] } }
        assertEquals(Rational.of(4), onion())
        // Never below one, and one Undo goes back to 4.
        runBlocking { got[1].undo!!.invoke() }
        assertEquals(Rational.of(2), onion())
        assertEquals(4, await(vm.ui) { it.meals.single().servings == 4 }.meals.single().servings)
        repeat(10) { vm.stepServings(vm.ui.value.meals.single(), -1) }
        assertEquals(1, vm.ui.value.meals.single().servings)
    }

    @Test
    fun aServingsChangeOnAMealRemovedElsewhereSaysSo() {
        val c = TestContainer.create()
        save(c, "Weeknight chilli", "2 onions")
        val vm = MealsViewModel(c).shown()
        val got = messages(vm)
        vm.add(await(vm.ui) { it.loaded && it.picks.size == 1 }.picks.single())
        val meal = await(vm.ui) { it.meals.size == 1 && got.size == 1 }.meals.single()
        runBlocking { c.repository.removeUse(meal.useId) }
        vm.stepServings(meal, 1)
        awaitTrue { got.size == 2 }
        assertEquals("Couldn't change Weeknight chilli: it is no longer on the list", got[1].text)
    }

    @Test
    fun theRecipesTabSeesTheSameMealsAndSearchFilters() {
        val c = TestContainer.create()
        save(c, "Weeknight chilli", "2 onions")
        val vm = MealsViewModel(c).shown()
        val ui = await(vm.ui) { it.loaded && it.picks.size == 1 }
        vm.add(ui.picks.single())
        await(vm.ui) { it.meals.size == 1 }
        val recipes = RecipesViewModel(c)
        CoroutineScope(Dispatchers.Main).launch { recipes.ui.collect {} }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf("Weeknight chilli"), await(recipes.ui) { it.loaded && it.uses.isNotEmpty() }.uses.map { it.title })
        vm.search("dal")
        Snapshot.sendApplyNotifications()
        assertTrue(await(vm.ui) { it.query == "dal" }.picks.isEmpty())
        vm.search("chil")
        Snapshot.sendApplyNotifications()
        assertEquals(1, await(vm.ui) { it.query == "chil" && it.picks.size == 1 }.picks.size)
    }
}
