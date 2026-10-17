package com.robert4lyf.hypershop.ui.list

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.di.AppContainer
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
    fun groupedByAisleInStoreOrderAndDoneSinks() {
        val c = TestContainer.create()
        c.add("2 onions", "1 carrot", "500g beef mince", "1 tsp ground cumin")
        val vm = ListViewModel(c)
        var ui = await(vm.ui) { it.loaded && it.aisles.isNotEmpty() }
        assertEquals(listOf(AisleCode.PRODUCE, AisleCode.MEAT_FISH, AisleCode.SPICES_CONDIMENTS), ui.aisles.map { it.first })
        assertEquals(listOf("onion", "carrot"), ui.aisles.first().second.map { it.name })
        assertEquals("2 onions", ui.row("onion").main)
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
        vm.toggle(ui.row("onion"))
        val m = nextMessage(vm)
        assertEquals("Ticked onion", m.text)
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
        vm.delete(ui.row("carrot"))
        var m = nextMessage(vm)
        await(vm.ui) { u -> u.aisles.flatMap { it.second }.none { it.name == "carrot" } }
        runBlocking { m.undo!!() }
        ui = await(vm.ui) { u -> u.aisles.flatMap { it.second }.any { it.name == "carrot" } }
        vm.toggle(ui.row("onion"))
        await(vm.ui) { it.doneCount == 1 }
        nextMessage(vm)
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
        vm.addTyped("1 onion")
        val asked = await(vm.ui) { it.plusOne != null }
        assertEquals("onion", asked.plusOne!!.name)
        vm.confirmPlusOne()
        await(vm.ui) { it.plusOne == null && it.row("onion").main == "3 onions" }
        vm.addTyped("onion")
        await(vm.ui) { it.plusOne != null }
        vm.dismissPlusOne()
        assertEquals("3 onions", await(vm.ui) { it.plusOne == null }.row("onion").main)
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
}
