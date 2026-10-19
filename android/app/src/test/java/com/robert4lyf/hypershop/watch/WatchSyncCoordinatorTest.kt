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
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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

    /** What the coordinator sends from its own scope arrives a little later. */
    private suspend fun AppContainer.awaitSent(
        what: String,
        ok: (List<Map<String, Any>>) -> Boolean,
    ) {
        try {
            withTimeout(10_000) { while (!ok(fake().sent)) delay(20) }
        } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
            throw AssertionError("$what: not sent in time; sent ${fake().sent.size} payloads")
        }
    }

    private fun List<Map<String, Any>>.names() = mapNotNull { WatchCodec.decodePage(it) }.flatMap { it.items }.map { it.name }

    @Test
    fun startPushesOnAppReadyAndOnEveryListChange() =
        runBlocking {
            val c = TestContainer.create()
            c.addTyped(listOf("milk"))
            c.hello()
            c.fake().sent.clear()

            // The fake is APP_READY from the start: the last snapshot goes again at once.
            c.watchSync.start()
            c.awaitSent("APP_READY push") { it.isNotEmpty() }
            assertTrue("a full snapshot", c.pages().first().pages > 0)

            // A list change is pushed without the watch asking.
            c.fake().sent.clear()
            c.addTyped(listOf("bread"))
            c.awaitSent("listRev push") { sent -> sent.names().any { it.contains("bread", ignoreCase = true) } }

            // No watch, no push; the watch back, the change it missed.
            c.fake().sent.clear()
            c.fake().setState(WatchLinkState.DISCONNECTED)
            c.addTyped(listOf("eggs"))
            delay(300)
            assertTrue("nothing while disconnected", c.fake().sent.isEmpty())
            c.fake().setState(WatchLinkState.APP_READY)
            c.awaitSent("push on reconnect") { sent -> sent.names().any { it.contains("egg", ignoreCase = true) } }
        }

    @Test
    fun aPushAfterTheProcessRestartedGoesToTheInstallOnRecord() =
        runBlocking {
            val c = TestContainer.create()
            c.addTyped(listOf("milk"))
            c.hello()

            // A new process: the coordinator remembers nothing, the database does.
            val link = FakeWatchLink()
            val restarted =
                WatchSyncCoordinator(
                    link = link,
                    repository = c.repository,
                    catalog = c.catalog,
                    units = { c.settings.units.first() },
                    scope = c.applicationScope,
                )
            assertNull(restarted.install)
            restarted.push(force = true)
            assertEquals(install, restarted.install)
            val pages = link.sent.map { requireNotNull(WatchCodec.decodePage(it)) }
            assertTrue("the list went without a hello", pages.first().pages > 0)
            assertTrue(pages.flatMap { it.items }.any { it.name.contains("milk", ignoreCase = true) })

            // No install on record (a new epoch forgets them all): nothing to push to.
            c.repository.checkEpoch(com.robert4lyf.hypershop.data.EpochMarker(java.nio.file.Files.createTempDirectory("epoch").toFile()))
            val fresh = FakeWatchLink()
            WatchSyncCoordinator(fresh, c.repository, c.catalog, { c.settings.units.first() }, c.applicationScope).push(force = true)
            assertTrue(fresh.sent.isEmpty())
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
            // The list-change push that follows the tick has nothing to add: the reply was it.
            c.fake().sent.clear()
            c.watchSync.push(force = false)
            assertTrue("the tick's listRev went once", c.fake().sent.isEmpty())

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
