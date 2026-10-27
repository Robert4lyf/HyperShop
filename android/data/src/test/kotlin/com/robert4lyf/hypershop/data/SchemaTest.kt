package com.robert4lyf.hypershop.data

import android.database.sqlite.SQLiteConstraintException
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.AisleCode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Step 5.2a: schema v1, seeding, the constraints Room annotations cannot express, and the epoch marker. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SchemaTest {
    @get:Rule val r = DbRule()

    @get:Rule val tmp = TemporaryFolder()

    @Test
    fun seeds() =
        runBlocking {
            val dao = r.db.dao()
            assertEquals(AisleCode.entries.map { it.name }, dao.aisles().map { it.code })
            assertEquals(AisleCode.entries.map { it.name }, dao.aisleOrder(1).map { it.aisleCode })
            assertEquals(
                setOf("cat:salt", "cat:black_pepper", "cat:olive_oil", "cat:vegetable_oil", "cat:water"),
                dao.staples().map { it.key }.toSet(),
            )
            val meta = dao.meta()!!
            assertEquals(1L, meta.listRev)
            assertTrue(meta.listEpoch in 1..Int.MAX_VALUE.toLong())
            assertEquals(AisleCode.entries, r.repo.aisleOrder())
        }

    @Test
    fun mergeKeyIsUniqueAmongLiveItemsOnly() {
        val w = r.db.openHelper.writableDatabase
        w.execSQL("INSERT INTO Item VALUES (1,'cat:onion',NULL,'onion','onion','PRODUCE',0,1,500,0,NULL)")
        w.execSQL("INSERT INTO Item VALUES (2,'cat:onion',NULL,'onion','onion','PRODUCE',0,1,NULL,0,NULL)")
        try {
            w.execSQL("INSERT INTO Item VALUES (3,'cat:onion',NULL,'onion','onion','PRODUCE',0,1,NULL,0,NULL)")
            fail("two live items with one key")
        } catch (e: SQLiteConstraintException) {
            assertTrue(e.message!!.contains("UNIQUE"))
        }
    }

    @Test
    fun recipeUseNeedsARecipeUnlessManual() {
        val w = r.db.openHelper.writableDatabase
        w.execSQL("INSERT INTO RecipeUse VALUES (1,NULL,'MANUAL',1,0,NULL)")
        try {
            w.execSQL("INSERT INTO RecipeUse VALUES (2,NULL,'RECIPE',4,0,NULL)")
            fail("RECIPE use without a recipe")
        } catch (e: Exception) {
            assertTrue(e.message!!.contains("recipeId"))
        }
    }

    @Test
    fun epochMarkerDetectsARestore() =
        runBlocking {
            val marker = EpochMarker(tmp.newFolder())
            assertTrue("first launch writes a marker", r.repo.checkEpoch(marker))
            val epoch = r.repo.listEpoch()
            assertFalse(r.repo.checkEpoch(marker))
            assertEquals(epoch, r.repo.listEpoch())
            r.db.dao().putInstall(WatchInstallEntity(7, 12, 0))
            // A restore: the database comes back, the no-backup marker does not.
            val restored = EpochMarker(tmp.newFolder())
            assertTrue(r.repo.checkEpoch(restored))
            assertTrue(r.repo.listEpoch() != epoch)
            assertEquals(null, r.db.dao().install(7))
        }
}
