package com.robert4lyf.hypershop.ui.recipes

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.testutil.Photos.saved
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.await
import com.robert4lyf.hypershop.ui.meals.MealsViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * T.3 through the real view models and repository: "Saved" sorts and filters, the
 * planner offers your usual meals first, a meal shows its recipe's notes — and none
 * of it changes what is planned or on the list.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DiscoveryTest {
    private lateinit var c: AppContainer
    private lateinit var dal: String
    private lateinit var chilli: String
    private lateinit var pie: String

    @Before
    fun up() {
        c = TestContainer.create()
        dal = c.saved("Red lentil dal")
        chilli = c.saved("Weeknight chilli")
        pie = c.saved("Fish pie")
        runBlocking {
            c.repository.setRating(dal, 4)
            c.repository.setRating(chilli, 5)
            c.repository.setCookingNotes(pie, "Half the mustard")
            // Planned in earlier weeks: the pie three times, the dal once.
            val now = c.planner.currentWeek()
            repeat(3) { c.planner.plan(now - (it + 1), pie) }
            c.planner.plan(now - 1, dal)
        }
    }

    private fun recipes() = RecipesViewModel(c).also { vm -> CoroutineScope(Dispatchers.Main).launch { vm.ui.collect {} } }

    private fun order(vm: RecipesViewModel) = vm.ui.value.library.map { it.key }

    private fun everything() =
        runBlocking {
            Triple(
                c.repository.snapshot().liveItems.map { it.mergeKey to it.id },
                (-3..1).map { c.planner.week(c.planner.currentWeek() + it).map { m -> m.libraryKey } },
                c.repository.observeRecipeNotes().first(),
            )
        }

    @Test
    fun savedSortsAndFiltersAndChangesNothingElse() {
        val vm = recipes()
        await(vm.ui) { it.library.size == 3 }
        val before = everything()
        assertEquals("newest first", listOf(pie, chilli, dal), order(vm))
        vm.sortBy(LibrarySort.RATING)
        await(vm.ui) { it.sort == LibrarySort.RATING }
        assertEquals(listOf(chilli, dal, pie), order(vm))
        vm.sortBy(LibrarySort.PLANNED)
        await(vm.ui) { it.sort == LibrarySort.PLANNED }
        assertEquals(listOf(pie, dal, chilli), order(vm))
        assertEquals(3, vm.ui.value.library.first().timesPlanned)
        vm.sortBy(LibrarySort.NAME)
        await(vm.ui) { it.sort == LibrarySort.NAME }
        assertEquals(listOf(pie, dal, chilli), order(vm))
        vm.showHighlyRated(true)
        await(vm.ui) { it.highlyRated }
        assertEquals(listOf(dal, chilli), order(vm))
        // Searching keeps the filter, and orders by match.
        vm.onSearchText("chilli")
        // Typed text is Compose state: the search reads it once the write is applied.
        androidx.compose.runtime.snapshots.Snapshot.sendApplyNotifications()
        await(vm.ui) { it.terms.isNotEmpty() }
        assertEquals(listOf(chilli), order(vm))
        assertEquals("the screen's order only", before, everything())
    }

    @Test
    fun aRemovedRecipeLeavesAndARatingChangeMovesItAtOnce() {
        val vm = recipes()
        vm.sortBy(LibrarySort.RATING)
        await(vm.ui) { it.sort == LibrarySort.RATING && it.library.size == 3 }
        runBlocking { c.repository.setRating(dal, 5) }
        // Same five stars as the chilli: the newer of the two first.
        await(vm.ui) { order(vm) == listOf(chilli, dal, pie) }
        runBlocking { c.repository.removeFromLibrary(chilli) }
        await(vm.ui) { order(vm) == listOf(dal, pie) }
    }

    @Test
    fun ratingsComeBackWithARestoreAndTheFilterUsesThem() {
        val file = runBlocking { c.backups.snapshot() }
        runBlocking {
            c.repository.setRating(dal, null)
            c.repository.setRating(chilli, null)
        }
        val vm = recipes()
        vm.showHighlyRated(true)
        await(vm.ui) { it.highlyRated && it.library.isEmpty() }
        runBlocking { c.backups.restore(file) }
        await(vm.ui) { order(vm).toSet() == setOf(dal, chilli) }
    }

    @Test
    fun thePlannerOffersYourUsualFirstAndAMealShowsItsNotes() {
        val vm = MealsViewModel(c).also { m -> CoroutineScope(Dispatchers.Main).launch { m.ui.collect {} } }
        val ui = await(vm.ui) { it.loaded && it.picks.size == 3 }
        assertEquals(listOf(pie, dal, chilli), ui.picks.map { it.key })
        // Planning the chilli this week twice does not move it: only other weeks rank the picks.
        runBlocking {
            c.planner.plan(c.planner.currentWeek(), chilli)
            c.planner.plan(c.planner.currentWeek(), pie)
        }
        val after = await(vm.ui) { it.meals.size == 2 }
        assertEquals(listOf(pie, dal, chilli), after.picks.map { it.key })
        assertEquals("Half the mustard", after.meals.single { it.title == "Fish pie" }.notes)
        assertTrue(after.meals.single { it.title == "Weeknight chilli" }.notes == null)
    }
}
