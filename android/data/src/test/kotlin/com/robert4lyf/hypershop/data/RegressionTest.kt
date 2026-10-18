package com.robert4lyf.hypershop.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.merge.ApplyResult
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.RuleParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Bugs found in review, one test each. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RegressionTest {
    @get:Rule val r = DbRule()

    private val two = "'{\"parts\":{\"count\":\"2\"}}'"

    /** A name-keyed item from an older catalogue that did not know "zucchini", with id [id]. */
    private fun nameItem(
        id: Long,
        useId: Long,
    ) {
        val w = r.db.openHelper.writableDatabase
        w.execSQL("INSERT INTO Item VALUES ($id,'name:zucchini',NULL,'zucchini','zucchini','OTHER',5,3,NULL,0)")
        w.execSQL("INSERT INTO Contribution VALUES (${id + 1},$useId,NULL,$id,$two,0,1,NULL,NULL)")
    }

    @Test
    fun rekeyWhenTheNameItemHasTheLowerId() =
        runBlocking {
            r.db.openHelper.writableDatabase.execSQL("UPDATE Meta SET nextId = 500")
            val cId = r.repo.manualAdd("courgette", QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), null)
            val catKey = r.repo.snapshot().items.getValue(cId).mergeKey
            nameItem(10, r.db.dao().uses().first().id)
            r.db.openHelper.writableDatabase.execSQL("UPDATE Meta SET catalogVersion = 'old'")
            assertTrue(r.repo.rekeyIfCatalogChanged())
            assertFalse(r.repo.rekeyIfCatalogChanged())
            val l = r.repo.snapshot()
            assertEquals(10L, l.liveItems.single().id)
            assertEquals(catKey, l.liveItems.single().mergeKey)
            assertEquals(Rational.of(3), l.total(10)[BaseKey.ITEMS])
            assertEquals(10L, l.resolve(cId)!!.id)
        }

    @Test
    fun rekeyMovesStaplesAndYoursWin() =
        runBlocking {
            r.repo.manualAdd("onion", QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), null)
            nameItem(900, r.db.dao().uses().first().id)
            r.db.openHelper.writableDatabase.execSQL("UPDATE Meta SET catalogVersion = 'old', nextId = 1000")
            val key = r.repo.target("zucchini").mergeKey
            assertTrue(key.startsWith("cat:"))
            r.db.dao().putStaple(StapleEntity(key, "SEED"))
            r.db.dao().putStaple(StapleEntity("name:zucchini", "USER"))
            assertTrue(r.repo.rekeyIfCatalogChanged())
            assertEquals(key, r.repo.snapshot().items.getValue(900).mergeKey)
            val staples = r.db.dao().staples().associateBy { it.key }
            assertFalse("name:zucchini" in staples)
            assertEquals("USER", staples.getValue(key).source)
        }

    @Test
    fun alreadyOnListOnlyWhileSomethingIsOnTheList() =
        runBlocking {
            val url = "https://example.com/r"
            r.import("2 onions", url = url)
            assertTrue(r.repo.alreadyOnList(url))
            val stamp = r.repo.clearAll()
            assertFalse(r.repo.alreadyOnList(url))
            r.repo.undoDelete(stamp)
            assertTrue(r.repo.alreadyOnList(url))
            r.repo.tick(r.repo.snapshot().itemByKey("cat:onion")!!.id)
            r.repo.finishShopping()
            assertFalse(r.repo.alreadyOnList(url))
        }

    @Test
    fun setAisleOrderBumpsListRev() =
        runBlocking {
            val before = r.repo.snapshot().listRev
            val order = r.repo.aisleOrder().reversed()
            r.repo.setAisleOrder(order)
            assertTrue(r.repo.snapshot().listRev > before)
            assertEquals(order, r.repo.aisleOrder())
        }

    @Test
    fun undoFinishShoppingRestoresArchivedUses() =
        runBlocking {
            val a = r.import("2 onions")
            r.repo.tick(r.repo.snapshot().itemByKey("cat:onion")!!.id)
            r.now += 8L * 24 * 3600 * 1000
            val stamp = r.repo.finishShopping()
            assertEquals(stamp, r.repo.snapshot().uses.getValue(a.useId).archivedAt)
            r.repo.undoDelete(stamp)
            assertNull(r.repo.snapshot().uses.getValue(a.useId).archivedAt)
        }

    @Test
    fun observedAisleOrderFollowsSettings() =
        runBlocking {
            val order = r.repo.aisleOrder().reversed()
            assertTrue(order != r.repo.observeAisleOrder().first())
            r.repo.setAisleOrder(order)
            assertEquals(order, r.repo.observeAisleOrder().first())
        }

    @Test
    fun unitsChangedBumpsEveryLiveRevAndListRev() =
        runBlocking {
            r.import("2 onions", "1 carrot")
            val before = r.repo.snapshot()
            r.repo.unitsChanged()
            val after = r.repo.snapshot()
            assertEquals(before.listRev + 1, after.listRev)
            for (i in before.liveItems) assertEquals(i.rev + 1, after.items.getValue(i.id).rev)
        }

    @Test
    fun setOverrideIsAUserOverride() =
        runBlocking {
            r.repo.setOverride("cat:onion", AisleCode.FROZEN)
            assertEquals(mapOf("cat:onion" to AisleCode.FROZEN), r.repo.overrides())
            assertEquals("USER", r.db.dao().overrides().single().source)
        }

    @Test
    fun concurrentUsageIsNeverLost() =
        runBlocking {
            coroutineScope {
                repeat(40) { launch(Dispatchers.IO) { r.repo.recordUsage("2026-10-17", "m", 10, 1, 2, 3) } }
            }
            val row = r.db.dao().usageRow("2026-10-17", "m")!!
            assertEquals(40, row.calls)
            assertEquals(400L, row.inputTokens)
            assertEquals(40L, row.outputTokens)
            assertEquals(80L, row.cacheWrite)
            assertEquals(120L, row.cacheRead)
        }

    @Test
    fun jobUpdatesAreSingleStatements() =
        runBlocking {
            val dao = r.db.dao()
            val id = dao.insertJob(ImportJobEntity(0, "QUEUED", "{}", null, null, 1))
            dao.finishJob(id, "READY", "{\"x\":1}", null)
            dao.setJobState(id, "DONE")
            assertEquals(ImportJobEntity(id, "DONE", "{}", "{\"x\":1}", null, 1), dao.job(id))
            // Done stays done: a late worker run can't offer it for review again.
            dao.setJobState(id, "RUNNING")
            dao.finishJob(id, "READY", "{}", null)
            assertEquals("DONE", dao.job(id)?.state)
            // A missing job is a no-op, as before.
            dao.setJobState(id + 1, "DONE")
            assertNull(dao.job(id + 1))
        }

    @Test
    fun addMarksTheJobDoneInTheSameTransaction() =
        runBlocking {
            val dao = r.db.dao()
            val job = dao.insertJob(ImportJobEntity(0, "READY", "{}", "{}", null, 1))
            val stored =
                r.repo.storeRecipe(
                    null, "Soup", 4, UnitLocale.METRIC, "WEB", ParsedBy.RULES, null, false,
                    listOf(
                        RuleParser().parse("2 onions"),
                    ),
                )
            assertTrue(r.repo.apply(r.repo.preview(stored, 4), job) is ApplyResult.Applied)
            assertEquals("DONE", dao.job(job)?.state)
        }

    @Test
    fun aCatalogueChangeBumpsRevsEvenWhenNothingIsRenamed() =
        runBlocking {
            r.import("2 onions")
            val before = r.repo.snapshot()
            r.db.dao().putMeta(r.db.dao().meta()!!.copy(catalogVersion = "old"))
            assertTrue(r.repo.rekeyIfCatalogChanged())
            val after = r.repo.snapshot()
            val id = after.itemByKey("cat:onion")!!.id
            assertTrue(after.items.getValue(id).rev > before.items.getValue(id).rev)
            assertTrue(after.listRev > before.listRev)
        }
}
