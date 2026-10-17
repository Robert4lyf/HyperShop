package com.robert4lyf.hypershop.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.sync.OpResult
import com.robert4lyf.hypershop.core.sync.WatchMessage
import com.robert4lyf.hypershop.core.sync.WatchOp
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Step 5.2d: catalogue re-key, and watch ops applied in one transaction. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WatchAndRekeyTest {
    @get:Rule val r = DbRule()

    private val install = 42

    private suspend fun epoch() = r.repo.listEpoch().toInt()

    private suspend fun ops(vararg o: WatchOp) = r.repo.applyWatch(WatchMessage.Ops(epoch(), install, o.toList()))

    private suspend fun seen() = r.repo.snapshot().listRev.toInt()

    @Test
    fun tickAckDuplicateAndUntick() =
        runBlocking {
            r.import("2 onions")
            val id = r.repo.snapshot().itemByKey("cat:onion")!!.id.toInt()
            r.repo.applyWatch(WatchMessage.Hello(epoch(), install, 0, 1_760_000_000, 0))
            val s = seen()
            val reply = ops(WatchOp(1, id, true, s, 1_760_000_010))
            assertEquals(1, reply.ackedThrough)
            assertTrue(reply.fullSnapshot)
            assertTrue(r.repo.snapshot().isDone(id.toLong()))
            // The same batch again: re-acked, not re-applied.
            val dup = ops(WatchOp(1, id, true, s, 1_760_000_010))
            assertEquals(1, dup.ackedThrough)
            assertFalse(dup.fullSnapshot)
            assertEquals(1, r.db.dao().ticks().size)
            ops(WatchOp(2, id, false, seen(), 1_760_000_020))
            assertFalse(r.repo.snapshot().isDone(id.toLong()))
            assertTrue(r.db.dao().ticks().isEmpty())
        }

    @Test
    fun staleTickIsPartialAndGapIsIgnored() =
        runBlocking {
            r.import("2 onions")
            val id = r.repo.snapshot().itemByKey("cat:onion")!!.id.toInt()
            val s = seen()
            r.import("1 onion", url = "https://example.com/2")
            // Unknown install: the batch's first seq sets the baseline.
            assertEquals(3, ops(WatchOp(3, id, true, s, 0)).ackedThrough)
            val gap = ops(WatchOp(9, id, true, s, 0))
            assertEquals(3, gap.ackedThrough)
            val partial = r.repo.applyWatch(WatchMessage.Hello(epoch(), install, 0, 0, 3))
            assertEquals(listOf(3 to OpResult.PARTIAL), partial.ackExceptions)
            val l = r.repo.snapshot()
            assertFalse(l.isDone(id.toLong()))
            assertTrue(l.isPartial(id.toLong()))
        }

    @Test
    fun epochMismatchAliasAndRejected() =
        runBlocking {
            r.import("2 onions")
            val id = r.repo.snapshot().itemByKey("cat:onion")!!.id.toInt()
            val wrong = r.repo.applyWatch(WatchMessage.Ops(epoch() + 1, install, listOf(WatchOp(1, id, true, seen(), 0))))
            assertEquals(listOf(1 to OpResult.EPOCH), wrong.ackExceptions)
            assertFalse(r.repo.snapshot().isDone(id.toLong()))
            val rejected = ops(WatchOp(2, 999, true, seen(), 0))
            assertTrue(rejected.ackExceptions.contains(2 to OpResult.REJECTED))
        }

    @Test
    fun rekeyMergesAndRedirectsWatchOps() =
        runBlocking {
            // A name-keyed item from an older catalogue that did not know "zucchini".
            val zId = r.repo.manualAdd("courgette", QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), null)
            val w = r.db.openHelper.writableDatabase
            w.execSQL("INSERT INTO Item VALUES (900,'name:zucchini',NULL,'zucchini','zucchini','OTHER',5,3,NULL,0)")
            val use = r.db.dao().uses().first().id
            val two = "'{\"parts\":{\"count\":\"2\"}}'"
            w.execSQL("INSERT INTO Contribution VALUES (901,$use,NULL,900,$two,0,1,NULL,NULL)")
            w.execSQL("UPDATE Meta SET catalogVersion = 'old', nextId = 1000")
            assertTrue(r.repo.rekeyIfCatalogChanged())
            assertFalse(r.repo.rekeyIfCatalogChanged())
            val l = r.repo.snapshot()
            assertEquals(1, l.liveItems.size)
            assertEquals(zId, l.liveItems.single().id)
            assertEquals(Rational.of(3), l.total(zId).parts["count"])
            // A watch op on the old id lands on the survivor.
            ops(WatchOp(1, 900, true, seen(), 0))
            assertTrue(r.repo.snapshot().isDone(zId))
        }
}
