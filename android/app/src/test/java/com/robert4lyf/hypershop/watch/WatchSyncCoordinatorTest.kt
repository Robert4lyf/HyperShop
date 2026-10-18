package com.robert4lyf.hypershop.watch

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.sync.PayloadCap
import com.robert4lyf.hypershop.core.sync.PayloadSize
import com.robert4lyf.hypershop.core.sync.WatchCodec
import com.robert4lyf.hypershop.core.sync.WatchMessage
import com.robert4lyf.hypershop.core.sync.WatchOp
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.testutil.TestContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Step 7.2: what the phone sends back for each thing the watch says. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WatchSyncCoordinatorTest {
    private val install = 77

    private fun AppContainer.fake() = watchLink as FakeWatchLink

    /** As "Add item" does it. */
    private suspend fun AppContainer.addTyped(lines: List<String>) {
        for (line in lines) {
            val p = ruleParser.parse(line)
            repository.manualAdd(p.name, p.shopBase(UnitLocale.METRIC), p.shopUnit)
        }
    }

    private fun AppContainer.pages() = fake().sent.map { requireNotNull(WatchCodec.decodePage(it)) }

    private suspend fun AppContainer.hello() {
        val epoch = repository.listEpoch().toInt()
        watchSync.handle(WatchCodec.encode(WatchMessage.Hello(epoch, install, 0, 1_760_000_000, 0)))
    }

    @Test
    fun helloGetsTheWholeListInPagesUnderTheCap() =
        runBlocking {
            val c = TestContainer.create()
            c.addTyped(List(30) { "item number $it" })
            c.watchSync.cap = PayloadCap(bytes = 384, maxPages = 16)
            c.hello()
            val pages = c.pages()
            assertTrue("several pages", pages.size > 1)
            assertTrue(c.fake().sent.all { PayloadSize.of(it) <= 384 })
            assertEquals(pages.size, pages.first().pages)
            assertEquals(30, pages.sumOf { it.items.size } + pages.first().more)
            assertEquals(c.repository.snapshot().listRev.toInt(), pages.first().listRev)
        }

    @Test
    fun aTickIsAppliedAndAnsweredWithTheNewList() =
        runBlocking {
            val c = TestContainer.create()
            c.addTyped(listOf("2 onions", "milk"))
            c.hello()
            val first = c.pages()
            val onion = first.flatMap { it.items }.first { it.name.contains("onion") }
            c.fake().sent.clear()

            val epoch = c.repository.listEpoch().toInt()
            c.watchSync.handle(
                WatchCodec.encode(WatchMessage.Ops(epoch, install, listOf(WatchOp(1, onion.id, true, first[0].listRev, 1_760_000_010)))),
            )
            assertTrue(c.repository.snapshot().isDone(onion.id.toLong()))
            val reply = c.pages()
            assertEquals(1, reply.first().ackedThrough)
            assertTrue("a full snapshot", reply.first().pages > 0)
            assertTrue(reply.flatMap { it.items }.first { it.id == onion.id }.done)

            // The same batch again (its reply was lost): acked, nothing applied twice, ack-only.
            c.fake().sent.clear()
            c.watchSync.handle(
                WatchCodec.encode(WatchMessage.Ops(epoch, install, listOf(WatchOp(1, onion.id, true, first[0].listRev, 1_760_000_010)))),
            )
            val dup = c.pages().single()
            assertEquals(0, dup.pages)
            assertEquals(1, dup.ackedThrough)
            assertEquals(1, c.db.dao().ticks().size)
        }

    @Test
    fun aListChangeIsPushedOnceAndNotForARevTheWatchHas() =
        runBlocking {
            val c = TestContainer.create()
            c.addTyped(listOf("milk"))
            c.hello()
            c.fake().sent.clear()
            c.watchSync.push(force = false)
            assertTrue("already sent this listRev", c.fake().sent.isEmpty())
            c.addTyped(listOf("bread"))
            c.watchSync.push(force = false)
            assertTrue(c.pages().flatMap { it.items }.any { it.name.contains("bread", ignoreCase = true) })
            c.fake().sent.clear()
            c.watchSync.push(force = true)
            assertTrue("APP_READY re-sends", c.fake().sent.isNotEmpty())
        }

    @Test
    fun probeIsAnsweredAndGarbageIsNot() =
        runBlocking {
            val c = TestContainer.create()
            c.watchSync.handle(mapOf("v" to 1, "t" to "prb", "ep" to 0, "ii" to install, "n" to 500))
            val reply = c.fake().sent.single()
            assertEquals("prb", reply["t"])
            assertTrue(PayloadSize.of(reply) in 500..510)

            c.fake().sent.clear()
            for (junk in listOf(
                mapOf("v" to 1, "t" to "ops", "ep" to 0, "ii" to install, "op" to "x"),
                mapOf("t" to 5),
                emptyMap<String, Any>(),
            )) {
                c.watchSync.handle(junk)
            }
            assertTrue(c.fake().sent.isEmpty())
        }
}
