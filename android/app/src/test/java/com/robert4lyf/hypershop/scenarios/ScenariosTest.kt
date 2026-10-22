package com.robert4lyf.hypershop.scenarios

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.sync.OpResult
import com.robert4lyf.hypershop.core.sync.WatchCodec
import com.robert4lyf.hypershop.core.sync.WatchMessage
import com.robert4lyf.hypershop.core.sync.WatchOp
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.settings.ListMode
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.await
import com.robert4lyf.hypershop.testutil.readyJob
import com.robert4lyf.hypershop.ui.list.ListUi
import com.robert4lyf.hypershop.ui.list.ListViewModel
import com.robert4lyf.hypershop.ui.list.TickState
import com.robert4lyf.hypershop.ui.list.add
import com.robert4lyf.hypershop.ui.review.ReviewState
import com.robert4lyf.hypershop.ui.review.ReviewViewModel
import com.robert4lyf.hypershop.watch.FakeWatchLink
import com.robert4lyf.hypershop.watch.WatchLinkState
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The three scenarios of docs/design/simplify.md's review, through the real
 * view models, repository and watch coordinator: a quick shop, a recipe-based
 * weekly shop, and a trip interrupted by a watch that went offline.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ScenariosTest {
    private val models = java.util.concurrent.CopyOnWriteArrayList<androidx.lifecycle.ViewModel>()

    private fun <T : androidx.lifecycle.ViewModel> tracked(vm: T): T = vm.also { models += it }

    /** A view model's flows run on after a test (Eagerly); stop them, and let a read in flight finish, before the next. */
    @org.junit.After
    fun stopModels() {
        for (vm in models) vm.viewModelScope.cancel()
        Thread.sleep(300)
    }

    private fun ListUi.row(name: String) = aisles.flatMap { it.second }.first { it.name == name }

    private fun AppContainer.fake() = watchLink as FakeWatchLink

    /** Every queued write has run and the screen has caught up: the list has not changed for a while. */
    private fun settled(vm: ListViewModel) {
        var last = vm.ui.value
        var quiet = 0
        while (quiet < 6) {
            await(vm.ui) { true }
            runBlocking { delay(50) }
            val now = await(vm.ui) { true }
            if (now == last) quiet++ else quiet = 0
            last = now
        }
    }

    private fun AppContainer.pages() = fake().sent.map { requireNotNull(WatchCodec.decodePage(it)) }

    @Test
    fun scenarioA_quickShopFromTypedItemsToFinished() {
        val c = TestContainer.create()
        val vm = tracked(ListViewModel(c))
        await(vm.ui) { it.isEmpty }
        for (l in listOf("milk", "6 eggs", "bread", "2 onions")) vm.addTyped(l)
        var ui = await(vm.ui) { u -> u.aisles.flatMap { it.second }.size == 4 }
        // Into the shop, tick everything as fast as taps come, finish: an empty, planned list.
        vm.setMode(ListMode.SHOP)
        ui = await(vm.ui) { it.shopping }
        for (r in ui.aisles.flatMap { it.second }) vm.toggle(r)
        ui = await(vm.ui) { it.left == 0 && it.doneCount == 4 }
        vm.finishShopping()
        // The trip took everything: an empty list, back in planning (the mode write follows the finish).
        ui = await(vm.ui) { it.isEmpty && it.mode == ListMode.PLAN }
        assertTrue(!ui.shopping)
        // Undo is still there: the whole trip comes back, bought.
        val stamp = runBlocking { c.db.dao().meta()!! }
        assertTrue(stamp.listRev > 1)
    }

    @Test
    fun rapidTicksOnOneItemAlwaysLandOnTheLastTap() {
        val c = TestContainer.create()
        c.add("2 onions", "1 carrot")
        val vm = tracked(ListViewModel(c))
        val ui = await(vm.ui) { it.loaded && it.aisles.isNotEmpty() }
        val onion = ui.row("onion")
        // Seven taps before the screen can redraw: an odd number ends ticked, and only one tick is left behind.
        repeat(7) { vm.toggle(onion) }
        settled(vm)
        assertEquals(TickState.DONE, vm.ui.value.row("onion").state)
        assertEquals(1, runBlocking { c.db.dao().ticks() }.size)
        // Seven more: back to open, no tick left.
        repeat(7) { vm.toggle(onion) }
        settled(vm)
        assertEquals(TickState.OPEN, vm.ui.value.row("onion").state)
        assertEquals(0, runBlocking { c.db.dao().ticks() }.size)
        // Alternating items keep their own state.
        vm.toggle(onion)
        vm.toggle(ui.row("carrot"))
        vm.toggle(onion)
        await(vm.ui) { it.row("onion").state == TickState.OPEN && it.row("carrot").state == TickState.DONE }
    }

    @Test
    fun scenarioB_twoSavedRecipesMergeAndAtHomeIsHonoured() {
        val c = TestContainer.create()
        // Two recipes reviewed: olive oil marked at home on the first, the onion shared by both.
        val first = ReviewViewModel(c, runBlocking { c.readyJob("2 onions", "2 tbsp olive oil", "500g beef mince", title = "Chilli") })
        await(first.state) { it is ReviewState.Ready }
        first.setInStock("cat:olive_oil", true)
        await(first.state) { it is ReviewState.Ready && it.ui.row("cat:olive_oil").inStock }
        first.confirm()
        await(first.state) { it is ReviewState.Added }
        val second = ReviewViewModel(c, runBlocking { c.readyJob("1 onion", "200g red lentils", title = "Dal") })
        await(second.state) { it is ReviewState.Ready }
        second.confirm()
        await(second.state) { it is ReviewState.Added }
        val vm = tracked(ListViewModel(c))
        val ui = await(vm.ui) { it.loaded && it.inStock.isNotEmpty() }
        assertEquals("3 onions", ui.row("onion").main)
        assertEquals(listOf("olive oil"), ui.inStock.map { it.name })
        assertTrue(ui.aisles.flatMap { it.second }.none { it.name == "olive oil" })
        // Change of mind in the shop: "Need it" brings the oil on under its recipe; the amount is the recipe's.
        vm.needIt(ui.inStock.single())
        val after = await(vm.ui) { it.inStock.isEmpty() && it.aisles.flatMap { r -> r.second }.any { it.name == "olive oil" } }
        assertEquals(4, after.left)
    }

    private fun com.robert4lyf.hypershop.ui.review.ReviewUi.row(key: String) = aisles.flatMap { it.second }.first { it.key == key }

    @Test
    fun scenarioC_watchTicksOfflineWhileARecipeIsAddedOnThePhone() =
        runBlocking {
            val c = TestContainer.create()
            c.add("2 onions", "1 carrot", "500g beef mince")
            val install = 77
            val epoch = c.repository.listEpoch().toInt()
            c.watchSync.handle(WatchCodec.encode(WatchMessage.Hello(epoch, install, 0, 1_760_000_000, 0)))
            val snapshot = c.pages()
            val seen = snapshot.first().listRev
            val onion = snapshot.flatMap { it.items }.first { it.name.contains("onion") }
            val carrot = snapshot.flatMap { it.items }.first { it.name.contains("carrot") }
            c.fake().sent.clear()

            // Offline: the watch ticks onions and carrots against the list it has.
            c.fake().setState(WatchLinkState.DISCONNECTED)
            // Meanwhile the phone adds a recipe that needs one more onion.
            c.add("1 onion", "200g red lentils")
            val phone = tracked(ListViewModel(c))
            var ui = await(phone.ui) { it.loaded && it.row("onion").main == "3 onions" }
            assertEquals(4, ui.left)

            // The watch comes back and sends its two ticks, each with the listRev it saw.
            c.fake().setState(WatchLinkState.APP_READY)
            c.watchSync.handle(
                WatchCodec.encode(
                    WatchMessage.Ops(
                        epoch,
                        install,
                        listOf(
                            WatchOp(1, onion.id, true, seen, 1_760_000_100),
                            WatchOp(2, carrot.id, true, seen, 1_760_000_101),
                        ),
                    ),
                ),
            )
            // The onion tick covers the two onions the watch saw; the dal's onion stays to buy. The carrot is done.
            ui = await(phone.ui) { it.row("carrot").state == TickState.DONE && it.row("onion").state == TickState.PARTIAL }
            assertEquals("1 more onion", ui.row("onion").main)
            assertEquals("2 already bought", ui.row("onion").sub)
            assertEquals(3, ui.left)
            // The watch is told so: the onion's op was partial, the carrot's ok, and the new items are in the snapshot it gets.
            withTimeout(10_000) { while (c.pages().none { it.pages > 0 }) delay(20) }
            val reply = c.pages().first { it.pages > 0 }
            assertEquals(2, reply.ackedThrough)
            assertEquals(listOf(1 to OpResult.PARTIAL), reply.ackExceptions)
            val names = c.pages().flatMap { it.items }.map { it.name }
            assertTrue(names.toString(), names.any { it.contains("lentil") })
            assertTrue(names.toString(), names.any { it == "1 more onion" })

            // Back in the shop on the phone: the counts agree with what the watch did.
            phone.setMode(ListMode.SHOP)
            ui = await(phone.ui) { it.shopping }
            assertEquals(1, ui.doneCount)
            assertEquals(Rational.of(2), c.repository.snapshot().let { l -> l.covered(l.itemByKey("cat:onion")!!.id)[BaseKey.ITEMS] })
        }

    @Test
    fun aReviewLeftMidWayComesBackAsLeftAndTheProcessCanDie() {
        val c = TestContainer.create()
        val job = runBlocking { c.readyJob("2 onions", "1 carrot", "200 ml milk", servings = 4) }
        val first = ReviewViewModel(c, job)
        await(first.state) { it is ReviewState.Ready }
        first.setInStock("cat:carrot", true)
        first.setServings(8)
        await(first.state) { it is ReviewState.Ready && it.ui.servings == 8 && it.ui.row("cat:carrot").inStock }
        // The process dies: the view model is simply gone, and the job row is all that is left.
        val again = ReviewViewModel(c, job)
        val ui = (await(again.state) { it is ReviewState.Ready } as ReviewState.Ready).ui
        assertEquals(8, ui.servings)
        assertTrue(ui.row("cat:carrot").inStock)
        assertEquals("4", ui.row("cat:onion").quantity)
        again.confirm()
        await(again.state) { it is ReviewState.Added }
        // The job is done: nothing is left ready to review.
        assertTrue(runBlocking { c.importJobs.ready.first() }.isEmpty())
    }
}
