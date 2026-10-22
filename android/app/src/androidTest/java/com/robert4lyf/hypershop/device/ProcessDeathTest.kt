package com.robert4lyf.hypershop.device

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.robert4lyf.hypershop.HyperShopApplication
import com.robert4lyf.hypershop.backup.BackupManager
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.di.Startup
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * A restore killed by real process death, and the next process finishing it
 * before anything is shown. Two stages in two processes, so it runs only when
 * asked (`-e processDeath true`), never in the ordinary suite: it uses the
 * app's own database and files, and stage one kills the process on purpose.
 *
 * CI's device job drives it with `adb shell am instrument` (Gradle's connected
 * task would uninstall the app, and the evidence with it, between stages).
 * Emulator only: it replaces whatever list the installed app holds.
 */
@RunWith(AndroidJUnit4::class)
class ProcessDeathTest {
    private val enabled = InstrumentationRegistry.getArguments().getString("processDeath") == "true"
    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HyperShopApplication

    @Test
    fun stage1_theProcessDiesHalfwayThroughARestore() {
        assumeTrue("run on request only", enabled)
        val c = app.container
        runBlocking {
            withTimeout(30_000) { c.startup.first { it == Startup.Ready } }
            c.repository.clearAll()
            c.repository.manualAdd("bananas", QtyBase.of(Rational.of(3), null, UnitLocale.METRIC), null)
            val backup = c.backups.snapshot()
            c.repository.clearAll()
            c.repository.manualAdd("milk", QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), null)
            // The database is restored, the settings and the library are not: now the process goes, at once.
            c.backups.beforeStage = { if (it == BackupManager.Stage.SETTINGS) Runtime.getRuntime().halt(0) }
            c.backups.restore(backup)
        }
        error("the process should have died before this")
    }

    @Test
    fun stage2_theNextProcessFinishesItBeforeAnythingElse() {
        assumeTrue("run on request only", enabled)
        val c = app.container
        // A new process: the application has started up on its own, journal and all.
        val ready = runBlocking { withTimeout(30_000) { c.startup.first { it == Startup.Ready || it == Startup.Stuck } } }
        assertEquals(Startup.Ready, ready)
        assertEquals("A restore was interrupted; it has been finished", c.notice.value)
        assertEquals(listOf("cat:banana"), runBlocking { c.repository.snapshot() }.liveItems.map { it.mergeKey })
        assertEquals(false, File(c.context.noBackupFilesDir, "restore/journal").exists())
    }
}
