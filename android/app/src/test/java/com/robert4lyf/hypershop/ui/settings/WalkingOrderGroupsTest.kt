package com.robert4lyf.hypershop.ui.settings

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.sync.WatchCodec
import com.robert4lyf.hypershop.core.sync.WatchMessage
import com.robert4lyf.hypershop.data.ListRepository
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.await
import com.robert4lyf.hypershop.ui.list.ListViewModel
import com.robert4lyf.hypershop.watch.FakeWatchLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Step AC.2: Salad moved above Vegetables in Settings › Aisles › Walking order is the one stored order the list's
 * Arrange items uses: it shows on the list, in the shared text and the widget's order, survives finishing the shop,
 * a restart and a backup restored elsewhere, and reaches the watch snapshot.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WalkingOrderGroupsTest {
    private val collectors = CoroutineScope(Dispatchers.Main)
    private val models = ArrayList<androidx.lifecycle.ViewModel>()

    @After
    fun stop() {
        collectors.cancel()
        for (m in models) m.viewModelScope.cancel()
        Thread.sleep(300)
    }

    private fun AppContainer.add(vararg names: String) =
        runBlocking {
            awaitReady()
            for (n in names) repository.manualAdd(n, QtyBase.PRESENCE, null)
        }

    private fun produce(
        repo: ListRepository,
        list: com.robert4lyf.hypershop.core.merge.ShoppingList,
    ) = runBlocking { repo.inWalkingOrder(list.liveItems.filter { it.aisle == AisleCode.PRODUCE }) }.map { it.displayName }

    private fun AppContainer.produce() = produce(repository, runBlocking { repository.snapshot() })

    @Test
    fun saladAboveVegetablesEverywhereAndForGood() {
        val c = TestContainer.create()
        c.add("onion", "carrot", "lettuce")
        assertEquals("the catalogue's order first", listOf("onion", "carrot", "lettuce"), c.produce())

        val settings = SettingsViewModel(c).also { models += it }
        collectors.launch { settings.ui.collect {} }
        val groups = await(settings.ui) { it.loaded && it.groups[AisleCode.PRODUCE] != null }.groups.getValue(AisleCode.PRODUCE)
        // Every group the aisle can have, not only those on the list, and things in no group last.
        assertEquals(listOf("Vegetables", "Salad", "Herbs", "Fruit", null), groups)
        settings.moveGroupTo(AisleCode.PRODUCE, "Salad", 0)
        await(settings.ui) { it.groups[AisleCode.PRODUCE]?.first() == "Salad" }

        // The list, its shared text and the widget's order.
        val list = ListViewModel(c).also { models += it }
        collectors.launch { list.ui.collect {} }
        await(list.ui) { u ->
            u.aisles.firstOrNull {
                it.first == AisleCode.PRODUCE
            }?.second?.map { it.name } == listOf("lettuce", "onion", "carrot")
        }
        val text = runBlocking { list.shoppingText() }
        assertTrue(text, text.indexOf("lettuce") < text.indexOf("onion"))
        assertEquals(listOf("lettuce", "onion", "carrot"), c.produce())

        // Finished, and on the list again next time: still first.
        runBlocking {
            for (i in c.repository.snapshot().liveItems) c.repository.tick(i.id)
            c.repository.finishShopping()
        }
        c.add("carrot", "onion", "lettuce")
        assertEquals(listOf("lettuce", "carrot", "onion"), c.produce())

        // A restart: a repository with nothing cached, over the same database.
        val restarted = ListRepository(c.db, c.catalog)
        assertEquals(listOf("lettuce", "carrot", "onion"), produce(restarted, runBlocking { restarted.snapshot() }))

        // Backed up and restored on another phone.
        val file = runBlocking { c.backups.snapshot() }
        val other = TestContainer.create()
        runBlocking {
            other.awaitReady()
            other.backups.restore(file)
        }
        assertEquals(listOf("lettuce", "carrot", "onion"), other.produce())
    }

    @Test
    fun theWatchSnapshotFollowsTheGroupOrder() =
        runBlocking {
            val c = TestContainer.create()
            c.add("onion", "lettuce")
            c.repository.arrangeWith(1) {
                val known = c.catalog.groupsIn(AisleCode.PRODUCE) + null
                it.movedGroup(AisleCode.PRODUCE, known, known, "Salad", -1) { g -> c.catalog.groupRank(AisleCode.PRODUCE, g) }
            }
            val fake = c.watchLink as FakeWatchLink
            c.watchSync.handle(WatchCodec.encode(WatchMessage.Hello(c.repository.listEpoch().toInt(), 77, 0, 1_760_000_000, 0)))
            withTimeout(10_000) { while (fake.sent.isEmpty()) delay(20) }
            val names = fake.sent.mapNotNull { WatchCodec.decodePage(it) }.flatMap { it.items }.map { it.name.lowercase() }
            assertTrue(names.toString(), names.indexOfFirst { "lettuce" in it } < names.indexOfFirst { "onion" in it })
        }
}
