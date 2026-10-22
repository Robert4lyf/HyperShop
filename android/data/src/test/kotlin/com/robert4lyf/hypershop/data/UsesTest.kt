package com.robert4lyf.hypershop.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import com.robert4lyf.hypershop.core.merge.ApplyResult
import com.robert4lyf.hypershop.core.merge.UseState
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.RuleParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private val parser = RuleParser()

/** Step 5.2c: removing a use, Undo of an import, rescaling, finish shopping, soft delete with undo. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UsesTest {
    @get:Rule val r = DbRule()

    @Test
    fun importThenUndoLeavesTheDatabaseEqualApartFromRevs() =
        runBlocking {
            r.import("2 onions", "1 carrot")
            r.repo.tick(r.repo.snapshot().itemByKey("cat:onion")!!.id)
            val before = r.dump()
            val applied = r.import("1 onion", "200 ml milk", "3 garlic cloves", url = "https://example.com/second")
            assertTrue(r.dump() != before)
            r.repo.undoImport(applied.useId)
            assertEquals(before, r.dump())
        }

    @Test
    fun removeUseKeepsOtherRecipes() =
        runBlocking {
            val a = r.import("2 onions")
            r.import("1 onion", url = "https://example.com/b")
            r.repo.removeUse(a.useId)
            val l = r.repo.snapshot()
            assertEquals(Rational.ONE, l.total(l.itemByKey("cat:onion")!!.id)[BaseKey.ITEMS])
        }

    @Test
    fun rescale() =
        runBlocking {
            val a = r.import("2 onions", "Salt, to taste", servings = 4)
            r.repo.rescaleUse(a.useId, 8)
            val l = r.repo.snapshot()
            assertEquals(Rational.of(4), l.total(l.itemByKey("cat:onion")!!.id)[BaseKey.ITEMS])
        }

    @Test
    fun finishShoppingAndUndo() =
        runBlocking {
            r.import("2 onions", "1 carrot")
            val onion = r.repo.snapshot().itemByKey("cat:onion")!!.id
            r.repo.tick(onion)
            val before = r.dump()
            r.now += 1000
            val stamp = r.repo.finishShopping()
            assertEquals(listOf("cat:carrot"), r.repo.snapshot().liveItems.map { it.mergeKey })
            assertEquals(1, r.db.dao().deletedBefore(Long.MAX_VALUE).size)
            r.repo.undoDelete(stamp)
            assertEquals(before, r.dump())
            assertTrue(r.db.dao().deletedBefore(Long.MAX_VALUE).isEmpty())
        }

    @Test
    fun finishCountsWhatItRemoved() =
        runBlocking {
            r.import("2 onions", "1 carrot", "200 ml milk")
            val l = r.repo.snapshot()
            r.repo.tick(l.itemByKey("cat:onion")!!.id)
            r.repo.tick(l.itemByKey("cat:carrot")!!.id)
            assertEquals(2, r.repo.finish().removed)
            assertEquals(0, r.repo.finish().removed)
        }

    @Test
    fun usesFromALoadedListMatchAFreshRead() =
        runBlocking {
            val a = r.import("2 onions", "Salt, to taste")
            r.import("1 carrot", url = "https://example.com/b")
            r.repo.manualAdd("milk", QtyBase.PRESENCE, null)
            val l = r.repo.snapshot()
            val uses = r.repo.usesOnList(l)
            assertEquals(r.repo.usesOnList(), uses)
            assertEquals(3, uses.size)
            val links = r.repo.useLinks(l, uses.map { it.useId })
            assertEquals(uses.map { it.title to it.url }, uses.map { links.getValue(it.useId).let { k -> k.title to k.url } })
            assertTrue("Added by hand" in links.values.map { it.title })
            assertEquals(1, uses.single { it.useId == a.useId }.inStockCount)
        }

    @Test
    fun softDeleteUndoAndPurge() =
        runBlocking {
            r.import("1 carrot")
            val id = r.repo.snapshot().itemByKey("cat:carrot")!!.id
            val stamp = r.repo.softDelete(id)
            assertTrue(r.repo.snapshot().liveItems.isEmpty())
            r.repo.undoDelete(stamp)
            assertEquals(1, r.repo.snapshot().liveItems.size)
            r.repo.clearAll()
            // A new carrot while the old one is soft-deleted: allowed (live-only uniqueness).
            r.import("2 carrots", url = "https://example.com/c")
            assertEquals(1, r.repo.snapshot().liveItems.size)
            r.now += 8L * 24 * 3600 * 1000
            r.repo.purgeDeleted()
            assertEquals(1, r.repo.snapshot().items.size)
        }

    @Test
    fun clearingOrRemovingItemsByHandTakesTheRecipeOffTheListAndUndoBringsItBack() =
        runBlocking {
            val a = r.import("2 onions", "1 carrot")
            assertEquals(listOf(a.useId), r.repo.usesOnList().map { it.useId })
            val stamp = r.repo.clearAll()
            assertTrue(r.repo.usesOnList().isEmpty())
            r.repo.undoDelete(stamp)
            assertEquals(listOf(a.useId), r.repo.usesOnList().map { it.useId })
            // One item removed by hand: the recipe stays with the other; the last one takes it off.
            val l = r.repo.snapshot()
            r.repo.softDelete(l.itemByKey("cat:onion")!!.id)
            assertEquals(1, r.repo.usesOnList().single().progress.toBuy)
            r.repo.softDelete(l.itemByKey("cat:carrot")!!.id)
            assertTrue(r.repo.usesOnList().isEmpty())
        }

    @Test
    fun aBoughtRecipeStaysAsAReminderUntilItsItemsArePurged() =
        runBlocking {
            val a = r.import("2 onions")
            r.repo.tick(r.repo.snapshot().itemByKey("cat:onion")!!.id)
            r.repo.finishShopping()
            val shown = r.repo.usesOnList().single()
            assertEquals(a.useId, shown.useId)
            assertEquals(UseState.ALL_BOUGHT, shown.progress.state)
            // The same day: nothing to purge, nothing archived, the list not touched.
            val rev = r.repo.snapshot().listRev
            r.repo.purgeDeleted()
            assertEquals(1, r.repo.usesOnList().size)
            assertEquals(rev, r.repo.snapshot().listRev)
            r.now += 8L * 24 * 3600 * 1000
            r.repo.purgeDeleted()
            assertTrue(r.repo.usesOnList().isEmpty())
            assertTrue(r.repo.snapshot().uses.getValue(a.useId).archivedAt != null)
        }

    @Test
    fun removeUseThenRestoreBringsBackItemsTicksAndContributions() =
        runBlocking {
            val a = r.import("2 onions", "1 carrot")
            val l0 = r.repo.snapshot()
            r.repo.tick(l0.itemByKey("cat:carrot")!!.id)
            val before = r.dump()
            val removed = r.repo.removeUse(a.useId)!!
            assertTrue(r.repo.snapshot().liveItems.isEmpty())
            assertEquals(2, removed.removed.items.size)
            assertEquals(1, removed.removed.ticks.size)
            assertTrue(r.repo.restoreUse(removed))
            assertEquals(before, r.dump())
            val l = r.repo.snapshot()
            assertTrue(l.isDone(l.itemByKey("cat:carrot")!!.id))
            // Restoring twice does nothing.
            assertTrue(!r.repo.restoreUse(removed))
        }

    @Test
    fun restoreJoinsAnItemReAddedMeanwhile() =
        runBlocking {
            val a = r.import("2 onions")
            val removed = r.repo.removeUse(a.useId)!!
            r.import("1 onion", url = "https://example.com/b")
            assertTrue(r.repo.restoreUse(removed))
            val l = r.repo.snapshot()
            val onion = l.itemByKey("cat:onion")!!
            assertEquals(1, l.liveItems.size)
            assertEquals(Rational.of(3), l.total(onion.id)[BaseKey.ITEMS])
        }

    @Test
    fun forgottenRecipesComeBackOnUndoAndGoAtPurge() =
        runBlocking {
            val a = r.import("2 onions", url = "https://example.com/soup")
            val recipeId = r.repo.snapshot().uses[a.useId]!!.recipeId!!
            r.repo.removeUse(a.useId)
            val key = r.db.dao().recipe(recipeId)!!.libraryKey!!
            val copies = r.repo.removeFromLibrary(key)
            assertEquals(1, copies.size)
            assertTrue(r.db.dao().recipesByKey(key).all { it.lastAddedAt == null })
            r.repo.restoreToLibrary(copies)
            assertTrue(r.db.dao().recipesByKey(key).all { it.lastAddedAt != null })
            r.repo.removeFromLibrary(key)
            r.now += 8L * 24 * 3600 * 1000
            r.repo.purgeDeleted()
            assertTrue(r.db.dao().recipesByKey(key).isEmpty())
        }

    @Test
    fun aReviewCopyNotYetConfirmedSurvivesTheStartUpPurge() =
        runBlocking {
            // An edit or a re-add from the library: stored with its entry's key, not added yet.
            val stored =
                r.repo.storeRecipe(
                    null, "Soup", 2, UnitLocale.METRIC, "WEB", ParsedBy.RULES, null, false, listOf(parser.parse("1 onion")),
                    libraryKey = "r:1",
                )
            r.repo.purgeDeleted()
            assertTrue(r.db.dao().recipe(stored.recipe.id) != null)
            assertTrue(r.repo.apply(r.repo.preview(stored, 2)) is ApplyResult.Applied)
            // Only once Undo of "remove from your recipes" is long past.
            r.now += 8L * 24 * 3600 * 1000
            r.repo.purgeDeleted()
            assertTrue(r.db.dao().recipe(stored.recipe.id) != null)
            r.repo.removeUse(r.repo.snapshot().uses.keys.single())
            r.repo.removeFromLibrary("r:1")
            r.repo.purgeDeleted()
            assertTrue(r.db.dao().recipe(stored.recipe.id) == null)
        }

    @Test
    fun restoreFailsOnceTheRecipeCopyIsGone() =
        runBlocking {
            val a = r.import("2 onions")
            val recipeId = r.repo.snapshot().uses[a.useId]!!.recipeId!!
            val removed = r.repo.removeUse(a.useId)!!
            r.db.dao().deleteRecipe(recipeId)
            assertFalse(r.repo.restoreUse(removed))
            assertTrue(r.repo.snapshot().uses.isEmpty())
            assertTrue(r.repo.snapshot().liveItems.isEmpty())
        }

    @Test
    fun restoreAfterACatalogueEditJoinsTheItemAtItsNewKey() =
        runBlocking {
            val a = r.import("1 courgette")
            val removed = r.repo.removeUse(a.useId)!!
            // "Always use aubergine instead", saved in the ingredient library meanwhile.
            val edited = CatalogLoader.default.copy()
            edited.replace("edited", edited.entries.map { if (it.id == "courgette") it.copy(replaceWith = "aubergine") else it })
            val repo = ListRepository(r.db, edited, clock = { r.now })
            repo.manualAdd("aubergine", QtyBase.of(Rational.of(2), null, UnitLocale.METRIC), null)
            assertTrue(repo.restoreUse(removed))
            val l = repo.snapshot()
            assertEquals(listOf("cat:aubergine"), l.liveItems.map { it.mergeKey })
            assertEquals(Rational.of(3), l.total(l.liveItems.single().id)[BaseKey.ITEMS])
            assertEquals(2, l.uses.size)
            // The restored item's old id still finds it (a watch op in flight).
            assertEquals(l.liveItems.single().id, l.resolve(removed.removed.items.single().id)?.id)
        }

    @Test
    fun undoOfARemovalAfterTheSharedRowHasGoneBringsTheRowBack() =
        runBlocking {
            // Release A: found by ListInvariantsTest. A's onion row is shared with B; A is removed,
            // then B, which empties the row and takes it off. Undo of A pointed at a row that was gone.
            val a = r.import("2 onions", "1 carrot")
            val b = r.import("1 onion", url = "https://example.com/b")
            val removedA = r.repo.removeUse(a.useId)!!
            r.repo.removeUse(b.useId)!!
            assertTrue(r.repo.snapshot().liveItems.isEmpty())
            assertTrue(r.repo.restoreUse(removedA))
            val l = r.repo.snapshot()
            assertEquals(setOf("cat:onion", "cat:carrot"), l.liveItems.map { it.mergeKey }.toSet())
            assertEquals(Rational.of(2), l.total(l.itemByKey("cat:onion")!!.id)[BaseKey.ITEMS])
        }

    @Test
    fun undoOfARemovalAfterAnUntickBringsTheItemBackStillNeeded() =
        runBlocking {
            val a = r.import("2 onions")
            val b = r.import("1 onion", url = "https://example.com/b")
            val onion = r.repo.snapshot().itemByKey("cat:onion")!!.id
            r.repo.tick(onion)
            val removedA = r.repo.removeUse(a.useId)!!
            // The tick covered both recipes, so it stays with B; then it is undone.
            r.repo.untick(onion)
            assertTrue(r.repo.restoreUse(removedA))
            val l = r.repo.snapshot()
            assertTrue(l.contributionsOf(onion).all { it.coveredBy == null })
            assertEquals(Rational.of(3), l.total(onion)[BaseKey.ITEMS])
            assertEquals(1, l.progress(b.useId).toBuy)
        }
}
