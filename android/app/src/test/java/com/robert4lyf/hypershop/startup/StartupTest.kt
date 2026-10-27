package com.robert4lyf.hypershop.startup

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.backup.BackupManager
import com.robert4lyf.hypershop.backup.RestoreJournal
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.sync.WatchCodec
import com.robert4lyf.hypershop.core.sync.WatchMessage
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.di.Startup
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.awaitJob
import com.robert4lyf.hypershop.testutil.awaitTrue
import com.robert4lyf.hypershop.ui.HyperShopApp
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import com.robert4lyf.hypershop.watch.FakeWatchLink
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Stands in for the process dying mid-restore: an Error, so no failure handler catches it. */
private class Killed : Error("process killed")

/**
 * Release A, startup: no list read or write — from the UI, the watch or a share —
 * happens before a restore the process died in has been finished, the watch
 * epoch checked and the catalogue re-keyed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class StartupTest {
    @get:Rule val compose = createComposeRule()

    private fun AppContainer.fake() = watchLink as FakeWatchLink

    private fun AppContainer.items() = runBlocking { repository.snapshot() }.liveItems.map { it.mergeKey }.toSet()

    private val made = ArrayList<AppContainer>()

    private fun fresh(): AppContainer {
        val ctx = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        File(ctx.filesDir, "library").deleteRecursively()
        File(ctx.noBackupFilesDir, "restore").deleteRecursively()
        return TestContainer.create(ready = false).also { made += it }
    }

    /**
     * startUp() starts the watch coordinator's collectors, which run for the life of the
     * process; left running, they query a database the next test has torn down.
     */
    @After
    fun stop() {
        for (c in made) {
            c.applicationScope.cancel()
            c.db.close()
        }
    }

    /** A backup of "bananas", then the phone moves on to "milk", then a restore of it dies after the database. */
    private fun interruptedRestore(c: AppContainer) {
        runBlocking {
            c.repository.manualAdd("bananas", QtyBase.of(Rational.of(3), null, UnitLocale.METRIC), null)
            val file = c.backups.snapshot()
            c.repository.clearAll()
            c.repository.manualAdd("milk", QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), null)
            c.backups.beforeStage = { if (it == BackupManager.Stage.SETTINGS) throw Killed() }
            runCatching { c.backups.restore(file) }
            c.backups.beforeStage = {}
        }
    }

    @Test
    fun aNormalStartIsReadyWithoutANotice() {
        val c = fresh()
        assertEquals(Startup.Starting, c.startup.value)
        c.startUp()
        awaitTrue { c.startup.value == Startup.Ready }
        assertNull(c.notice.value)
    }

    @Test
    fun aSlowRecoveryHoldsTheWatchAndASharedRecipeUntilItIsDone() {
        val c = fresh()
        interruptedRestore(c)
        val epochBefore = runBlocking { c.repository.listEpoch() }
        val gate = CompletableDeferred<Unit>()
        c.backups.beforeStage = { if (it == BackupManager.Stage.DATABASE) gate.await() }
        c.startUp()
        awaitTrue { c.startup.value == Startup.Recovering }
        // A watch says hello and a recipe is shared while the restore is being finished.
        c.fake().deliver(WatchCodec.encode(WatchMessage.Hello(epochBefore.toInt(), 77, 0, 1_760_000_000, 0)))
        val share = c.applicationScope.async { c.importShared("2 onions\n1 carrot", "Soup") }
        runBlocking { delay(500) }
        assertTrue("nothing sent to the watch during recovery", c.fake().sent.isEmpty())
        assertFalse("the share waits", share.isCompleted)
        assertEquals(Startup.Recovering, c.startup.value)
        gate.complete(Unit)
        awaitTrue { c.startup.value == Startup.Ready }
        // The restored list, under the restore's new epoch, is what the watch is sent.
        assertEquals(setOf("cat:banana"), c.items())
        val epochAfter = runBlocking { c.repository.listEpoch() }
        assertNotEquals(epochBefore, epochAfter)
        awaitTrue { c.fake().sent.mapNotNull { WatchCodec.decodePage(it) }.isNotEmpty() }
        val pages = c.fake().sent.mapNotNull { WatchCodec.decodePage(it) }
        assertTrue(pages.all { it.epoch == epochAfter.toInt() })
        assertTrue(pages.flatMap { it.items }.any { it.name.contains("banana", ignoreCase = true) })
        assertTrue(pages.flatMap { it.items }.none { it.name.contains("milk", ignoreCase = true) })
        // The share went in after the restore, not under it.
        val id = runBlocking { withTimeout(5_000) { share.await() } }
        assertTrue(runBlocking { c.importJobs.get(id!!) } != null)
        assertEquals("A restore was interrupted; it has been finished", c.notice.value)
        // The import it queued runs to its end inside this test, not into the next one.
        runBlocking { c.awaitJob(id!!) }
    }

    @Test
    fun aRecoveryThatCannotFinishStopsAndAsksThenCarriesOnWhenTold() {
        val c = fresh()
        interruptedRestore(c)
        // Both copies damaged: nothing to roll forward or back to.
        val dir = File(c.context.noBackupFilesDir, "restore")
        File(dir, "target.json").writeText("{ half")
        File(dir, "previous.json").writeText("{ half")
        c.startUp()
        awaitTrue { c.startup.value == Startup.Stuck }
        compose.setContent { HyperShopTheme { HyperShopApp(c) } }
        compose.onNodeWithTag("startup-keep").assertExists()
        // No list screen, so nothing can be added, ticked or synced on top of half a restore.
        compose.onNodeWithTag("add-item").assertDoesNotExist()
        assertTrue(c.fake().sent.isEmpty())
        // "Try again" with the copies still damaged: still stuck, still asking.
        compose.onNodeWithTag("startup-retry").performClick()
        awaitTrue { c.startup.value == Startup.Stuck }
        compose.onNodeWithTag("startup-keep").performClick()
        awaitTrue { c.startup.value == Startup.Ready }
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("add-item").fetchSemanticsNodes().isNotEmpty() }
        // The journal is gone: the next start is a normal one.
        assertNull(runBlocking { BackupManager(c, "t").recover() })
    }

    @Test
    fun theUiShowsNoListBeforeStartUpHasFinished() {
        val c = fresh()
        compose.setContent { HyperShopTheme { HyperShopApp(c) } }
        compose.onNodeWithTag("startup").assertExists()
        compose.onNodeWithTag("add-item").assertDoesNotExist()
        c.startUp()
        awaitTrue { c.startup.value == Startup.Ready }
        // An empty list opens on This week (X.10).
        compose.waitUntil(5_000) { compose.onAllNodesWithTag("meals").fetchSemanticsNodes().isNotEmpty() }
    }

    @Test
    fun aRestartAfterAFinishedRecoveryIsNormal() {
        val c = fresh()
        interruptedRestore(c)
        c.startUp()
        awaitTrue { c.startup.value == Startup.Ready }
        assertEquals(setOf("cat:banana"), c.items())
        // The next process: the journal was ended, so nothing is replayed over later changes.
        runBlocking { c.repository.manualAdd("bread", QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), null) }
        assertNull(runBlocking { BackupManager(c, "t").recover() })
        assertEquals(setOf("cat:banana", "cat:bread"), c.items())
        assertFalse(File(c.context.noBackupFilesDir, "restore/journal").exists())
        // A damaged journal on its own (no copies) never touches the data either.
        File(c.context.noBackupFilesDir, "restore").mkdirs()
        RestoreJournal(File(c.context.noBackupFilesDir, "restore")).let { assertNull(it.pending()) }
        assertEquals(setOf("cat:banana", "cat:bread"), c.items())
    }
}
