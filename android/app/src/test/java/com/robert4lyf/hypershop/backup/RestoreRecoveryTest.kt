package com.robert4lyf.hypershop.backup

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.catalog.EntryEdit
import com.robert4lyf.hypershop.catalog.SaveResult
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.sync.WatchMessage
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.settings.ListMode
import com.robert4lyf.hypershop.settings.ThemeMode
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.ui.list.add
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** Stands in for the process dying: an Error, so nothing on the way out catches it as a failure. */
private class Killed : Error("process killed")

/**
 * Step 9.2: a restore spans the database, the settings store and the library
 * file. Stopped at every stage, the next start finishes it; failing (not dying)
 * part-way puts back what was there; and "nothing was changed" is said only when
 * it is true. The key never moves.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RestoreRecoveryTest {
    /** The state the backup holds: a recipe, dark theme, a custom ingredient. */
    private fun backedUp(): Pair<AppContainer, BackupFile> {
        // One test's files are shared by every container it builds: each stage starts from none.
        val ctx = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(ctx.filesDir, "library").deleteRecursively()
        File(ctx.noBackupFilesDir, "restore").deleteRecursively()
        val c = TestContainer.create(key = "sk-ant-test")
        c.add("2 onions", "1 carrot")
        runBlocking {
            c.settings.setTheme(ThemeMode.DARK)
            assertTrue(c.library.save(EntryEdit("", "Zorblax", "", aisle = "PRODUCE")) is SaveResult.Saved)
        }
        val file = runBlocking { c.backups.snapshot() }
        // Then the phone moves on: another list, another theme, the ingredient gone, mid-shop.
        runBlocking {
            c.repository.clearAll()
            c.repository.manualAdd("bananas", QtyBase.of(Rational.of(3), null, UnitLocale.METRIC), null)
            c.settings.setTheme(ThemeMode.LIGHT)
            c.settings.setListMode(ListMode.SHOP)
            c.library.reset("user_zorblax")
        }
        return c to file
    }

    private fun AppContainer.isBackup() =
        runBlocking {
            assertEquals(setOf("cat:onion", "cat:carrot"), repository.snapshot().liveItems.map { it.mergeKey }.toSet())
            assertEquals(ThemeMode.DARK, settings.theme.first())
            assertEquals(ListMode.PLAN, settings.listMode.first())
            assertEquals(setOf("user_zorblax"), library.edits.value.keys)
            assertEquals("sk-ant-test", apiKeyStore.get())
        }

    private fun AppContainer.isAsBefore() =
        runBlocking {
            assertEquals(listOf("cat:banana"), repository.snapshot().liveItems.map { it.mergeKey })
            assertEquals(ThemeMode.LIGHT, settings.theme.first())
            assertTrue(library.edits.value.isEmpty())
            assertEquals("sk-ant-test", apiKeyStore.get())
        }

    /** The next start: a new manager over the same files, as a new process would build. */
    private fun AppContainer.restart() = BackupManager(this, "test")

    @Test
    fun killedAtEveryStageTheNextStartFinishesIt() {
        for (stage in BackupManager.Stage.entries) {
            val (c, file) = backedUp()
            val m = c.backups
            m.beforeStage = { if (it == stage) throw Killed() }
            runCatching { runBlocking { m.restore(file) } }.let { assertTrue("$stage", it.exceptionOrNull() is Killed) }
            assertEquals("$stage", BackupManager.Recovery.FINISHED, runBlocking { c.restart().recover() })
            c.isBackup()
            // Once: the journal is gone.
            assertNull(runBlocking { c.restart().recover() })
        }
    }

    @Test
    fun killedDuringTheRecoveryItselfTheStartAfterFinishesIt() {
        val (c, file) = backedUp()
        c.backups.beforeStage = { if (it == BackupManager.Stage.LIBRARY) throw Killed() }
        runCatching { runBlocking { c.backups.restore(file) } }
        val second = c.restart().apply { beforeStage = { if (it == BackupManager.Stage.REKEY) throw Killed() } }
        runCatching { runBlocking { second.recover() } }
        assertEquals(BackupManager.Recovery.FINISHED, runBlocking { c.restart().recover() })
        c.isBackup()
    }

    @Test
    fun aDamagedCopyOfTheFileRollsBackToWhatWasThere() {
        val (c, file) = backedUp()
        c.backups.beforeStage = { if (it == BackupManager.Stage.SETTINGS) throw Killed() }
        runCatching { runBlocking { c.backups.restore(file) } }
        File(c.context.noBackupFilesDir, "restore/target.json").writeText("{ half a file")
        assertEquals(BackupManager.Recovery.ROLLED_BACK, runBlocking { c.restart().recover() })
        c.isAsBefore()
    }

    @Test
    fun copiesWithNoJournalAreABeginThatNeverFinishedAndChangeNothing() {
        val (c, file) = backedUp()
        val dir = File(c.context.noBackupFilesDir, "restore")
        RestoreJournal(dir).begin(file, file)
        File(dir, "journal").delete()
        assertNull(runBlocking { c.restart().recover() })
        assertTrue(dir.listFiles().orEmpty().isEmpty())
        c.isAsBefore()
    }

    @Test
    fun aFailurePartWayPutsBackWhatWasThere() {
        val (c, file) = backedUp()
        var failures = 0
        c.backups.beforeStage = { if (it == BackupManager.Stage.LIBRARY && failures++ == 0) throw IllegalStateException("disk") }
        assertEquals(RestoreOutcome.Failed(rolledBack = true), runBlocking { c.backups.restore(file) })
        c.isAsBefore()
        assertNull(runBlocking { c.restart().recover() })
    }

    @Test
    fun aFailureThatTheRollbackCannotUndoIsLeftForTheNextStart() {
        val (c, file) = backedUp()
        c.backups.beforeStage = { if (it == BackupManager.Stage.LIBRARY) throw IllegalStateException("disk") }
        assertEquals(RestoreOutcome.Failed(rolledBack = false), runBlocking { c.backups.restore(file) })
        assertEquals(BackupManager.Recovery.FINISHED, runBlocking { c.restart().recover() })
        c.isBackup()
    }

    @Test
    fun aRefusalChangesNothingAndLeavesNoJournal() {
        val (c, file) = backedUp()
        val broken = file.copy(data = file.data.copy(nextId = 0))
        assertTrue(runBlocking { c.backups.restore(broken) } is RestoreOutcome.Refused)
        c.isAsBefore()
        assertNull(runBlocking { c.restart().recover() })
    }

    @Test
    fun undoIsARestoreOfWhatWasThere() {
        val (c, file) = backedUp()
        val done = runBlocking { c.backups.restore(file) } as RestoreOutcome.Restored
        c.isBackup()
        assertTrue(runBlocking { c.backups.restore(done.before) } is RestoreOutcome.Restored)
        c.isAsBefore()
    }

    @Test
    fun theWatchStartsAgainAfterARecoveredRestore() {
        val (c, file) = backedUp()
        runBlocking { c.repository.applyWatch(WatchMessage.Hello(c.repository.listEpoch().toInt(), 7, 0, 1_760_000_000, 0)) }
        val epoch = runBlocking { c.repository.listEpoch() }
        c.backups.beforeStage = { if (it == BackupManager.Stage.SETTINGS) throw Killed() }
        runCatching { runBlocking { c.backups.restore(file) } }
        runBlocking { c.restart().recover() }
        assertTrue(epoch != runBlocking { c.repository.listEpoch() })
        assertNull(runBlocking { c.repository.lastInstall() })
    }

    @Test
    fun twoRestoresAtOnceRunOneAfterTheOtherAndNeverMix() {
        val (c, file) = backedUp()
        val other = runBlocking { c.backups.snapshot() }
        // Slow stages, so the two would interleave if they could.
        c.backups.beforeStage = { delay(20) }
        val outcomes =
            runBlocking {
                coroutineScope {
                    listOf(
                        async { c.backups.restore(file) },
                        async { c.backups.restore(other) },
                        async { repeat(5) { c.repository.manualAdd("milk", QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), null) } },
                    ).awaitAll()
                }
            }
        assertTrue(outcomes.take(2).all { it is RestoreOutcome.Restored })
        // Whichever ran last, the list and the settings came from the same file.
        val theme = runBlocking { c.settings.theme.first() }
        val keys = runBlocking { c.repository.snapshot().liveItems.map { it.mergeKey }.toSet() - "cat:milk" }
        if (theme == ThemeMode.DARK) assertEquals(setOf("cat:onion", "cat:carrot"), keys) else assertEquals(setOf("cat:banana"), keys)
        assertNull(runBlocking { c.restart().recover() })
    }
}
