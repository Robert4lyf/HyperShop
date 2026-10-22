package com.robert4lyf.hypershop.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Release B phase 2: shopping history and Buy again. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BuyAgainTest {
    @get:Rule val r = DbRule()

    private val buy by lazy { BuyAgain(r.repo) }

    private suspend fun typed(
        name: String,
        n: Int = 1,
        unit: QtyUnit? = null,
    ) = r.repo.manualAdd(name, QtyBase.of(Rational.of(n.toLong()), unit, UnitLocale.METRIC), unit)

    /** Ticks everything and finishes: one trip. */
    private suspend fun shop() {
        for (i in r.repo.snapshot().liveItems) r.repo.tick(i.id)
        r.now += 7L * 24 * 3600 * 1000
        r.repo.finish()
    }

    @Test
    fun finishingKeepsATripAndUndoTakesItBack() =
        runBlocking {
            typed("milk", 2)
            r.import("2 onions", "1 onion")
            for (i in r.repo.snapshot().liveItems) r.repo.tick(i.id)
            val f = r.repo.finish()
            val trip = r.db.dao().trips().single()
            val items = r.db.dao().tripItems(listOf(trip.id)).associateBy { it.mergeKey }
            // One onion row for the trip, however many lines wanted it; bought for a recipe, not by hand.
            assertEquals(setOf("cat:milk", "cat:onion"), items.keys)
            assertTrue(items.getValue("cat:milk").byHand)
            assertFalse(items.getValue("cat:onion").byHand)
            r.repo.undoDelete(f.stamp)
            assertTrue(r.db.dao().trips().isEmpty())
        }

    @Test
    fun anItemBoughtByHandOnTwoTripsIsARegularAndRecipeIngredientsAreNot() =
        runBlocking {
            typed("milk", 2)
            typed("bread")
            r.import("2 onions")
            shop()
            assertTrue(buy.view().regulars.isEmpty())
            typed("milk", 1)
            r.import("1 onion", url = "https://example.com/b")
            r.import("3 onions", url = "https://example.com/c")
            shop()
            val v = buy.view()
            assertEquals(listOf("milk"), v.regulars.map { it.name })
            // The last time's amount.
            assertEquals(Rational.ONE, v.regulars.single().qty[BaseKey.ITEMS])
        }

    @Test
    fun aRegularOnTheListIsShownThereAndAddingItDoesNotDoubleIt() =
        runBlocking {
            repeat(2) {
                typed("milk", 2)
                shop()
            }
            typed("milk", 1)
            val milk = buy.view().regulars.single()
            assertTrue(milk.onList)
            assertTrue(buy.add(listOf(milk)).isEmpty())
            val l = r.repo.snapshot()
            assertEquals(Rational.ONE, l.total(l.itemByKey("cat:milk")!!.id)[BaseKey.ITEMS])
        }

    @Test
    fun addAllIsOneWriteAndUndoTakesBackOnlyWhatItAdded() =
        runBlocking {
            repeat(2) {
                typed("milk", 2)
                typed("bread")
                typed("eggs", 6)
                shop()
            }
            typed("bread")
            val before = r.repo.snapshot().liveItems.map { it.mergeKey }.toSet()
            val v = buy.view()
            assertEquals(3, v.regulars.size)
            val added = buy.add(v.regulars)
            assertEquals(2, added.size)
            val l = r.repo.snapshot()
            assertEquals(Rational.of(2), l.total(l.itemByKey("cat:milk")!!.id)[BaseKey.ITEMS])
            assertEquals(Rational.ONE, l.total(l.itemByKey("cat:bread")!!.id)[BaseKey.ITEMS])
            buy.undo(added)
            assertEquals(before, r.repo.snapshot().liveItems.map { it.mergeKey }.toSet())
        }

    @Test
    fun undoNeverTakesBackSomethingBoughtSince() =
        runBlocking {
            repeat(2) {
                typed("milk", 2)
                shop()
            }
            val added = buy.add(buy.view().regulars)
            r.repo.tick(r.repo.snapshot().itemByKey("cat:milk")!!.id)
            buy.undo(added)
            assertTrue(r.repo.snapshot().itemByKey("cat:milk") != null)
        }

    @Test
    fun repeatLastShopOffersItsHandAddedItemsNotOnTheList() =
        runBlocking {
            typed("milk", 2)
            typed("toilet roll", 9)
            r.import("2 onions")
            shop()
            typed("milk")
            val v = buy.view()
            assertEquals(listOf("toilet roll"), v.lastShop.map { it.name })
            buy.add(v.lastShop)
            val l = r.repo.snapshot()
            assertEquals(Rational.of(9), l.total(l.itemByKey(v.lastShop.single().mergeKey)!!.id)[BaseKey.ITEMS])
            assertTrue(l.itemByKey("cat:onion") == null)
        }

    @Test
    fun pinnedHiddenAndClearedHistory() =
        runBlocking {
            repeat(2) {
                typed("milk")
                typed("bread")
                shop()
            }
            val (bread, milk) = buy.view().regulars.sortedBy { it.name }
            buy.hide(milk)
            assertEquals(listOf("bread"), buy.view().regulars.map { it.name })
            buy.pin(bread)
            buy.clearHistory()
            val v = buy.view()
            // The pin was chosen and stays; the trips and the hide go.
            assertEquals(listOf("bread"), v.regulars.map { it.name })
            assertTrue(v.regulars.single().pinned)
            assertFalse(v.hasHistory)
            assertTrue(r.db.dao().trips().isEmpty())
        }

    @Test
    fun onlyTheLatestTripsAreKept() =
        runBlocking {
            repeat(ListRepository.MAX_TRIPS + 3) {
                typed("milk")
                shop()
            }
            assertEquals(ListRepository.MAX_TRIPS, r.db.dao().trips().size)
        }

    @Test
    fun aRecipeItemWhoseAmountWasRaisedIsNotARegular() =
        runBlocking {
            repeat(2) {
                r.import("2 onions", url = "https://example.com/$it")
                val onion = r.repo.snapshot().itemByKey("cat:onion")!!.id
                r.repo.editQuantity(onion, QtyBase.of(Rational.of(3), null, UnitLocale.METRIC))
                shop()
            }
            assertTrue(buy.view().regulars.isEmpty())
        }
}
