package com.robert4lyf.hypershop.ui.list

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.settings.ListView
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.await
import com.robert4lyf.hypershop.testutil.readyJob
import com.robert4lyf.hypershop.ui.common.UiMessage
import com.robert4lyf.hypershop.ui.review.ReviewState
import com.robert4lyf.hypershop.ui.review.ReviewViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
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
    fun categorisedGroupsRowsWithinTheAisle() {
        val c = TestContainer.create()
        c.add("1 lemon", "2 onions", "1 bunch basil", "1 carrot")
        val vm = ListViewModel(c)
        val standard = await(vm.ui) { it.loaded && it.aisles.isNotEmpty() }
        assertEquals(listOf("lemon", "onion", "basil", "carrot"), standard.aisles.single().second.map { it.name })
        vm.setView(ListView.TYPE)
        val grouped = await(vm.ui) { it.categorised }
        assertEquals(listOf("onion", "carrot", "basil", "lemon"), grouped.aisles.single().second.map { it.name })
        assertEquals(listOf("Vegetables", "Vegetables", "Herbs", "Fruit"), grouped.aisles.single().second.map { it.group })
    }

    @Test
    fun byRecipeGivesEachRecipeACardThenSharedThenByHand() {
        val c = TestContainer.create()
        c.add("2 onions", "500g beef mince", "1 carrot")
        val dal = ReviewViewModel(c, runBlocking { c.readyJob("1 onion", "200g red lentils", title = "Red lentil dal") })
        await(dal.state) { it is ReviewState.Ready }
        dal.confirm()
        await(dal.state) { it is ReviewState.Added }
        val vm = ListViewModel(c)
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
        val vm = ListViewModel(c)
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
        val vm = ListViewModel(c)
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
        val vm = ListViewModel(c)
        val ui = await(vm.ui) { it.loaded && it.aisles.isNotEmpty() }
        // A tick is announced, with Undo; tapping again is the undo too.
        vm.toggle(ui.row("onion"))
        var m = nextMessage(vm)
        assertEquals("Ticked onion", m.text)
        val done = await(vm.ui) { it.row("onion").state == TickState.DONE }
        vm.toggle(done.row("onion"))
        await(vm.ui) { it.row("onion").state == TickState.OPEN }
        vm.toggle(ui.row("onion"))
        await(vm.ui) { it.row("onion").state == TickState.DONE }
        m = nextMessage(vm)
        runBlocking { m.undo!!() }
        await(vm.ui) { it.row("onion").state == TickState.OPEN }
        vm.toggle(ui.row("onion"))
        await(vm.ui) { it.row("onion").state == TickState.DONE }
        c.add("1 onion")
        val partial = await(vm.ui) { it.row("onion").state == TickState.PARTIAL }
        assertEquals("1 more onion", partial.row("onion").main)
        assertEquals("2 already ticked", partial.row("onion").sub)
    }

    @Test
    fun deleteFinishAndClearAllUndo() {
        val c = TestContainer.create()
        c.add("2 onions", "1 carrot")
        val vm = ListViewModel(c)
        var ui = await(vm.ui) { it.loaded && it.aisles.isNotEmpty() }
        ui.row("carrot").let { vm.delete(it.id, it.name) }
        var m = nextMessage(vm)
        await(vm.ui) { u -> u.aisles.flatMap { it.second }.none { it.name == "carrot" } }
        runBlocking { m.undo!!() }
        ui = await(vm.ui) { u -> u.aisles.flatMap { it.second }.any { it.name == "carrot" } }
        vm.toggle(ui.row("onion"))
        assertEquals("Ticked onion", nextMessage(vm).text)
        await(vm.ui) { it.doneCount == 1 }
        vm.finishShopping()
        m = nextMessage(vm)
        assertEquals("Finished: removed 1 ticked items", m.text)
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
        val vm = ListViewModel(c)
        await(vm.ui) { it.isEmpty }
        vm.addTyped("2 onions")
        await(vm.ui) { it.aisles.isNotEmpty() && it.row("onion").main == "2 onions" }
        vm.addTyped("milk\n\n6 eggs\n")
        assertEquals("Added 2 items", nextMessage(vm).text)
        await(vm.ui) { u -> u.aisles.flatMap { it.second }.size == 3 }
        // Typed by hand: no recipe to name.
        assertNull(await(vm.ui) { it.aisles.isNotEmpty() }.row("onion").from)
        vm.addTyped("1 onion")
        val asked = await(vm.ui) { it.plusOne != null }
        assertEquals("onion", asked.plusOne!!.name)
        assertEquals("1", asked.plusOne!!.amount)
        vm.confirmPlusOne()
        await(vm.ui) { it.plusOne == null && it.row("onion").main == "3 onions" }
        vm.addTyped("onion")
        assertNull(await(vm.ui) { it.plusOne != null }.plusOne!!.amount)
        vm.dismissPlusOne()
        assertEquals("3 onions", await(vm.ui) { it.plusOne == null }.row("onion").main)
    }

    @Test
    fun suggestionsSkipTheAmountAndKeepItWhenPicked() {
        val c = TestContainer.create()
        val vm = ListViewModel(c)
        await(vm.ui) { it.isEmpty }
        assertTrue(vm.suggestions("2 oni").contains("onion"))
        assertTrue(vm.suggestions("200 g oni").contains("onion"))
        vm.addSuggested("2 oni", "onion")
        assertEquals("2 onions", await(vm.ui) { it.aisles.isNotEmpty() }.row("onion").main)
        vm.addSuggested("cor", "coriander")
        assertEquals("coriander", await(vm.ui) { u -> u.aisles.flatMap { it.second }.size == 2 }.row("coriander").main)
    }

    @Test
    fun suggestionsSampleAndShareText() {
        val c = TestContainer.create()
        val vm = ListViewModel(c)
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
        val vm = ListViewModel(c)
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
        val vm = ListViewModel(c)
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
        val vm = ListViewModel(c)
        val ui = await(vm.ui) { it.loaded && it.aisles.isNotEmpty() }
        // Straight after the tick: the screen may not show it yet; the count comes from the write.
        runBlocking { c.repository.tick(ui.row("onion").id) }
        vm.finishShopping()
        assertEquals("Finished: removed 1 ticked items", nextMessage(vm).text)
    }

    @Test
    fun theLastItemOpenedWinsAndCloseStaysClosed() {
        val c = TestContainer.create()
        c.add("2 onions", "1 carrot")
        val vm = ListViewModel(c)
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

    /** Lets everything queued run: the main looper, and Room and background work. */
    private fun settle() {
        repeat(20) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
    }
}
