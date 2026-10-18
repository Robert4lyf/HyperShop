package com.robert4lyf.hypershop.ui.library

import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.await
import com.robert4lyf.hypershop.ui.common.UiMessage
import com.robert4lyf.hypershop.ui.list.add
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LibraryViewModelTest {
    @Test
    fun anIngredientYouAddTakesOverItemsAlreadyOnTheList() {
        val c = TestContainer.create()
        c.add("3 zorbleberries")
        val before = runBlocking { c.repository.snapshot() }.liveItems.single()
        assertTrue(before.mergeKey.startsWith("name:"))
        val vm = LibraryViewModel(c)
        val error =
            runBlocking {
                vm.save(
                    EntryForm(name = "zorbleberry", plural = "zorbleberries", aisle = AisleCode.NUTS_DRIED_FRUIT, alwaysInStock = false),
                )
            }
        assertNull(error)
        val list = runBlocking { c.repository.snapshot() }
        val item = list.itemByKey("cat:user_zorbleberry")!!
        // Re-keyed, in the aisle you chose, and redrawn (its rev moved on).
        assertEquals(AisleCode.NUTS_DRIED_FRUIT, item.aisle)
        assertTrue(item.rev > before.rev)
        assertEquals(1, list.liveItems.size)
        // And it shows in the library, marked as yours (ui is kept current while collected).
        val watching = CoroutineScope(Dispatchers.Main).launch { vm.ui.collect {} }
        val ui = await(vm.ui) { u -> u.rows.any { it.id == "user_zorbleberry" } }
        assertTrue(ui.rows.single { it.id == "user_zorbleberry" }.custom)
        watching.cancel()
    }

    @Test
    fun searchKeepsWhatYouTypedAndFilters() {
        val c = TestContainer.create()
        val vm = LibraryViewModel(c)
        val watching = CoroutineScope(Dispatchers.Main).launch { vm.ui.collect {} }
        await(vm.ui) { it.rows.size == it.total && it.total > 0 }
        vm.search("corian")
        // The field reads this back at once, before any filtering.
        assertEquals("corian", vm.query)
        Snapshot.sendApplyNotifications()
        val ui = await(vm.ui) { u -> u.rows.size < u.total }
        assertTrue(ui.rows.any { it.id == "coriander" })
        watching.cancel()
    }

    @Test
    fun aNameTakenByAnotherIngredientIsExplained() {
        val c = TestContainer.create()
        val vm = LibraryViewModel(c)
        val error = runBlocking { vm.save(EntryForm(name = "My onion", synonyms = "onion")) }
        assertEquals("\"onion\" is already onion", error)
    }

    @Test
    fun theAisleChipsNarrowTheList() {
        val c = TestContainer.create()
        val vm = LibraryViewModel(c)
        val watching = CoroutineScope(Dispatchers.Main).launch { vm.ui.collect {} }
        await(vm.ui) { it.loaded && it.rows.size == it.total }
        vm.filterAisle(AisleCode.PRODUCE)
        Snapshot.sendApplyNotifications()
        val ui = await(vm.ui) { u -> u.rows.size < u.total }
        assertTrue(ui.rows.all { it.aisle == AisleCode.PRODUCE })
        assertTrue(ui.rows.any { it.id == "onion" })
        watching.cancel()
    }

    @Test
    fun suggestionsAreNamesStartingWithWhatIsTyped() {
        val vm = LibraryViewModel(TestContainer.create())
        assertEquals(emptyList<String>(), vm.suggest(" "))
        val onion = vm.suggest("onio")
        assertEquals("onion", onion.first())
        assertTrue(onion.size <= 5)
        // Typed in full, nothing more to offer for that name.
        assertFalse("onion" in vm.suggest("onion"))
    }

    @Test
    fun resetCanBeUndone() {
        val c = TestContainer.create()
        val vm = LibraryViewModel(c)
        val got = mutableListOf<UiMessage>()
        val watching = CoroutineScope(Dispatchers.Main).launch { vm.events.collect { got += it } }
        val form = runBlocking { vm.form("paprika") }
        assertNull(runBlocking { vm.save(form.copy(synonyms = "sweet paprika, paprika powder")) })
        assertTrue("paprika" in c.library.edits.value)
        vm.reset("paprika")
        await(vm.ui) { "paprika" !in c.library.edits.value && got.size == 2 }
        assertEquals("Reset paprika", got[1].text)
        runBlocking { got[1].undo!!.invoke() }
        assertEquals(listOf("sweet paprika", "paprika powder"), c.library.edits.value["paprika"]?.synonyms)
        watching.cancel()
    }

    @Test
    fun alwaysInStockFollowsTheSwitch() {
        val c = TestContainer.create()
        val vm = LibraryViewModel(c)
        val form = runBlocking { vm.form("paprika") }
        runBlocking { vm.save(form.copy(alwaysInStock = true)) }
        assertTrue("cat:paprika" in runBlocking { c.repository.staples() })
    }
}
