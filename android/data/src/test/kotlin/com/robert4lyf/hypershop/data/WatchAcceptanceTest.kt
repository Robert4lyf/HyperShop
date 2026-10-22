package com.robert4lyf.hypershop.data

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
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
import java.io.File

/**
 * Release A: the watch's offline life against the phone's list, through the same
 * transaction the coordinator calls. The phone is the authority: a tick from the
 * wrist covers only what the wrist had seen, a batch arrives once however often it
 * is sent, and nothing from before a restore lands on the restored list.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WatchAcceptanceTest {
    @get:Rule val r = DbRule()

    private val install = 42

    private suspend fun epoch() = r.repo.listEpoch().toInt()

    private suspend fun seen() = r.repo.snapshot().listRev.toInt()

    private suspend fun id(key: String) = r.repo.snapshot().itemByKey(key)!!.id.toInt()

    private suspend fun ops(vararg o: WatchOp) = r.repo.applyWatch(WatchMessage.Ops(epoch(), install, o.toList()))

    private suspend fun ticksOn(key: String) = r.repo.snapshot().let { l -> l.ticks.count { it.itemId == l.itemByKey(key)!!.id } }

    @Test
    fun anOfflineBatchAfterARecipeWasAddedCoversOnlyWhatTheWatchSaw() =
        runBlocking {
            val a = r.import("2 onions", "1 carrot")
            r.repo.applyWatch(WatchMessage.Hello(epoch(), install, 0, 1_760_000_000, 0))
            val s = seen()
            val onion = id("cat:onion")
            val carrot = id("cat:carrot")
            // Out of range: the phone adds a recipe that also wants an onion, and milk.
            val b = r.import("1 onion", "200 ml milk", url = "https://example.com/b")
            // Back in range, the wrist's queue arrives as one batch: onion, carrot, an Undo of the carrot, the carrot again.
            val reply =
                ops(
                    WatchOp(1, onion, true, s, 10),
                    WatchOp(2, carrot, true, s, 11),
                    WatchOp(3, carrot, false, s, 12),
                    WatchOp(4, carrot, true, s, 13),
                )
            assertEquals(4, reply.ackedThrough)
            val l = r.repo.snapshot()
            // The onion the wrist saw is bought; the one added since is still needed, by the recipe that added it.
            assertTrue(l.isPartial(onion.toLong()))
            assertEquals(0, l.progress(a.useId).toBuy)
            assertEquals(2, l.progress(b.useId).toBuy)
            // The carrot ends bought, by one tick: the Undo and the re-tick left no second purchase behind.
            assertTrue(l.isDone(carrot.toLong()))
            assertEquals(1, ticksOn("cat:carrot"))
            assertFalse(l.isDone(l.itemByKey("cat:milk")!!.id))
        }

    @Test
    fun anIngredientAlreadyBoughtThatIsNeededAgainIsNotAlreadyBought() =
        runBlocking {
            r.import("2 onions")
            val onion = id("cat:onion")
            r.repo.tick(onion.toLong())
            val s = seen()
            val b = r.import("1 onion", url = "https://example.com/b")
            // A tick from the wrist that has not seen the new recipe yet (sent before its list caught up)
            // covers nothing new: PARTIAL, and the new onion stays needed.
            val reply = ops(WatchOp(1, onion, true, s, 1))
            assertEquals(listOf(1 to OpResult.PARTIAL), reply.ackExceptions)
            assertEquals(1, r.repo.snapshot().progress(b.useId).toBuy)
            assertEquals(1, ticksOn("cat:onion"))
            // Once it has seen the list, its tick covers the new onion too.
            assertTrue(ops(WatchOp(2, onion, true, seen(), 2)).ackExceptions.isEmpty())
            assertTrue(r.repo.snapshot().isDone(onion.toLong()))
        }

    @Test
    fun theWristCannotUndoAPurchaseMadeOnThePhone() =
        runBlocking {
            r.import("2 onions")
            val onion = id("cat:onion")
            r.repo.tick(onion.toLong())
            val reply = ops(WatchOp(1, onion, false, seen(), 1))
            assertEquals(listOf(1 to OpResult.NOOP), reply.ackExceptions)
            assertTrue(r.repo.snapshot().isDone(onion.toLong()))
        }

    @Test
    fun aBatchSentTwiceLandsOnce() =
        runBlocking {
            r.import("2 onions", "1 carrot")
            val s = seen()
            val batch = arrayOf(WatchOp(1, id("cat:onion"), true, s, 1), WatchOp(2, id("cat:carrot"), true, s, 2))
            ops(*batch)
            val rev = r.repo.snapshot().listRev
            // The ack was lost; the wrist sends the same batch again, twice.
            repeat(2) { assertEquals(2, ops(*batch).ackedThrough) }
            assertEquals(rev, r.repo.snapshot().listRev)
            assertEquals(1, ticksOn("cat:onion"))
            assertEquals(1, ticksOn("cat:carrot"))
        }

    @Test
    fun ticksFromBeforeARestoreDoNotLandOnTheRestoredList() =
        runBlocking {
            r.import("2 onions")
            val backup = r.repo.exportBackup()
            val oldEpoch = epoch()
            val onion = id("cat:onion")
            val s = seen()
            val marker =
                EpochMarker(File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "m-${System.nanoTime()}"))
            assertEquals(emptyList<String>(), r.repo.restoreBackup(backup, marker))
            // The wrist still holds a tick from the old list: refused, and the restored onion is still to buy.
            val reply = r.repo.applyWatch(WatchMessage.Ops(oldEpoch, install, listOf(WatchOp(1, onion, true, s, 1))))
            assertEquals(listOf(1 to OpResult.EPOCH), reply.ackExceptions)
            assertFalse(r.repo.snapshot().liveItems.any { r.repo.snapshot().isDone(it.id) })
            // A hello under the old epoch is told to start again: a full snapshot of the restored list.
            assertTrue(r.repo.applyWatch(WatchMessage.Hello(oldEpoch, install, 0, 1_760_000_000, 0)).fullSnapshot)
        }

    @Test
    fun manyTicksInARowLandInOrder() =
        runBlocking {
            r.import("2 onions", "1 carrot", "200 ml milk", "6 eggs", "1 lemon")
            val s = seen()
            val ids = r.repo.snapshot().liveItems.map { it.id.toInt() }
            // Each tapped twice and once more: tick, untick, tick, as fast as the wrist sends.
            val seq = generateSequence(1) { it + 1 }.iterator()
            val batch =
                ids.flatMap {
                    listOf(WatchOp(seq.next(), it, true, s, 1), WatchOp(seq.next(), it, false, s, 2), WatchOp(seq.next(), it, true, s, 3))
                }
            assertEquals(batch.size, ops(*batch.toTypedArray()).ackedThrough)
            val l = r.repo.snapshot()
            assertTrue(l.liveItems.all { l.isDone(it.id) })
            assertEquals(ids.size, l.ticks.size)
        }
}
