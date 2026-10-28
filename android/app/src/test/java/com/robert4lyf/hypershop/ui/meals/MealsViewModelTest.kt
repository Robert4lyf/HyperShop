package com.robert4lyf.hypershop.ui.meals

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.RuleParser
import com.robert4lyf.hypershop.data.MealShopping
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.settings.ListMode
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.await
import com.robert4lyf.hypershop.testutil.awaitTrue
import com.robert4lyf.hypershop.ui.common.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/** "Plan this week's shop": meals, Buy again and the At home check, each through the data layer, each with Undo. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class MealsViewModelTest {
    private val parser = RuleParser()

    /** Collectors and containers made by a test, stopped after it: nothing may read a database Robolectric has reset. */
    private val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val made = ArrayList<AppContainer>()

    private fun container() = TestContainer.create().also { made += it }

    private val models = ArrayList<MealsViewModel>()

    @After
    fun stop() {
        collectors.cancel()
        // The view model's own flows outlive their last collector by a few seconds (WhileSubscribed):
        // stop them, and let a read already in flight finish, before the database goes.
        for (vm in models) vm.viewModelScope.cancel()
        Thread.sleep(300)
        for (c in made) {
            c.applicationScope.cancel()
            c.db.close()
        }
    }

    private fun MealsViewModel.shown(): MealsViewModel {
        models += this
        collectors.launch { ui.collect {} }
        return this
    }

    /** A saved recipe in "Your recipes", not on the list and not planned. Its library key. */
    private fun save(
        c: AppContainer,
        title: String,
        vararg lines: String,
        servings: Int? = 4,
    ): String =
        runBlocking {
            val url = "https://example.com/${title.lowercase().replace(' ', '-')}"
            val parsed = lines.map { parser.parse(it) }
            val stored = c.repository.storeRecipe(url, title, servings, UnitLocale.METRIC, "WEB", ParsedBy.RULES, null, false, parsed)
            c.repository.saveToLibrary(stored.recipe.id, null)
            url
        }

    private fun messages(vm: MealsViewModel): MutableList<UiMessage> {
        val got = CopyOnWriteArrayList<UiMessage>()
        collectors.launch { vm.events.collect { got += it } }
        return got
    }

    private fun onions(c: AppContainer): Rational? =
        runBlocking { c.repository.snapshot() }.let { l -> l.itemByKey("cat:onion")?.let { l.total(it.id)[BaseKey.ITEMS] } }

    @Test
    fun planTwoMealsThenOneTapPutsTheWeekOnTheListMergedAndUndoTakesItOff() {
        val c = container()
        save(c, "Chilli", "2 onions", "Salt, to taste")
        save(c, "Soup", "1 onion", "2 carrots")
        val vm = MealsViewModel(c).shown()
        val got = messages(vm)
        val ui = await(vm.ui) { it.picks.size == 2 }
        assertEquals("This week", ui.weekName)
        ui.picks.forEach { vm.plan(it) }
        val planned = await(vm.ui) { it.meals.size == 2 && it.preview != null }
        assertEquals(2, planned.pending)
        // 2 rows new (the onion once, carrots), salt left off as usually at home.
        assertEquals(2, planned.preview!!.newItems)
        assertEquals(1, planned.preview!!.atHome)
        assertTrue(planned.picks.all { it.planned == 1 })
        vm.addToList()
        val added = await(vm.ui) { it.meals.all { m -> m.shopping == MealShopping.TO_BUY } }
        assertNull(added.preview)
        assertEquals(2, added.itemsLeft)
        assertEquals(Rational.of(3), onions(c))
        awaitTrue { got.any { it.text == "Added 2 meals · 2 items" } }
        runBlocking { got.first { it.text.startsWith("Added 2 meals") }.undo!!.invoke() }
        await(vm.ui) { it.meals.size == 2 && it.meals.all { m -> m.shopping == MealShopping.NOT_ON_LIST } }
        assertNull(onions(c))
    }

    @Test
    fun thisWeekMovesOnWithTheClock() {
        // Review B.12: the view model lives for the process; Monday must not show last week as "This week".
        var now = 1_760_000_000_000L
        val c = TestContainer.create(clock = { now }).also { made += it }
        val vm = MealsViewModel(c).shown()
        val before = await(vm.ui) { it.loaded }
        assertEquals("This week", before.weekName)
        now += 7 * 24 * 60 * 60 * 1000L
        vm.refreshWeek()
        val after = await(vm.ui) { it.week != before.week }
        assertEquals("This week", after.weekName)
        assertTrue(after.isCurrent)
        // A week chosen with the arrows is left where it is.
        vm.previousWeek()
        await(vm.ui) { it.week == before.week }
        now += 7 * 24 * 60 * 60 * 1000L
        vm.refreshWeek()
        assertEquals(before.week, await(vm.ui) { it.isPast }.week)
    }

    @Test
    fun cookedItCountsOnTheRecipeAndCanBeTakenBack() {
        val c = container()
        val key = save(c, "Chilli", "2 onions")
        val vm = MealsViewModel(c).shown()
        val got = messages(vm)
        vm.plan(await(vm.ui) { it.picks.isNotEmpty() }.picks.single())
        val meal = await(vm.ui) { it.meals.isNotEmpty() }.meals.single()
        assertEquals(key, meal.libraryKey)
        vm.cooked(meal)
        awaitTrue { runBlocking { c.repository.recipeNote(key) }?.timesCooked == 1 }
        // X.14: the card says so, which is what the tap used to leave unchanged.
        val cooked = await(vm.ui) { it.meals.single().cooked }.meals.single()
        val m = got.first { it.text.startsWith("Cooked Chilli") }
        runBlocking { m.undo!!.invoke() }
        awaitTrue { runBlocking { c.repository.recipeNote(key) }?.timesCooked == 0 }
        await(vm.ui) { !it.meals.single().cooked }
        // A tap on a cooked meal takes it back, and a stale second tap never counts twice.
        vm.cooked(cooked.copy(cooked = false))
        await(vm.ui) { it.meals.single().cooked }
        vm.cooked(cooked.copy(cooked = false))
        Thread.sleep(200)
        assertEquals(1, runBlocking { c.repository.recipeNote(key) }?.timesCooked)
        vm.cooked(await(vm.ui) { it.meals.single().cooked }.meals.single())
        await(vm.ui) { !it.meals.single().cooked }
        assertEquals(0, runBlocking { c.repository.recipeNote(key) }?.timesCooked)
    }

    @Test
    fun aRepeatedTapOnAddAddsTheWeekOnce() {
        val c = container()
        save(c, "Chilli", "2 onions")
        val vm = MealsViewModel(c).shown()
        vm.plan(await(vm.ui) { it.picks.isNotEmpty() }.picks.single())
        await(vm.ui) { it.pending == 1 }
        repeat(3) { vm.addToList() }
        await(vm.ui) { it.pending == 0 && it.meals.isNotEmpty() }
        awaitTrue { onions(c) != null }
        Thread.sleep(200)
        assertEquals(Rational.of(2), onions(c))
    }

    @Test
    fun weeksMoveAndAnEmptyWeekOffersTheLastPlannedOne() {
        val c = container()
        val chilli = save(c, "Chilli", "2 onions")
        val soup = save(c, "Soup", "1 carrot")
        val week = c.planner.currentWeek()
        runBlocking {
            c.planner.plan(week, chilli)
            c.planner.plan(week, soup)
        }
        val vm = MealsViewModel(c).shown()
        await(vm.ui) { it.meals.size == 2 }
        vm.nextWeek()
        val next = await(vm.ui) { it.weekName == "Next week" }
        assertTrue(next.meals.isEmpty())
        assertEquals(week, next.repeatFrom)
        assertEquals(listOf("Chilli", "Soup"), next.repeatTitles)
        vm.repeat()
        await(vm.ui) { it.weekName == "Next week" && it.meals.size == 2 }
        // Planning next week touches neither this week nor the list.
        assertTrue(runBlocking { c.repository.snapshot() }.liveItems.isEmpty())
        vm.previousWeek()
        vm.previousWeek()
        val last = await(vm.ui) { it.weekName == "Last week" }
        assertTrue(last.isPast)
        vm.thisWeek()
        await(vm.ui) { it.isCurrent && it.meals.size == 2 }
    }

    @Test
    fun servingsOnAMealOnTheListRescaleItAndRemoveTakesItsItemsOffWithUndo() {
        val c = container()
        save(c, "Chilli", "2 onions")
        val vm = MealsViewModel(c).shown()
        val got = messages(vm)
        vm.plan(await(vm.ui) { it.picks.isNotEmpty() }.picks.single())
        await(vm.ui) { it.pending == 1 }
        vm.addToList()
        val meal = await(vm.ui) { it.meals.singleOrNull()?.shopping == MealShopping.TO_BUY }.meals.single()
        vm.stepServings(meal, 2)
        await(vm.ui) { it.meals.single().servings == 6 }
        awaitTrue { onions(c) == Rational.of(3) }
        awaitTrue { got.any { it.text == "Chilli serves 6" } }
        vm.remove(await(vm.ui) { it.meals.single().servings == 6 }.meals.single())
        await(vm.ui) { it.meals.isEmpty() }
        assertNull(onions(c))
        awaitTrue { got.any { it.text == "Removed Chilli" } }
        runBlocking { got.first { it.text == "Removed Chilli" }.undo!!.invoke() }
        await(vm.ui) { it.meals.singleOrNull()?.shopping == MealShopping.TO_BUY }
        assertEquals(Rational.of(3), onions(c))
    }

    @Test
    fun buyAgainAddsAllInOneGoAndNeverDoublesWhatIsThere() {
        val c = container()
        runBlocking {
            repeat(2) {
                for (n in listOf(
                    "milk",
                    "bread",
                    "eggs",
                )) c.repository.manualAdd(n, QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), null)
                for (i in c.repository.snapshot().liveItems) c.repository.tick(i.id)
                c.repository.finish()
            }
            c.repository.manualAdd("bread", QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), null)
        }
        val vm = MealsViewModel(c).shown()
        val ui = await(vm.ui) { it.buyAgain.regulars.size == 3 }
        assertEquals(2, ui.toAdd.size)
        assertTrue(ui.buyAgain.regulars.single { it.name == "bread" }.onList)
        vm.addAllRegulars()
        await(vm.ui) { it.toAdd.isEmpty() }
        val l = runBlocking { c.repository.snapshot() }
        assertEquals(Rational.ONE, l.total(l.itemByKey("cat:bread")!!.id)[BaseKey.ITEMS])
        assertTrue(l.itemByKey("cat:milk") != null)
    }

    @Test
    fun theAtHomeCheckMovesSharedCupboardIngredientsOnceAndShopsAfter() {
        val c = container()
        save(c, "Chilli", "200 g basmati rice", "2 onions")
        save(c, "Curry", "100 g basmati rice", "1 tsp cumin")
        val vm = MealsViewModel(c).shown()
        await(vm.ui) { it.picks.size == 2 }.picks.forEach { vm.plan(it) }
        await(vm.ui) { it.pending == 2 }
        vm.addToList()
        val ui = await(vm.ui) { it.pending == 0 && it.home.isNotEmpty() }
        val rice = ui.home.single { it.name.contains("rice") }
        assertEquals(listOf("Chilli", "Curry"), rice.from.sorted())
        vm.have(ui.toCheck)
        await(vm.ui) { it.toCheck.isEmpty() && it.home.all { r -> r.have } }
        val l = runBlocking { c.repository.snapshot() }
        assertNull(l.itemByKey(rice.mergeKey))
        assertTrue(l.itemByKey("cat:onion") != null)
        vm.startShopping()
        awaitTrue { runBlocking { c.settings.listMode.first() } == ListMode.SHOP }
    }
}
