package com.robert4lyf.hypershop.ui.recipes

import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.catalog.EntryEdit
import com.robert4lyf.hypershop.catalog.SaveResult
import com.robert4lyf.hypershop.core.merge.UseState
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.RuleParser
import com.robert4lyf.hypershop.importing.ImportInput
import com.robert4lyf.hypershop.importing.ImportResult
import com.robert4lyf.hypershop.testutil.CHILLI
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.await
import com.robert4lyf.hypershop.testutil.readyJob
import com.robert4lyf.hypershop.ui.common.UiMessage
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
        // Removed from the library; the list keeps its items.
        vm.removeFromLibrary(again)
        await(vm.ui) { it.library.isEmpty() && it.uses.size == 1 }
    }

    @Test
    fun removingFromTheListOrTheLibraryCanBeUndone() {
        val c = TestContainer.create()
        c.add(*CHILLI)
        val vm = RecipesViewModel(c).shown()
        val got = mutableListOf<UiMessage>()
        val watching = CoroutineScope(Dispatchers.Main).launch { vm.events.collect { got += it } }
        val use = await(vm.ui) { it.uses.isNotEmpty() && it.library.isNotEmpty() }.uses.single()
        vm.remove(use)
        await(vm.ui) { it.uses.isEmpty() && got.size == 1 }
        assertEquals("Removed Weeknight chilli from the list", got[0].text)
        runBlocking { got[0].undo!!.invoke() }
        // Back as it was: the use, its items and the library's "on your list".
        val back = await(vm.ui) { it.uses.size == 1 && it.library.single().onList }
        assertEquals(use.progress, back.uses.single().progress)
        assertTrue(runBlocking { c.repository.snapshot() }.itemByKey("cat:onion") != null)
        vm.removeFromLibrary(back.library.single())
        await(vm.ui) { it.library.isEmpty() && got.size == 2 }
        assertEquals("Removed Weeknight chilli from your recipes", got[1].text)
        runBlocking { got[1].undo!!.invoke() }
        assertEquals("Weeknight chilli", await(vm.ui) { it.library.size == 1 }.library.single().title)
        watching.cancel()
    }

    @Test
    fun undoOfRemoveSaysSoWhenTheRecipeIsGone() {
        val c = TestContainer.create()
        c.add(*CHILLI)
        val vm = RecipesViewModel(c).shown()
        val got = mutableListOf<UiMessage>()
        val watching = CoroutineScope(Dispatchers.Main).launch { vm.events.collect { got += it } }
        val use = await(vm.ui) { it.uses.isNotEmpty() }.uses.single()
        vm.remove(use)
        await(vm.ui) { it.uses.isEmpty() && got.size == 1 }
        // The copy went meanwhile, as the start-up purge does once it is forgotten and old.
        runBlocking { c.db.dao().deleteRecipe(use.recipeId!!) }
        runBlocking { got[0].undo!!.invoke() }
        assertEquals("Can't undo: the list has changed since", await(vm.ui) { got.size == 2 }.let { got[1].text })
        assertTrue(vm.ui.value.uses.isEmpty())
        assertTrue(runBlocking { c.repository.snapshot() }.liveItems.isEmpty())
        watching.cancel()
    }

    @Test
    fun aSynonymSavedInTheLibraryIsFoundBySearch() {
        val c = TestContainer.create()
        c.add(*CHILLI)
        val vm = RecipesViewModel(c).shown()
        await(vm.ui) { it.library.isNotEmpty() }
        vm.onSearchText("zorble")
        Snapshot.sendApplyNotifications()
        assertTrue(await(vm.ui) { it.terms == listOf("zorble") }.library.isEmpty())
        val onion = EntryEdit.of(c.catalog.byId.getValue("onion"))
        assertTrue(runBlocking { c.library.save(onion.copy(synonyms = onion.synonyms + "zorble")) } is SaveResult.Saved)
        // Searched again: the index was built before the edit and must not be reused.
        vm.clearSearch()
        Snapshot.sendApplyNotifications()
        await(vm.ui) { it.terms.isEmpty() }
        vm.onSearchText("zorble")
        Snapshot.sendApplyNotifications()
        val hits = await(vm.ui) { it.terms == listOf("zorble") && it.library.isNotEmpty() }
        assertEquals(listOf("Weeknight chilli"), hits.library.map { it.title })
    }

    @Test
    fun aCookbookRecipeKeepsItsBookAndPage() {
        val c = TestContainer.create()
        // As "+ › Photo of a recipe" leaves it once Claude has read the photo.
        val raw = listOf("4 eggs", "400g tin chopped tomatoes")
        val result =
            ImportResult(
                url = null,
                title = "Shakshuka",
                servings = 2,
                locale = UnitLocale.METRIC,
                method = "PHOTO",
                lines = RuleParser().parseLines(raw),
                rawLines = raw,
                book = "Simple",
            )
        val review = ReviewViewModel(c, runBlocking { c.importJobs.enqueueReady(ImportInput("", "Photo of a recipe"), result) })
        val ui = (await(review.state) { it is ReviewState.Ready } as ReviewState.Ready).ui
        assertTrue(ui.cookbook)
        assertEquals("Simple", ui.book)
        // The page, added on the review screen.
        review.setBook("Ottolenghi Simple", " 142 ")
        await(review.state) { it is ReviewState.Ready && it.ui.page == "142" && it.ui.book == "Ottolenghi Simple" }
        review.confirm()
        await(review.state) { it is ReviewState.Added }

        val vm = RecipesViewModel(c).shown()
        val loaded = await(vm.ui) { it.library.isNotEmpty() && it.uses.isNotEmpty() }
        assertEquals("Ottolenghi Simple · p. 142", loaded.library.single().bookRef)
        assertEquals("Ottolenghi Simple · p. 142", loaded.uses.single().bookRef)
        // The photo form starts with the book used last.
        assertEquals("Ottolenghi Simple", loaded.lastBook)
        // Searching by the book finds it.
        vm.onSearchText("ottolenghi")
        Snapshot.sendApplyNotifications()
        await(vm.ui) { it.terms.isNotEmpty() && it.library.singleOrNull()?.title == "Shakshuka" }

        // Edit keeps the book and page.
        vm.edit(loaded.uses.single())
        val edit = ReviewViewModel(c, nextReview(vm))
        val reopened = (await(edit.state) { it is ReviewState.Ready } as ReviewState.Ready).ui
        assertEquals("Ottolenghi Simple" to "142", reopened.book to reopened.page)
    }

    @Test
    fun addedByHandCanBeEditedLikeARecipe() {
        val c = TestContainer.create()
        runBlocking {
            for (line in listOf("2 onions", "500g beef mince")) {
                val p = c.ruleParser.parse(line)
                c.repository.manualAdd(p.name, p.shopBase(UnitLocale.METRIC), p.shopUnit)
            }
        }
        val vm = RecipesViewModel(c).shown()
        val byHand = await(vm.ui) { it.uses.isNotEmpty() }.uses.single()
        assertEquals(null, byHand.recipeId)
        vm.edit(byHand)
        val edit = ReviewViewModel(c, nextReview(vm))
        val keys = { s: ReviewState -> (s as? ReviewState.Ready)?.ui?.aisles?.flatMap { it.second }?.map { it.key }.orEmpty() }
        val ready = (await(edit.state) { it is ReviewState.Ready } as ReviewState.Ready).ui
        assertTrue(ready.editing && ready.byHand && !ready.cookbook)
        assertEquals(setOf("cat:onion", "cat:beef_mince"), keys(edit.state.value).toSet())
        edit.removeLine(edit.sheet("cat:onion")!!.lines.single().id)
        await(edit.state) { "cat:onion" !in keys(it) }
        edit.addLine("1 leek")
        await(edit.state) { "cat:leek" in keys(it) }
        edit.confirm()
        await(edit.state) { it is ReviewState.Added }
        // Still hand-added: one use with no recipe, and nothing in the library.
        val after = await(vm.ui) { u -> u.uses.singleOrNull()?.progress?.toBuy == 2 }
        assertEquals(null, after.uses.single().recipeId)
        assertEquals("Added by hand", after.uses.single().title)
        assertTrue(after.library.isEmpty())
        val list = runBlocking { c.repository.snapshot() }
        assertEquals(setOf("cat:beef_mince", "cat:leek"), list.liveItems.map { it.mergeKey }.toSet())
        // A new typed item joins the same card.
        runBlocking {
            val p = c.ruleParser.parse("milk")
            c.repository.manualAdd(p.name, p.shopBase(UnitLocale.METRIC), p.shopUnit)
        }
        assertEquals(3, await(vm.ui) { u -> u.uses.singleOrNull()?.progress?.toBuy == 3 }.uses.single().progress.toBuy)
    }

    @Test
    fun aRecipeStaysOnceEverythingIsBoughtAndHandAddedGoes() {
        val c = TestContainer.create()
        c.add(*CHILLI)
        runBlocking {
            val p = c.ruleParser.parse("bin bags")
            c.repository.manualAdd(p.name, p.shopBase(UnitLocale.METRIC), p.shopUnit)
        }
        val vm = RecipesViewModel(c).shown()
        await(vm.ui) { it.uses.size == 2 }
        // Everything bought: tick it all, then Finish shopping.
        runBlocking {
            for (item in c.repository.snapshot().liveItems) c.repository.toggle(item.id)
            c.repository.finishShopping()
        }
        val after = await(vm.ui) { u -> u.uses.size == 1 && u.uses.single().progress.state == UseState.ALL_BOUGHT }
        // The recipe is still there to cook; the hand-added card went with its items.
        assertEquals("Weeknight chilli", after.uses.single().title)
        // And "Remove from list" still clears it.
        vm.remove(after.uses.single())
        await(vm.ui) { it.uses.isEmpty() }
    }

    @Test
    fun editingASavedRecipeChangesTheLibraryAndNotTheList() {
        val c = TestContainer.create()
        c.add(*CHILLI)
        val vm = RecipesViewModel(c).shown()
        val start = await(vm.ui) { it.uses.isNotEmpty() && it.library.isNotEmpty() }
        val items = runBlocking { c.repository.snapshot() }.liveItems.size
        vm.editSaved(start.library.single())
        val edit = ReviewViewModel(c, nextReview(vm))
        // On the list already, but an edit adds nothing: no "add again?".
        val ready = await(edit.state) { it is ReviewState.Ready } as ReviewState.Ready
        assertTrue(ready.ui.editing)
        // Nothing goes on the list from here, so no in-stock or servings: just the ingredients.
        assertTrue(ready.ui.savedOnly)
        assertTrue(ready.ui.summary, ready.ui.summary.endsWith(" ingredients"))
        edit.addLine("1 lime")
        await(edit.state) { s -> s is ReviewState.Ready && s.ui.changed }
        edit.confirm()
        assertEquals("Weeknight chilli", (await(edit.state) { it is ReviewState.Added } as ReviewState.Added).edited)
        val after = await(vm.ui) { u -> u.library.singleOrNull()?.lines == start.library.single().lines + 1 }
        assertEquals(start.library.single().key, after.library.single().key)
        // The list is as it was, and nothing waits to be reviewed.
        assertEquals(items, runBlocking { c.repository.snapshot() }.liveItems.size)
        assertTrue(runBlocking { c.importJobs.ready.first() }.isEmpty())
    }

    @Test
    fun leavingAnEditOrALibraryReAddLeavesNothingReadyToReview() {
        val c = TestContainer.create()
        c.add(*CHILLI)
        val vm = RecipesViewModel(c).shown()
        val start = await(vm.ui) { it.uses.isNotEmpty() && it.library.isNotEmpty() }
        // Edit, then Back (which is Cancel on these).
        vm.edit(start.uses.single())
        val edit = ReviewViewModel(c, nextReview(vm))
        await(edit.state) { it is ReviewState.Ready }
        assertTrue(edit.isThrowaway)
        edit.cancel()
        await(edit.state) { it is ReviewState.Cancelled }
        runBlocking { edit.discardForGood() }
        // "Add" from the library, then Back.
        vm.addFromLibrary(start.library.single())
        val again = ReviewViewModel(c, nextReview(vm))
        // Already on the list: the review asks first, as it does in the app.
        await(again.state) { it is ReviewState.AlreadyOnList }
        again.addAgain()
        await(again.state) { it is ReviewState.Ready }
        assertTrue(again.isThrowaway)
        again.cancel()
        await(again.state) { it is ReviewState.Cancelled }
        runBlocking { again.discardForGood() }
        assertTrue(runBlocking { c.importJobs.ready.first() }.isEmpty())
        // The recipe on the list is untouched.
        assertEquals(1, await(vm.ui) { it.uses.size == 1 }.uses.size)
    }

    @Test
    fun renamingShowsOnTheListAndInTheLibrary() {
        val c = TestContainer.create()
        c.add(*CHILLI)
        runBlocking {
            val p = c.ruleParser.parse("bin bags")
            c.repository.manualAdd(p.name, p.shopBase(UnitLocale.METRIC), p.shopUnit)
        }
        val vm = RecipesViewModel(c).shown()
        val start = await(vm.ui) { it.uses.size == 2 && it.library.size == 1 }
        val chilli = start.uses.first { it.recipeId != null }
        val byHand = start.uses.first { it.recipeId == null }
        assertEquals("Added by hand", byHand.title)
        // From the list: the library shows it too.
        vm.rename(chilli, "  Friday chilli ")
        val renamed =
            await(vm.ui) { u -> u.library.single().title == "Friday chilli" && u.uses.any { it.title == "Friday chilli" } }
        assertEquals(chilli.useId, renamed.uses.first { it.title == "Friday chilli" }.useId)
        // From the library: the list shows it too.
        vm.renameSaved(renamed.library.single(), "Chilli")
        await(vm.ui) { u -> u.uses.any { it.title == "Chilli" } && u.library.single().title == "Chilli" }
        // "Added by hand" becomes "Household", and stays so after Finish shopping.
        vm.rename(byHand, "Household")
        await(vm.ui) { u -> u.uses.any { it.recipeId == null && it.title == "Household" } }
        runBlocking {
            c.repository.finishShopping()
            val p = c.ruleParser.parse("washing up liquid")
            c.repository.manualAdd(p.name, p.shopBase(UnitLocale.METRIC), p.shopUnit)
        }
        assertEquals("Household", runBlocking { c.repository.manualTitle() })
        // Blank puts the old name back.
        vm.rename(byHand, " ")
        await(vm.ui) { u -> u.uses.any { it.recipeId == null && it.title == "Added by hand" } }
    }

    @Test
    fun aRecipeOnTheListOpensWithItsIngredientsInStockMarked() {
        val c = TestContainer.create()
        c.add(*CHILLI)
        runBlocking {
            val p = c.ruleParser.parse("bin bags")
            c.repository.manualAdd(p.name, p.shopBase(UnitLocale.METRIC), p.shopUnit)
        }
        val vm = RecipesViewModel(c).shown()
        val uses = await(vm.ui) { it.uses.size == 2 }.uses
        vm.openUse(uses.first { it.recipeId != null })
        val d = await(vm.useDetail) { it != null }!!
        val lines = d.sections.flatMap { it.second }
        assertEquals(CHILLI.toList(), lines.map { it.first })
        // Salt, olive oil and the soured-cream garnish were left out as in stock.
        assertEquals(3, lines.count { it.second })
        vm.closeUse()
        // Hand-added: the items as they are.
        vm.openUse(uses.first { it.recipeId == null })
        val hand = await(vm.useDetail) { it?.use?.recipeId == null && it != null }!!
        assertEquals(listOf("bin bags"), hand.sections.single().second.map { it.first.lowercase() })
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
    fun searchByWhatYouHaveOrWhatYouFancy() {
        val c = TestContainer.create()
        c.add(*CHILLI)
        val vm0 = RecipesViewModel(c)
        runBlocking { c.readyJob("250g chestnut mushrooms", "1 onion", "300g risotto rice", title = "Mushroom risotto") }.let { job ->
            val r = ReviewViewModel(c, job)
            await(r.state) { it is ReviewState.Ready }
            r.confirm()
            await(r.state) { it is ReviewState.Added }
        }
        val vm = vm0.shown()
        await(vm.ui) { it.library.size == 2 }
        // Half a word already finds it, while typing.
        vm.onSearchText("mush")
        Snapshot.sendApplyNotifications()
        assertEquals(listOf("Mushroom risotto"), await(vm.ui) { it.terms == listOf("mush") }.library.map { it.title })
        vm.onSearchText("mushroom")
        Snapshot.sendApplyNotifications()
        val hits = await(vm.ui) { it.terms == listOf("mushroom") }
        assertEquals(listOf("Mushroom risotto"), hits.library.map { it.title })
        assertEquals("chestnut mushroom", hits.library.single().uses)
        // What you have in: Enter makes a chip; the recipe using both comes first.
        vm.commitSearch()
        vm.onSearchText("onion")
        Snapshot.sendApplyNotifications()
        val both = await(vm.ui) { it.terms == listOf("mushroom", "onion") }
        assertEquals(listOf("Mushroom risotto", "Weeknight chilli"), both.library.map { it.title })
        vm.clearSearch()
        Snapshot.sendApplyNotifications()
        assertEquals(2, await(vm.ui) { it.terms.isEmpty() }.library.size)
    }

    @Test
    fun usesCountInStockAndRemove() {
        val c = TestContainer.create()
        c.add(*CHILLI)
        val vm = RecipesViewModel(c).shown()
        val use = await(vm.ui) { it.uses.isNotEmpty() }.uses.single()
        assertEquals("Weeknight chilli", use.title)
        assertEquals(4, use.servings)
        // Salt, olive oil and the soured-cream garnish were left out as in stock.
        assertEquals(3, use.inStockCount)
        assertTrue(runBlocking { c.repository.snapshot() }.itemByKey("cat:salt") == null)
        vm.remove(use)
        await(vm.ui) { it.uses.isEmpty() }
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
