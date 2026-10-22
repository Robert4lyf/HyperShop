package com.robert4lyf.hypershop.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.merge.ShoppingList
import com.robert4lyf.hypershop.core.merge.UseProgress
import com.robert4lyf.hypershop.core.merge.UseState
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.sync.WatchMessage
import com.robert4lyf.hypershop.core.sync.WatchOp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * Step 9.1: one recipe's shopping, as every screen reads it ([ShoppingList.progress]
 * through [ListRepository.usesOnList]): still needed, bought, partly bought, at home,
 * shared, and what happens to each when the list changes underneath it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class UseProgressTest {
    @get:Rule val r = DbRule()

    private suspend fun progress(useId: Long): UseProgress = r.repo.snapshot().progress(useId)

    private suspend fun shown(useId: Long): UseSummary? = r.repo.usesOnList().firstOrNull { it.useId == useId }

    private suspend fun id(key: String): Long = r.repo.snapshot().itemByKey(key)!!.id

    @Test
    fun everythingOutstanding() =
        runBlocking {
            val a = r.import("2 onions", "1 carrot")
            assertEquals(UseProgress(2, 0, 0, 0), progress(a.useId))
            assertEquals(UseState.STILL_NEEDED, shown(a.useId)!!.progress.state)
        }

    @Test
    fun everythingBoughtStaysAllBoughtThroughFinishShopping() =
        runBlocking {
            val a = r.import("2 onions", "1 carrot")
            r.repo.tick(id("cat:onion"))
            r.repo.tick(id("cat:carrot"))
            assertEquals(UseProgress(0, 0, 2, 0), progress(a.useId))
            r.repo.finishShopping()
            assertEquals(UseState.ALL_BOUGHT, shown(a.useId)!!.progress.state)
        }

    @Test
    fun partlyBought() =
        runBlocking {
            val a = r.import("2 onions", "1 carrot")
            r.repo.tick(id("cat:onion"))
            assertEquals(UseProgress(1, 0, 1, 0), progress(a.useId))
        }

    @Test
    fun aSharedItemCountsOnceForEachRecipe() =
        runBlocking {
            val a = r.import("2 onions", "1 carrot")
            val b = r.import("1 onion", url = "https://example.com/b")
            assertEquals(UseProgress(2, 0, 0, 0), progress(a.useId))
            assertEquals(UseProgress(1, 0, 0, 0), progress(b.useId))
            r.repo.tick(id("cat:onion"))
            assertEquals(UseProgress(1, 0, 1, 0), progress(a.useId))
            assertEquals(UseState.ALL_BOUGHT, progress(b.useId).state)
        }

    @Test
    fun aRecipeAddedAfterAPurchaseStillNeedsItsShare() =
        runBlocking {
            val a = r.import("2 onions")
            r.repo.tick(id("cat:onion"))
            val b = r.import("1 onion", url = "https://example.com/b")
            // The tick covered the first recipe's two; the second's one is still to buy.
            assertEquals(UseState.ALL_BOUGHT, progress(a.useId).state)
            assertEquals(UseProgress(1, 1, 0, 0), progress(b.useId))
            assertTrue(r.repo.snapshot().isPartial(id("cat:onion")))
        }

    @Test
    fun moreServingsAfterAPurchaseNeedOnlyTheDifference() =
        runBlocking {
            val a = r.import("2 onions", "1 carrot", servings = 4)
            r.repo.tick(id("cat:onion"))
            r.repo.rescaleUse(a.useId, 8)
            val l = r.repo.snapshot()
            val onion = l.itemByKey("cat:onion")!!.id
            // Four onions in all, two of them bought: the tick never grows to cover the new two.
            assertEquals(Rational.of(4), l.total(onion)[BaseKey.ITEMS])
            assertTrue(l.isPartial(onion))
            assertEquals(UseProgress(2, 1, 0, 0), l.progress(a.useId))
            // Back to four servings: the two bought cover it, nothing left to buy for onions.
            r.repo.rescaleUse(a.useId, 4)
            val back = r.repo.snapshot()
            assertTrue(back.isDone(onion))
            assertEquals(Rational.of(2), back.total(onion)[BaseKey.ITEMS])
            assertEquals(UseProgress(1, 0, 1, 0), back.progress(a.useId))
            // Fewer than were bought for: still bought, the amount shrinks.
            r.repo.rescaleUse(a.useId, 2)
            assertTrue(r.repo.snapshot().isDone(onion))
            assertEquals(Rational.ONE, r.repo.snapshot().total(onion)[BaseKey.ITEMS])
        }

    @Test
    fun moreServingsAfterFinishingPutTheExtraBackOnTheListAndFewerTakeItOffAgain() =
        runBlocking {
            val a = r.import("2 onions", servings = 4)
            r.repo.tick(id("cat:onion"))
            val stamp = r.repo.finishShopping()
            assertTrue(r.repo.snapshot().liveItems.isEmpty())
            // More servings: the one extra onion is back on the list, on a row of its own; the two
            // bought stay with the finished trip.
            r.repo.rescaleUse(a.useId, 6)
            var l = r.repo.snapshot()
            val extra = l.itemByKey("cat:onion")!!
            assertEquals(Rational.ONE, l.total(extra.id)[BaseKey.ITEMS])
            assertTrue(l.contributionsOf(extra.id).all { it.coveredBy == null })
            assertEquals(UseProgress(1, 0, 1, 0), l.progress(a.useId))
            assertEquals(UseState.STILL_NEEDED, shown(a.useId)!!.progress.state)
            // Back to four: nothing extra is needed, and no bought onion is left showing on the list.
            r.repo.rescaleUse(a.useId, 4)
            l = r.repo.snapshot()
            assertTrue(l.liveItems.isEmpty())
            assertEquals(UseState.ALL_BOUGHT, l.progress(a.useId).state)
            assertEquals(1, l.ticks.size)
            // Up again and down past what was bought: still nothing on the list, the bought amount shrinks.
            r.repo.rescaleUse(a.useId, 8)
            assertEquals(Rational.of(2), r.repo.snapshot().let { it.total(it.itemByKey("cat:onion")!!.id)[BaseKey.ITEMS] })
            r.repo.rescaleUse(a.useId, 2)
            l = r.repo.snapshot()
            assertTrue(l.liveItems.isEmpty())
            assertEquals(UseState.ALL_BOUGHT, l.progress(a.useId).state)
            // Undo of the finish brings the trip's row back, bought, at the amount it scaled to.
            r.repo.undoDelete(stamp)
            l = r.repo.snapshot()
            val onion = l.itemByKey("cat:onion")!!
            assertTrue(l.isDone(onion.id))
            assertEquals(Rational.ONE, l.total(onion.id)[BaseKey.ITEMS])
        }

    @Test
    fun moreServingsAfterFinishingJoinAnOnionAlreadyBackOnTheList() =
        runBlocking {
            val a = r.import("2 onions", servings = 4)
            r.repo.tick(id("cat:onion"))
            r.repo.finishShopping()
            // Another recipe has put onions on the list since.
            r.import("1 onion", url = "https://example.com/b")
            val live = id("cat:onion")
            r.repo.rescaleUse(a.useId, 6)
            var l = r.repo.snapshot()
            assertEquals(live, l.itemByKey("cat:onion")!!.id)
            assertEquals(Rational.of(2), l.total(live)[BaseKey.ITEMS])
            // Back down: the other recipe's onion stays, the extra goes.
            r.repo.rescaleUse(a.useId, 4)
            l = r.repo.snapshot()
            assertEquals(Rational.ONE, l.total(l.itemByKey("cat:onion")!!.id)[BaseKey.ITEMS])
        }

    @Test
    fun everythingAtHomeIsNothingToBuyNotAllBought() =
        runBlocking {
            val a = r.import("Salt, to taste")
            assertEquals(UseProgress(0, 0, 0, 0), progress(a.useId))
            val s = shown(a.useId)!!
            assertEquals(UseState.NOTHING_TO_BUY, s.progress.state)
            assertEquals(1, s.inStockCount)
            // Some at home, the rest bought: all bought.
            val b = r.import("2 onions", "Salt, to taste", url = "https://example.com/b")
            r.repo.tick(id("cat:onion"))
            assertEquals(UseState.ALL_BOUGHT, shown(b.useId)!!.progress.state)
        }

    @Test
    fun aRecipeRemovedIsOffEveryScreenAndUndoBringsItsProgressBack() =
        runBlocking {
            val a = r.import("2 onions", "1 carrot")
            r.repo.tick(id("cat:onion"))
            val before = progress(a.useId)
            val removal = r.repo.removeUse(a.useId)!!
            assertEquals(null, shown(a.useId))
            assertTrue(r.repo.restoreUse(removal))
            assertEquals(before, shown(a.useId)!!.progress)
        }

    @Test
    fun takenOffUnboughtIsNotAllBought() =
        runBlocking {
            val a = r.import("2 onions", "1 carrot")
            r.repo.clearAll()
            assertEquals(UseState.DROPPED, progress(a.useId).state)
            assertEquals(null, shown(a.useId))
            // One bought, the other taken off: shopping is over, but not "all bought".
            val b = r.import("2 onions", "1 carrot", url = "https://example.com/b")
            r.repo.tick(id("cat:onion"))
            r.repo.softDelete(id("cat:carrot"))
            assertEquals(UseState.BOUGHT_THE_REST, shown(b.useId)!!.progress.state)
        }

    @Test
    fun undoingAPurchase() =
        runBlocking {
            val a = r.import("2 onions")
            r.repo.tick(id("cat:onion"))
            r.repo.untick(id("cat:onion"))
            assertEquals(UseProgress(1, 0, 0, 0), progress(a.useId))
        }

    @Test
    fun aWatchTickFromBeforeAListChangeCoversOnlyWhatItSaw() =
        runBlocking {
            val a = r.import("2 onions")
            val epoch = r.repo.listEpoch().toInt()
            val seen = r.repo.snapshot().listRev.toInt()
            val b = r.import("1 onion", url = "https://example.com/b")
            val onion = id("cat:onion").toInt()
            r.repo.applyWatch(WatchMessage.Ops(epoch, 42, listOf(WatchOp(1, onion, true, seen, 1_760_000_010))))
            assertEquals(UseState.ALL_BOUGHT, progress(a.useId).state)
            assertEquals(UseProgress(1, 1, 0, 0), progress(b.useId))
        }

    @Test
    fun aWeekAfterwardsTheBoughtAndTheAtHomeRecipesAreArchived() =
        runBlocking {
            val a = r.import("2 onions")
            val b = r.import("Salt, to taste", url = "https://example.com/b")
            r.repo.tick(id("cat:onion"))
            r.repo.finishShopping()
            assertEquals(setOf(a.useId, b.useId), r.repo.usesOnList().map { it.useId }.toSet())
            r.now += ShoppingList.SEVEN_DAYS + 1
            r.repo.purgeDeleted()
            assertTrue(r.repo.usesOnList().isEmpty())
        }

    @Test
    fun aRestoredListReadsTheSame() =
        runBlocking {
            val a = r.import("2 onions", "1 carrot")
            val b = r.import("Salt, to taste", url = "https://example.com/b")
            r.repo.tick(id("cat:onion"))
            val before = r.repo.usesOnList().associate { it.useId to it.progress }
            val backup = r.repo.exportBackup()
            val fresh = DbRule().also { it.open() }
            try {
                val marker =
                    EpochMarker(
                        File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "m-${System.nanoTime()}"),
                    )
                assertEquals(emptyList<String>(), fresh.repo.restoreBackup(backup, marker))
                assertEquals(before, fresh.repo.usesOnList().associate { it.useId to it.progress })
                assertEquals(setOf(a.useId, b.useId), before.keys)
            } finally {
                fresh.close()
            }
        }
}
