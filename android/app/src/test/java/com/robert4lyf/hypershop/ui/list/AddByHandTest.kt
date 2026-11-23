package com.robert4lyf.hypershop.ui.list

import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.await
import com.robert4lyf.hypershop.ui.common.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

/**
 * Step AB: adding by hand through the Add field's usual items (Buy again's data): what is suggested, a chip's add at
 * its usual amount, the next candidate taking its place, Don't suggest, last shop's items in one go, and Undo that
 * never takes back what was bought.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AddByHandTest {
    private val models = java.util.concurrent.CopyOnWriteArrayList<ListViewModel>()
    private val collectors = CoroutineScope(Dispatchers.Main)

    @After
    fun stop() {
        collectors.cancel()
        for (vm in models) vm.viewModelScope.cancel()
        Thread.sleep(300)
    }

    private fun list(c: AppContainer) =
        ListViewModel(c).also {
            models += it
            collectors.launch { it.prep.collect {} }
            collectors.launch { it.ui.collect {} }
        }

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

    private val one = QtyBase.of(Rational.ONE, null, UnitLocale.METRIC)

    /** [names] bought by hand on two finished trips (the second with [amount] of each): regulars, oldest first. */
    private fun AppContainer.regulars(
        names: List<String>,
        amount: QtyBase = one,
    ) = runBlocking {
        awaitReady()
        repeat(2) {
            for (n in names) repository.tick(repository.manualAdd(n, amount, null))
            repository.finish()
        }
    }

    private fun total(
        c: AppContainer,
        name: String,
    ): QtyBase? =
        runBlocking { c.repository.snapshot() }.let {
                l ->
            l.itemByKey(c.repository.target(name).mergeKey)?.let { l.total(it.id) }
        }

    @Test
    fun theChipsAreTheRegularsNotOnTheListEightAtMostWithLastShopLast() {
        val c = TestContainer.create()
        val names = listOf("toothpaste", "kitchen roll", "bin bags", "shampoo", "batteries", "foil", "cat food", "tea", "coffee")
        c.regulars(names)
        runBlocking { c.repository.manualAdd("tea", one, null) }
        val vm = list(c)
        val usual = await(vm.prep) { it.loaded && it.usual.regulars.size == 8 }.usual
        assertFalse("tea is on the list", usual.regulars.any { it.name == "tea" })
        // Last shop's items not on the list: every one but the tea.
        assertEquals(8, usual.lastShop)
        assertEquals(UsualItems.MAX_CHIPS - 1, usual.shown.size)
        // One chip: on at its usual amount; the next candidate moves up into the shown eight.
        val first = usual.shown.first()
        val next = usual.regulars[UsualItems.MAX_CHIPS - 1]
        vm.addUsual(first)
        val after = await(vm.prep) { p -> p.usual.regulars.none { it.mergeKey == first.mergeKey } }.usual
        assertTrue(after.shown.any { it.mergeKey == next.mergeKey })
        assertTrue(total(c, first.name) != null)
    }

    @Test
    fun aChipAddsTheUsualAmountAndUndoTakesBackOnlyWhatIsUnbought() {
        val c = TestContainer.create()
        c.regulars(listOf("cat food"), QtyBase.of(Rational.of(3), null, UnitLocale.METRIC))
        val vm = list(c)
        val cat = await(vm.prep) { it.usual.regulars.size == 1 }.usual.regulars.single()
        vm.addUsual(cat)
        val m = nextMessage(vm)
        assertEquals(Rational.of(3), total(c, "cat food")!![BaseKey.ITEMS])
        // Bought before Undo: it stays, bought.
        val id = runBlocking { c.repository.snapshot() }.itemByKey(cat.mergeKey)!!.id
        runBlocking { c.repository.tick(id) }
        runBlocking { m.undo!!.invoke() }
        val l = runBlocking { c.repository.snapshot() }
        assertTrue(l.isDone(id))
    }

    @Test
    fun dontSuggestHidesItAndUndoBringsItBack() {
        val c = TestContainer.create()
        c.regulars(listOf("shampoo", "foil"))
        runBlocking { c.repository.manualAdd("foil", one, null) }
        val vm = list(c)
        val shampoo = await(vm.prep) { it.usual.regulars.size == 1 }.usual.regulars.single()
        vm.dontSuggest(shampoo)
        val m = nextMessage(vm)
        assertEquals("Won't suggest shampoo", m.text)
        await(vm.prep) { it.usual.regulars.isEmpty() }
        runBlocking { m.undo!!.invoke() }
        await(vm.prep) { it.usual.regulars.size == 1 }
    }

    @Test
    fun lastShopsChipAddsTheRestInOneGoNeverDoublingAndOneUndoKeepsWhatWasBought() {
        // Moved from Buy again's sheet (Add all, Repeat last shop): one write, one Undo; what is there is not doubled.
        val c = TestContainer.create()
        c.regulars(listOf("milk", "bread", "eggs"))
        runBlocking { c.repository.manualAdd("bread", one, null) }
        val vm = list(c)
        val usual = await(vm.prep) { it.usual.lastShop == 2 }.usual
        assertEquals(2, usual.regulars.size)
        vm.addLastShop()
        val m = nextMessage(vm)
        assertEquals("Added 2 items", m.text)
        await(vm.prep) { it.usual.isEmpty }
        val l = runBlocking { c.repository.snapshot() }
        assertEquals(Rational.ONE, l.total(l.itemByKey("cat:bread")!!.id)[BaseKey.ITEMS])
        val milk = l.itemByKey("cat:milk")!!.id
        assertTrue(l.itemByKey("cat:egg") != null)
        runBlocking { c.repository.tick(milk) }
        runBlocking { m.undo!!.invoke() }
        val after = runBlocking { c.repository.snapshot() }
        assertTrue("the milk was bought", after.isDone(milk))
        assertEquals(null, after.itemByKey("cat:egg"))
        assertTrue("bread was there before", after.itemByKey("cat:bread") != null)
    }

    @Test
    fun aChipSaysWhichAisleItWentToAndLightsItsRow() {
        val c = TestContainer.create()
        c.regulars(listOf("kitchen roll"))
        val vm = list(c)
        val roll = await(vm.prep) { it.usual.regulars.size == 1 }.usual.regulars.single()
        vm.addUsual(roll)
        assertEquals("Added to Household", nextMessage(vm).text)
        val id = runBlocking { c.repository.snapshot() }.itemByKey("cat:kitchen_roll")!!.id
        assertEquals(setOf(id), vm.flash.value)
    }

    private fun moveTo(
        vm: ListViewModel,
        id: Long,
        aisle: com.robert4lyf.hypershop.core.model.AisleCode,
    ): UiMessage {
        vm.openItem(id)
        await(vm.sheet) { it?.id == id }
        // Not asked (the editor shows no "Always" for a hand-added item): even false here, it is remembered.
        vm.saveItem(id, ItemChanges(aisle = aisle, alwaysAisle = false))
        return nextMessage(vm)
    }

    @Test
    fun aHandAddedItemMovedIsRememberedThroughFinishAndBackupAndUndoForgetsIt() {
        val household = com.robert4lyf.hypershop.core.model.AisleCode.HOUSEHOLD
        val c = TestContainer.create()
        val vm = list(c)
        vm.addTyped("widgets")
        assertEquals("Added to Other", nextMessage(vm).text)
        val id = runBlocking { c.repository.snapshot() }.liveItems.single().id
        vm.openItem(id)
        assertTrue(await(vm.sheet) { it?.id == id }!!.byHand)
        vm.closeItem()
        val moved = moveTo(vm, id, household)
        assertEquals(household, runBlocking { c.repository.overrides() }["name:widgets"])
        assertEquals("Moved to Household. HyperShop will remember.", moved.text)
        assertEquals(household, runBlocking { c.repository.snapshot() }.items.getValue(id).aisle)
        // Undo: back where it was, and forgotten.
        runBlocking { moved.undo!!.invoke() }
        assertEquals(com.robert4lyf.hypershop.core.model.AisleCode.OTHER, runBlocking { c.repository.snapshot() }.items.getValue(id).aisle)
        assertEquals(null, runBlocking { c.repository.overrideOf("name:widgets") })
        // Again, kept this time: bought, the trip finished, and typed again: it goes to Household.
        moveTo(vm, id, household)
        runBlocking {
            c.repository.tick(id)
            c.repository.finish()
        }
        vm.addTyped("widgets")
        assertEquals("Added to Household", nextMessage(vm).text)
        // And on a phone restored from a backup.
        val file = runBlocking { c.backups.snapshot() }
        val other = TestContainer.create()
        runBlocking {
            other.awaitReady()
            other.backups.restore(file)
        }
        assertEquals(household, runBlocking { other.repository.overrides() }["name:widgets"])
        assertEquals(household, runBlocking { other.repository.addByHand("widgets", QtyBase.PRESENCE, null) }.aisle)
    }

    @Test
    fun aRecipesItemStillAsksAndIsNotRememberedUnlessChosen() {
        val c = TestContainer.create()
        c.add("2 onions")
        val vm = list(c)
        val id = runBlocking { c.repository.snapshot() }.itemByKey("cat:onion")!!.id
        vm.openItem(id)
        assertFalse(await(vm.sheet) { it?.id == id }!!.byHand)
        vm.saveItem(id, ItemChanges(aisle = com.robert4lyf.hypershop.core.model.AisleCode.DRINKS, alwaysAisle = false))
        assertEquals("Saved", nextMessage(vm).text)
        assertEquals(null, runBlocking { c.repository.overrideOf("cat:onion") })
    }

    @Test
    fun aLineWithALinkInItIsStillAnItemAndALineThatIsNothingIsSaidSo() {
        // KNOWN_ISSUES #50: "Olive oil https://…" opened a recipe import and added nothing.
        val c = TestContainer.create()
        val vm = list(c)
        vm.addTyped("Olive oil https://shop.example/oil?id=3")
        assertTrue(nextMessage(vm).text.startsWith("Added to"))
        val l = runBlocking { c.repository.snapshot() }
        assertEquals(listOf("cat:olive_oil"), l.liveItems.map { it.mergeKey })
        assertTrue(runBlocking { c.importJobs.ready.first() }.isEmpty())
    }

    @Test
    fun withNoHistoryThereAreNoChips() {
        val c = TestContainer.create()
        runBlocking { c.repository.manualAdd("milk", one, null) }
        val vm = list(c)
        assertTrue(await(vm.prep) { it.loaded }.usual.isEmpty)
    }
}
