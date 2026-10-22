package com.robert4lyf.hypershop.device

import android.net.Uri
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.backup.BackupManager
import com.robert4lyf.hypershop.backup.Inspection
import com.robert4lyf.hypershop.backup.RestoreOutcome
import com.robert4lyf.hypershop.core.merge.UseState
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.settings.ListMode
import com.robert4lyf.hypershop.ui.HyperShopApp
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * The five scenarios of the fourth pass, on a device: the real composables,
 * Room on the device's SQLite, real files. They need an emulator (or a phone
 * whose HyperShop data you can lose: see [DeviceContainer]) and run in CI's
 * `device-tests` job; the container here has no KVM, so they are compiled by
 * `tools/verify.sh` but not run there.
 */
@RunWith(AndroidJUnit4::class)
class DeviceScenariosTest {
    @get:Rule val compose = createComposeRule()

    private fun show(c: AppContainer) {
        compose.setContent { HyperShopTheme { HyperShopApp(c) } }
        compose.waitForIdle()
    }

    private fun ticks() =
        compose.onAllNodes(
            SemanticsMatcher("a tick") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("tick-") == true
            },
        )

    private fun waitFor(what: () -> Boolean) = compose.waitUntil(10_000) { runCatching(what).getOrDefault(false) }

    /** A: typed items, into the shop, every one bought, finished — and Undo is still offered. */
    @Test
    fun a_quickShop() {
        val c = DeviceContainer.create()
        show(c)
        // What the field and the list held after each step: the failure message, if it fails.
        val transcript = StringBuilder()

        fun note(what: String) {
            val field = compose.onNodeWithTag("add-item").fetchSemanticsNode().config.getOrNull(SemanticsProperties.EditableText)
            val items = runBlocking { c.repository.snapshot() }.liveItems.map { it.displayName }
            transcript.append("$what -> field='$field' items=$items\n")
        }
        for (line in listOf("milk", "6 eggs", "bread", "2 onions")) {
            compose.onNodeWithTag("add-item").performTextInput(line)
            note("typed '$line'")
            compose.onNodeWithTag("add-item").performImeAction()
            note("done")
        }
        val four = runCatching { waitFor { runBlocking { c.repository.snapshot() }.liveItems.size == 4 } }
        note("after waiting")
        assertTrue("Four typed items did not all land:\n$transcript", four.isSuccess)
        compose.onNodeWithTag("start-shopping").performClick()
        waitFor { compose.onAllNodesWithTag("shop").fetchSemanticsNodes().isNotEmpty() }
        // Ticked rows fold away under "N bought", so the first tick is the next item to buy, once the
        // last tap has landed. waitForIdle does not wait for the database, so each tap waits for its own
        // effect, as a shopper looks before the next tap; tapping on would hit the same row again.
        val bought = { runBlocking { c.repository.snapshot() }.let { l -> l.liveItems.count { l.isDone(it.id) } } }
        repeat(4) { i ->
            waitFor { ticks().fetchSemanticsNodes().isNotEmpty() }
            ticks()[0].performClick()
            waitFor { bought() == i + 1 }
            waitFor { compose.onAllNodesWithText("${i + 1} of 4 bought", substring = true).fetchSemanticsNodes().isNotEmpty() }
        }
        waitFor { compose.onNodeWithTag("shop-left").fetchSemanticsNode().config.toString().contains("All bought") }
        compose.onNodeWithTag("finish").performClick()
        waitFor { runBlocking { c.repository.snapshot() }.liveItems.isEmpty() }
        waitFor { runBlocking { c.settings.listMode.first() } == ListMode.PLAN }
        waitFor { compose.onAllNodesWithText("Undo").fetchSemanticsNodes().isNotEmpty() }
    }

    /** B: two saved recipes become the week's meals; the shared onion merges and both read "still needed". */
    @Test
    fun b_weeklyMealsFromSavedRecipes() {
        val c = DeviceContainer.create()
        val chilli = c.saveRecipe("Weeknight chilli", "2 onions", "500g beef mince", "Salt, to taste")
        val dal = c.saveRecipe("Red lentil dal", "1 onion", "200g red lentils")
        runBlocking {
            c.repository.addSavedRecipe(chilli)
            c.repository.addSavedRecipe(dal)
        }
        show(c)
        // One merged row: 2 + 1 onions. The list capitalises the name ("3 Onions").
        val onion = runBlocking { c.repository.snapshot() }.let { l -> l.total(l.itemByKey("cat:onion")!!.id)[BaseKey.ITEMS] }
        assertEquals(Rational.of(3), onion)
        waitFor { compose.onAllNodesWithText("3 onions", substring = true, ignoreCase = true).fetchSemanticsNodes().isNotEmpty() }
        val uses = runBlocking { c.repository.usesOnList() }
        assertEquals(2, uses.size)
        assertTrue(uses.all { it.progress.state == UseState.STILL_NEEDED })
        // Salt was at home: on neither the list nor any recipe's count.
        assertTrue(runBlocking { c.repository.snapshot() }.itemByKey("cat:salt") == null)
    }

    /** C: a backup written to a real file, read back, restored over a different list, and undone. */
    @Test
    fun c_backupAndRestoreThroughAFile() {
        val c = DeviceContainer.create(key = "sk-ant-device-test")
        val m = BackupManager(c, "device")
        runBlocking { c.repository.manualAdd("bananas", QtyBase.of(Rational.of(3), null, UnitLocale.METRIC), null) }
        val file = File(c.context.cacheDir, "backup-${System.nanoTime()}.json")
        assertTrue(runBlocking { m.writeTo(c.context, Uri.fromFile(file)) }.isSuccess)
        runBlocking {
            c.repository.clearAll()
            c.repository.manualAdd("milk", QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), null)
        }
        val read = runBlocking { m.inspect(c.context, Uri.fromFile(file)) } as Inspection.Ok
        val done = runBlocking { m.restore(read.file) } as RestoreOutcome.Restored
        assertEquals(listOf("cat:banana"), runBlocking { c.repository.snapshot() }.liveItems.map { it.mergeKey })
        assertEquals("sk-ant-device-test", runBlocking { c.apiKeyStore.get() })
        assertTrue(runBlocking { m.restore(done.before) } is RestoreOutcome.Restored)
        assertEquals(listOf("cat:milk"), runBlocking { c.repository.snapshot() }.liveItems.map { it.mergeKey })
    }

    /** D: a restore stopped half-way (the settings written, the library not) is finished by the next start. */
    @Test
    fun d_interruptedRestoreIsFinishedAtTheNextStart() {
        val c = DeviceContainer.create()
        runBlocking { c.repository.manualAdd("bananas", QtyBase.of(Rational.of(3), null, UnitLocale.METRIC), null) }
        val backup = runBlocking { c.backups.snapshot() }
        runBlocking { c.repository.clearAll() }
        val first = BackupManager(c, "device")
        first.beforeStage = { if (it == BackupManager.Stage.LIBRARY) throw OutOfMemoryError("stand-in for the process dying") }
        runCatching { runBlocking { first.restore(backup) } }
        assertEquals(BackupManager.Recovery.FINISHED, runBlocking { BackupManager(c, "device").recover() })
        assertEquals(listOf("cat:banana"), runBlocking { c.repository.snapshot() }.liveItems.map { it.mergeKey })
    }

    /** E: the shop screen speaks: each tick is a named checkbox with its state, big enough to hit. */
    @Test
    fun e_shopScreenAccessibility() {
        val c = DeviceContainer.create()
        runBlocking {
            for (n in listOf("milk", "bread")) c.repository.manualAdd(n, QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), null)
            c.settings.setListMode(ListMode.SHOP)
        }
        show(c)
        waitFor { ticks().fetchSemanticsNodes().size == 2 }
        val tick = ticks()[0]
        tick.assertHeightIsAtLeast(48.dp).assertWidthIsAtLeast(48.dp)
        val node = tick.fetchSemanticsNode()
        assertEquals(Role.Checkbox, node.config.getOrNull(SemanticsProperties.Role))
        assertEquals("Still needed", node.config.getOrNull(SemanticsProperties.StateDescription))
        assertTrue(node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().isNotEmpty())
        compose.onNodeWithTag("shop-left").assertIsDisplayed()
    }
}
