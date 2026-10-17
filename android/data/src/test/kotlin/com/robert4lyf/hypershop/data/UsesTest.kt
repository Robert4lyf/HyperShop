package com.robert4lyf.hypershop.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.Rational
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

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
}
