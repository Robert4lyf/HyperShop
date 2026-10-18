package com.robert4lyf.hypershop.ui.recipes

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.testutil.CHILLI
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.await
import com.robert4lyf.hypershop.testutil.readyJob
import com.robert4lyf.hypershop.ui.list.add
import com.robert4lyf.hypershop.ui.review.ReviewState
import com.robert4lyf.hypershop.ui.review.ReviewViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RecipesViewModelTest {
    /** The tab on screen: ui is only kept current while something collects it. */
    private fun RecipesViewModel.shown(): RecipesViewModel {
        CoroutineScope(Dispatchers.Main).launch { ui.collect {} }
        return this
    }

    private fun nextReview(vm: RecipesViewModel): Long {
        var id: Long? = null
        val job = CoroutineScope(Dispatchers.Main).launch { id = vm.openReview.first() }
        val end = System.currentTimeMillis() + 5000
        while (id == null && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        job.cancel()
        return id ?: throw AssertionError("no review opened")
    }

    @Test
    fun editReopensTheRecipeAndSavingReplacesIt() {
        val c = TestContainer.create()
        val first = ReviewViewModel(c, runBlocking { c.readyJob("2 onions", "1 tsp ground cumin", "200g rice") })
        await(first.state) { it is ReviewState.Ready }
        first.setInStock("cat:cumin", true)
        await(first.state) { it is ReviewState.Ready && it.ui.addCount == 2 }
        first.confirm()
        await(first.state) { it is ReviewState.Added }
        val vm = RecipesViewModel(c).shown()
        val use = await(vm.ui) { it.uses.isNotEmpty() }.uses.single()
        vm.edit(use)
        val edit = ReviewViewModel(c, nextReview(vm))
        val keys = { s: ReviewState -> (s as? ReviewState.Ready)?.ui?.aisles?.flatMap { it.second }?.map { it.key }.orEmpty() }
        val ready = (await(edit.state) { it is ReviewState.Ready } as ReviewState.Ready).ui
        assertTrue(ready.editing)
        // As it went on the list: cumin still in stock.
        assertTrue(ready.aisles.flatMap { it.second }.single { it.key == "cat:cumin" }.inStock)
        edit.addLine("3 carrots")
        await(edit.state) { "cat:carrot" in keys(it) }
        edit.removeLine(edit.sheet("cat:onion")!!.lines.single().id)
        await(edit.state) { "cat:onion" !in keys(it) }
        edit.setTitle("Rice and carrots")
        await(edit.state) { it is ReviewState.Ready && it.ui.title == "Rice and carrots" }
        edit.confirm()
        assertEquals("Rice and carrots", (await(edit.state) { it is ReviewState.Added } as ReviewState.Added).edited)
        // The recipe's items were replaced, not added again.
        val list = runBlocking { c.repository.snapshot() }
        assertEquals(setOf("cat:rice", "cat:carrot"), list.liveItems.map { it.mergeKey }.toSet())
        val after = await(vm.ui) { u -> u.uses.singleOrNull()?.title == "Rice and carrots" }.uses.single()
        assertEquals(1, after.inStockCount)
    }

    @Test
    fun recipesStayInTheLibraryAndCanBeAddedAgain() {
        val c = TestContainer.create()
        c.add(*CHILLI)
        val vm = RecipesViewModel(c).shown()
        val use = await(vm.ui) { it.uses.isNotEmpty() && it.library.isNotEmpty() }.uses.single()
        val saved = vm.ui.value.library.single()
        assertEquals("Weeknight chilli", saved.title)
        assertTrue(saved.onList)
        // Off the list, still in the library.
        vm.remove(use)
        val off = await(vm.ui) { it.uses.isEmpty() && it.library.isNotEmpty() }.library.single()
        assertFalse(off.onList)
        // Added again from the library: one entry, now on the list.
        vm.addFromLibrary(off)
        val review = ReviewViewModel(c, nextReview(vm))
        await(review.state) { it is ReviewState.Ready }
        review.confirm()
        await(review.state) { it is ReviewState.Added }
        val again = await(vm.ui) { it.uses.size == 1 && it.library.singleOrNull()?.onList == true }.library.single()
        assertEquals(off.key, again.key)
        assertTrue(again.lastAddedAt >= off.lastAddedAt)
        // "Add the in-stock items now" doesn't replace the whole recipe in the library.
        vm.addInStockNow(vm.ui.value.uses.single())
        val part = ReviewViewModel(c, nextReview(vm))
        await(part.state) { it is ReviewState.Ready }
        part.confirm()
        await(part.state) { it is ReviewState.Added }
        assertEquals(again.recipeId, await(vm.ui) { it.uses.size == 2 }.library.single().recipeId)
        // Removed from the library; the list keeps its items.
        vm.removeFromLibrary(again)
        await(vm.ui) { it.library.isEmpty() && it.uses.size == 2 }
    }

    @Test
    fun aSavedRecipeOpensWithItsIngredients() {
        val c = TestContainer.create()
        c.add(*CHILLI)
        val vm = RecipesViewModel(c).shown()
        val row = await(vm.ui) { it.library.isNotEmpty() }.library.single()
        vm.openDetail(row)
        val d = await(vm.detail) { it != null }!!
        assertEquals(CHILLI.toList(), d.sections.flatMap { it.second })
        assertEquals(4, d.servings)
        vm.closeDetail()
        assertEquals(null, vm.detail.value)
    }

    @Test
    fun usesInStockNowAndRemove() {
        val c = TestContainer.create()
        c.add(*CHILLI)
        val vm = RecipesViewModel(c).shown()
        val use = await(vm.ui) { it.uses.isNotEmpty() }.uses.single()
        assertEquals("Weeknight chilli", use.title)
        assertEquals(4, use.servings)
        // Salt, olive oil and the soured-cream garnish were left out as in stock.
        assertEquals(3, use.inStockCount)
        vm.addInStockNow(use)
        val review = ReviewViewModel(c, nextReview(vm))
        val ready = await(review.state) { it is ReviewState.Ready } as ReviewState.Ready
        val rows = ready.ui.aisles.flatMap { it.second }
        assertEquals(setOf("cat:salt", "cat:olive_oil", "cat:sour_cream"), rows.map { it.key }.toSet())
        assertTrue("nothing pre-ticked", rows.none { it.inStock })
        review.confirm()
        await(review.state) { it is ReviewState.Added }
        assertTrue(runBlocking { c.repository.snapshot() }.itemByKey("cat:salt") != null)
        val uses = await(vm.ui) { it.uses.size == 2 }.uses
        vm.remove(uses.last())
        await(vm.ui) { it.uses.size == 1 }
        assertTrue(runBlocking { c.repository.snapshot() }.itemByKey("cat:onion") == null)
    }

    @Test
    fun sameUrlAsksAddAgain() {
        val c = TestContainer.create()
        val url = "https://example.com/chilli"
        val first = ReviewViewModel(c, runBlocking { c.readyJob("2 onions", url = url) })
        await(first.state) { it is ReviewState.Ready }
        first.confirm()
        await(first.state) { it is ReviewState.Added }
        val again = ReviewViewModel(c, runBlocking { c.readyJob("2 onions", url = url) })
        val asked = await(again.state) { it is ReviewState.AlreadyOnList } as ReviewState.AlreadyOnList
        assertEquals("Weeknight chilli", asked.title)
        again.addAgain()
        await(again.state) { it is ReviewState.Ready }
        again.confirm()
        await(again.state) { it is ReviewState.Added }
        val l = runBlocking { c.repository.snapshot() }
        assertEquals(com.robert4lyf.hypershop.core.model.Rational.of(4), l.total(l.itemByKey("cat:onion")!!.id).parts["count"])
    }

    @Test
    fun aTypedRecipeIsReviewedLikeAShare() {
        val c = TestContainer.create()
        val vm = RecipesViewModel(c).shown()
        vm.addManual("  Gran's soup ", 6, "2 onions\n\n400 g chopped tomatoes\n")
        val review = ReviewViewModel(c, nextReview(vm))
        val ready = await(review.state) { it is ReviewState.Ready } as ReviewState.Ready
        assertEquals("Gran's soup", ready.ui.title)
        assertEquals(6, ready.ui.servings)
        assertEquals(setOf("cat:onion", "cat:chopped_tomatoes"), ready.ui.aisles.flatMap { it.second }.map { it.key }.toSet())
        review.confirm()
        await(review.state) { it is ReviewState.Added }
        assertEquals("Gran's soup", await(vm.ui) { it.uses.isNotEmpty() }.uses.single().title)
    }
}
