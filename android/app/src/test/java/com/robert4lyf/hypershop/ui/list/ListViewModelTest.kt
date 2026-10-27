package com.robert4lyf.hypershop.ui.list

import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.settings.ListMode
import com.robert4lyf.hypershop.settings.ListView
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.await
import com.robert4lyf.hypershop.testutil.readyJob
import com.robert4lyf.hypershop.ui.common.UiMessage
import com.robert4lyf.hypershop.ui.review.ReviewState
import com.robert4lyf.hypershop.ui.review.ReviewViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/** Adds lines through the real review flow. */
internal fun AppContainer.add(vararg lines: String) {
    val vm = ReviewViewModel(this, runBlocking { readyJob(*lines) })
    await(vm.state) { it is ReviewState.Ready }
    vm.confirm()
    await(vm.state) { it is ReviewState.Added }
}

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ListViewModelTest {
    private val models = java.util.concurrent.CopyOnWriteArrayList<androidx.lifecycle.ViewModel>()

    private fun <T : androidx.lifecycle.ViewModel> tracked(vm: T): T = vm.also { models += it }

    /** A view model's flows run on after a test (Eagerly); stop them, and let a read in flight finish, before the next. */
    @org.junit.After
    fun stopModels() {
        for (vm in models) vm.viewModelScope.cancel()
        Thread.sleep(300)
    }

    private fun ListUi.row(name: String) = aisles.flatMap { it.second }.first { it.name == name }

    private fun nextMessage(vm: ListViewModel): UiMessage {
        var m: UiMessage? = null
        val job = CoroutineScope(Dispatchers.Main).launch { m = vm.events.first() }
        val end = System.currentTimeMillis() + 5000
        while (m == null && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        job.cancel()
        return m ?: throw AssertionError("no message")
    }

    @Test
    fun groupsKeepTogetherInEveryView() {
        val c = TestContainer.create()
        c.add("1 lemon", "2 onions", "1 bunch basil", "1 carrot")
        val vm = tracked(ListViewModel(c))
        // X.9: store order too, so the herbs are not split by what was added between them.
        val standard = await(vm.ui) { it.loaded && it.aisles.isNotEmpty() }
        assertEquals(listOf("onion", "carrot", "basil", "lemon"), standard.aisles.single().second.map { it.name })
        assertFalse(standard.categorised)
        vm.setView(ListView.TYPE)
        val grouped = await(vm.ui) { it.categorised }
        assertEquals(listOf("onion", "carrot", "basil", "lemon"), grouped.aisles.single().second.map { it.name })
        assertEquals(listOf("Vegetables", "Vegetables", "Herbs", "Fruit"), grouped.aisles.single().second.map { it.group })
    }

    @Test
    fun anArrangementIsKeptForTheNextTrip() {
        val c = TestContainer.create()
        c.add("2 onions", "1 carrot", "1 bunch basil", "1 bunch dill", "1 lemon")
        val vm = tracked(ListViewModel(c))

        fun names(u: ListUi) = u.aisles.flatMap { it.second }.map { it.name }
        var ui = await(vm.ui) { it.loaded && it.aisles.isNotEmpty() }
        assertEquals(listOf("onion", "carrot", "basil", "dill", "lemon"), names(ui))
        vm.arrange(true)
        ui = await(vm.ui) { it.arranging }
        assertTrue(ui.categorised)
        // Dill above basil, then the herbs above the vegetables.
        vm.moveItem(ui.row("dill"), -1)
        ui = await(vm.ui) { names(it) == listOf("onion", "carrot", "dill", "basil", "lemon") }
        vm.moveGroup(AisleCode.PRODUCE, "Herbs", -1)
        await(vm.ui) { names(it) == listOf("dill", "basil", "onion", "carrot", "lemon") }
        vm.arrange(false)
        await(vm.ui) { !it.arranging }
        // Next trip: everything bought and finished, then the same things added again in another order.
        runBlocking {
            for (r in vm.ui.value.aisles.single().second) c.repository.toggle(r.id)
            c.repository.finishShopping()
        }
        c.add("1 lemon", "1 bunch basil", "1 carrot", "1 bunch dill", "1 bunch mint")
        // Mint is new to the arrangement: it follows the arranged herbs.
        await(vm.ui) { names(it) == listOf("dill", "basil", "mint", "carrot", "lemon") }
    }

    @Test
    fun byRecipeGivesEachRecipeACardThenSharedThenByHand() {
        val c = TestContainer.create()
        c.add("2 onions", "500g beef mince", "1 carrot")
        val dal = ReviewViewModel(c, runBlocking { c.readyJob("1 onion", "200g red lentils", title = "Red lentil dal") })
        await(dal.state) { it is ReviewState.Ready }
        dal.confirm()
        await(dal.state) { it is ReviewState.Added }
        val vm = tracked(ListViewModel(c))
        vm.addTyped("milk")
        await(vm.ui) { u -> u.aisles.flatMap { it.second }.any { it.name == "milk" } }
        vm.setView(ListView.RECIPE)
        val ui = await(vm.ui) { it.view == ListView.RECIPE && it.recipes.size == 4 }
        assertEquals(
            listOf("Weeknight chilli", "Red lentil dal", "In more than one recipe", "Added by hand"),
            ui.recipes.map { it.title },
        )
        // Within a card, walking order: produce before meat.
        assertEquals(listOf("carrot", "beef mince"), ui.recipes[0].rows.map { it.name })
        assertEquals(listOf("red lentils"), ui.recipes[1].rows.map { it.name })
        // The card names the recipe, so its rows don't; a shared row names both.
        assertNull(ui.recipes[0].rows.first().from)
        assertEquals("onion", ui.recipes[2].rows.single().name)
        assertEquals("Weeknight chilli, Red lentil dal", ui.recipes[2].rows.single().from)
        assertEquals(listOf("milk"), ui.recipes[3].rows.map { it.name })
        // The other views carry no recipe cards.
        vm.setView(ListView.STORE)
        assertTrue(await(vm.ui) { it.view == ListView.STORE }.recipes.isEmpty())
    }

    @Test
    fun inStockIsListedAndNeedItPutsItOnTheList() {
        val c = TestContainer.create()
        val review = ReviewViewModel(c, runBlocking { c.readyJob("2 onions", "2 tbsp olive oil") })
        await(review.state) { it is ReviewState.Ready }
        review.setInStock("cat:olive_oil", true)
        await(review.state) { it is ReviewState.Ready && it.ui.addCount == 1 }
        review.confirm()
        await(review.state) { it is ReviewState.Added }
        val vm = tracked(ListViewModel(c))
        val ui = await(vm.ui) { it.loaded && it.inStock.isNotEmpty() }
        assertEquals("olive oil", ui.inStock.single().main)
        assertTrue(ui.aisles.flatMap { it.second }.none { it.name == "olive oil" })
        vm.needIt(ui.inStock.single())
        val after = await(vm.ui) { u -> u.inStock.isEmpty() && u.aisles.flatMap { it.second }.any { it.name == "olive oil" } }
        assertEquals("olive oil", after.row("olive oil").main)
    }

    @Test
    fun groupedByAisleInStoreOrderAndDoneSinks() {
        val c = TestContainer.create()
        c.add("2 onions", "1 carrot", "500g beef mince", "1 tsp ground cumin")
        val vm = tracked(ListViewModel(c))
        var ui = await(vm.ui) { it.loaded && it.aisles.isNotEmpty() }
        assertEquals(listOf(AisleCode.PRODUCE, AisleCode.MEAT_FISH, AisleCode.SPICES_CONDIMENTS), ui.aisles.map { it.first })
        assertEquals(listOf("onion", "carrot"), ui.aisles.first().second.map { it.name })
        assertEquals("2 onions", ui.row("onion").main)
        // Each came from the one recipe, so the row says so.
        assertEquals("Weeknight chilli", ui.row("onion").from)
        vm.toggle(ui.row("onion"))
        ui = await(vm.ui) { it.row("onion").state == TickState.DONE }
        assertEquals(listOf("carrot", "onion"), ui.aisles.first().second.map { it.name })
        assertEquals(3, ui.left)
    }

    @Test
    fun tickUndoAndPartial() {
        val c = TestContainer.create()
        c.add("2 onions")
        val vm = tracked(ListViewModel(c))
        val ui = await(vm.ui) { it.loaded && it.aisles.isNotEmpty() }
        // A tick is quiet; tapping again is the undo.
        vm.toggle(ui.row("onion"))
        val done = await(vm.ui) { it.row("onion").state == TickState.DONE }
        vm.toggle(done.row("onion"))
        await(vm.ui) { it.row("onion").state == TickState.OPEN }
        vm.toggle(ui.row("onion"))
        await(vm.ui) { it.row("onion").state == TickState.DONE }
        c.add("1 onion")
        val partial = await(vm.ui) { it.row("onion").state == TickState.PARTIAL }
        assertEquals("1 more onion", partial.row("onion").main)
        assertEquals("2 already bought", partial.row("onion").sub)
    }

    @Test
    fun deleteFinishAndClearAllUndo() {
        val c = TestContainer.create()
        c.add("2 onions", "1 carrot")
        val vm = tracked(ListViewModel(c))
        var ui = await(vm.ui) { it.loaded && it.aisles.isNotEmpty() }
        ui.row("carrot").let { vm.delete(it.id, it.name) }
        var m = nextMessage(vm)
        await(vm.ui) { u -> u.aisles.flatMap { it.second }.none { it.name == "carrot" } }
        runBlocking { m.undo!!() }
        ui = await(vm.ui) { u -> u.aisles.flatMap { it.second }.any { it.name == "carrot" } }
        vm.toggle(ui.row("onion"))
        await(vm.ui) { it.doneCount == 1 }
        vm.finishShopping()
        m = nextMessage(vm)
        assertEquals("Finished: 1 bought item removed", m.text)
        await(vm.ui) { it.left == 1 && it.doneCount == 0 }
        vm.clearAll()
        m = nextMessage(vm)
        await(vm.ui) { it.isEmpty }
        runBlocking { m.undo!!() }
        await(vm.ui) { it.left == 1 }
        assertTrue(runBlocking { withTimeout(1000) { c.repository.snapshot() } }.liveItems.size == 1)
    }

    @Test
    fun typedItemsMultiLinePasteAndPlusOne() {
        val c = TestContainer.create()
        val vm = tracked(ListViewModel(c))
        await(vm.ui) { it.isEmpty }
        vm.addTyped("2 onions")
        await(vm.ui) { it.aisles.isNotEmpty() && it.row("onion").main == "2 onions" }
        vm.addTyped("milk\n\n6 eggs\n")
        assertEquals("Added 2 items", nextMessage(vm).text)
        await(vm.ui) { u -> u.aisles.flatMap { it.second }.size == 3 }
        // Typed by hand: no recipe to name.
        assertNull(await(vm.ui) { it.aisles.isNotEmpty() }.row("onion").from)
        // Already on the list: nothing is doubled; the message offers the typed amount, one tap away.
        vm.addTyped("1 onion")
        val asked = nextMessage(vm)
        assertEquals("Onion is already on the list", asked.text)
        assertEquals("Add 1", asked.action)
        assertEquals("2 onions", vm.ui.value.row("onion").main)
        runBlocking { asked.undo!!.invoke() }
        await(vm.ui) { it.row("onion").main == "3 onions" }
        vm.addTyped("onion")
        assertEquals("Add 1 more", nextMessage(vm).action)
        assertEquals("3 onions", vm.ui.value.row("onion").main)
    }

    @Test
    fun limeJuiceTypedInIsLimesUnlessItSaysBottled() {
        val c = TestContainer.create()
        val vm = tracked(ListViewModel(c))
        await(vm.ui) { it.isEmpty }
        vm.addTyped("4 tbsp lime juice")
        assertEquals("2 limes", await(vm.ui) { it.aisles.isNotEmpty() }.row("lime").main)
        vm.addTyped("bottled lemon juice")
        await(vm.ui) { u -> u.aisles.flatMap { it.second }.size == 2 }
    }

    @Test
    fun suggestionsSkipTheAmountAndKeepItWhenPicked() {
        val c = TestContainer.create()
        val vm = tracked(ListViewModel(c))
        await(vm.ui) { it.isEmpty }
        assertTrue(vm.suggestions("2 oni").contains("onion"))
        assertTrue(vm.suggestions("200 g oni").contains("onion"))
        vm.addSuggested("2 oni", "onion")
        assertEquals("2 onions", await(vm.ui) { it.aisles.isNotEmpty() }.row("onion").main)
        vm.addSuggested("cor", "coriander")
        // W.2: no amount is still something to buy, in the way it is bought.
        assertEquals("1 pack coriander", await(vm.ui) { u -> u.aisles.flatMap { it.second }.size == 2 }.row("coriander").main)
    }

    @Test
    fun suggestionsSampleAndShareText() {
        val c = TestContainer.create()
        val vm = tracked(ListViewModel(c))
        await(vm.ui) { it.isEmpty }
        assertTrue(vm.suggestions("o").isEmpty())
        assertTrue(vm.suggestions("oni").contains("onion"))
        vm.addSample()
        val ui = await(vm.ui) { u -> u.aisles.flatMap { it.second }.size == ListViewModel.SAMPLE.size }
        vm.toggle(ui.row("egg"))
        await(vm.ui) { it.row("egg").state == TickState.DONE }
        val text = vm.shareText()
        assertTrue(text, text.startsWith("Shopping list\n\nProduce\n- 2 onions"))
        assertTrue(text, !text.contains("egg"))
        assertEquals("onion", vm.suggestions("on").first())
    }

    @Test
    fun itemSheetTotalsSourcesEditAndMove() {
        val c = TestContainer.create()
        c.add("2 onions", "200g onions")
        val vm = tracked(ListViewModel(c))
        val ui = await(vm.ui) { it.loaded && it.aisles.isNotEmpty() }
        vm.openItem(ui.row("onion").id)
        val sheet = await(vm.sheet) { it != null }!!
        assertTrue(!sheet.done)
        assertEquals(listOf("2", "200g"), sheet.totals)
        assertEquals(listOf("Weeknight chilli" to "2 + 200g"), sheet.sources.map { it.title to it.amount })
        assertEquals(AisleCode.PRODUCE, sheet.aisle)
        assertTrue(!vm.editQuantity(sheet.id, "lots"))
        assertTrue(vm.editQuantity(sheet.id, "5"))
        await(vm.ui) { it.row("onion").main.startsWith("5 onions") }
        vm.moveItem(sheet.id, AisleCode.TINS_JARS, always = false)
        await(vm.ui) { it.row("onion").aisle == AisleCode.TINS_JARS }
    }

    @Test
    fun theSheetShowsATickTheListHasNotDrawnYet() {
        val c = TestContainer.create()
        c.add("2 onions")
        val vm = tracked(ListViewModel(c))
        val ui = await(vm.ui) { it.loaded && it.aisles.isNotEmpty() }
        // Ticked straight in the repository: the list on screen still shows the row open.
        runBlocking { c.repository.tick(ui.row("onion").id) }
        vm.openItem(ui.row("onion").id)
        assertTrue(await(vm.sheet) { it != null }!!.done)
    }

    @Test
    fun finishCountsATickNotYetOnScreen() {
        val c = TestContainer.create()
        c.add("2 onions", "1 carrot")
        val vm = tracked(ListViewModel(c))
        val ui = await(vm.ui) { it.loaded && it.aisles.isNotEmpty() }
        // Straight after the tick: the screen may not show it yet; the count comes from the write.
        runBlocking { c.repository.tick(ui.row("onion").id) }
        vm.finishShopping()
        assertEquals("Finished: 1 bought item removed", nextMessage(vm).text)
    }

    @Test
    fun theLastItemOpenedWinsAndCloseStaysClosed() {
        val c = TestContainer.create()
        c.add("2 onions", "1 carrot")
        val vm = tracked(ListViewModel(c))
        val ui = await(vm.ui) { it.loaded && it.aisles.isNotEmpty() }
        val onion = ui.row("onion").id
        val carrot = ui.row("carrot").id
        vm.openItem(onion)
        vm.openItem(carrot)
        assertEquals(carrot, await(vm.sheet) { it != null }!!.id)
        settle()
        assertEquals(carrot, vm.sheet.value?.id)
        // Closed while loading: stays closed.
        vm.openItem(onion)
        vm.closeItem()
        settle()
        assertNull(vm.sheet.value)
        // A move made from the onion's sheet doesn't close the carrot's, opened since.
        vm.openItem(onion)
        await(vm.sheet) { it?.id == onion }
        vm.moveItem(onion, AisleCode.TINS_JARS, always = false)
        vm.openItem(carrot)
        await(vm.ui) { it.row("onion").aisle == AisleCode.TINS_JARS }
        settle()
        assertEquals(carrot, vm.sheet.value?.id)
    }

    @Test
    fun shopModeEndsHoweverTheListWasEmptied() {
        // Not only "Finish shopping": the last row deleted by hand (or a meal removed, an add undone)
        // must leave planning on, so the next item typed has a field to land in.
        val c = TestContainer.create()
        c.add("2 onions")
        val vm = tracked(ListViewModel(c))
        var ui = await(vm.ui) { it.loaded && it.aisles.isNotEmpty() }
        vm.setMode(ListMode.SHOP)
        ui = await(vm.ui) { it.shopping }
        vm.delete(ui.row("onion").id, "onion")
        await(vm.ui) { it.isEmpty && it.mode == ListMode.PLAN }
        vm.addTyped("milk")
        assertTrue(!await(vm.ui) { it.aisles.isNotEmpty() }.shopping)
    }

    @Test
    fun shopModeIsASettingAndKeepsEveryTick() {
        val c = TestContainer.create()
        c.add("2 onions", "1 carrot", "500g beef mince")
        val vm = tracked(ListViewModel(c))
        var ui = await(vm.ui) { it.loaded && it.aisles.isNotEmpty() }
        assertEquals(ListMode.PLAN, ui.mode)
        assertTrue(!ui.shopping)
        vm.setMode(ListMode.SHOP)
        ui = await(vm.ui) { it.shopping }
        vm.toggle(ui.row("onion"))
        vm.toggle(ui.row("carrot"))
        ui = await(vm.ui) { it.doneCount == 2 }
        // Back to planning: the same rows, the same ticks, the same count.
        vm.setMode(ListMode.PLAN)
        ui = await(vm.ui) { !it.shopping }
        assertEquals(2, ui.doneCount)
        assertEquals(TickState.DONE, ui.row("onion").state)
        // A new view model (the process came back) reads the mode from the setting.
        vm.setMode(ListMode.SHOP)
        await(vm.ui) { it.shopping }
        val again = tracked(ListViewModel(c))
        assertTrue(await(again.ui) { it.loaded && it.aisles.isNotEmpty() }.shopping)
        // Finishing takes the bought rows away and, with nothing left, the shop screen with them.
        again.finishShopping()
        assertEquals("Finished: 2 bought items removed", nextMessage(again).text)
        val after = await(again.ui) { it.doneCount == 0 && it.left == 1 }
        assertTrue(after.shopping)
        again.toggle(after.row("beef mince"))
        again.finishShopping()
        val empty = await(again.ui) { it.isEmpty }
        assertTrue(!empty.shopping)
        // A trip that took everything ends in planning, so the next item typed lands on the planning screen.
        await(again.ui) { it.mode == ListMode.PLAN }
        again.addTyped("milk")
        assertTrue(!await(again.ui) { it.aisles.isNotEmpty() }.shopping)
    }

    /** Lets everything queued run: the main looper, and Room and background work. */
    private fun settle() {
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }
}
