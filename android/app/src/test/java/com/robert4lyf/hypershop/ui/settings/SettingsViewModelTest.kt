package com.robert4lyf.hypershop.ui.settings

import android.net.Uri
import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.claude.ClaudeModel
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.data.ClaudeUsageEntity
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.await
import com.robert4lyf.hypershop.ui.common.UiMessage
import com.robert4lyf.hypershop.ui.list.add
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SettingsViewModelTest {
    @Test
    fun theFirstStateShownIsTheSavedOne() {
        val c = TestContainer.create(key = "sk-ant-test")
        runBlocking { c.settings.setTheme(com.robert4lyf.hypershop.settings.ThemeMode.DARK) }
        val vm = SettingsViewModel(c)
        val first = await(vm.ui) { it.loaded }
        // Already right when the screen first draws: nothing jumps from the defaults.
        assertTrue(first.hasKey)
        assertEquals(com.robert4lyf.hypershop.settings.ThemeMode.DARK, first.theme)
    }

    @Test
    fun saveTestAndRemoveKey() {
        val claude = MockWebServer().apply { start() }
        try {
            claude.enqueue(MockResponse().setResponseCode(401))
            claude.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}"""))
            val c = TestContainer.create(claude = claude)
            val vm = SettingsViewModel(c)
            await(vm.ui) { !it.hasKey }
            vm.saveKey("bad-key")
            await(vm.ui) { it.hasKey && it.keyStatus == KeyStatus.BAD_KEY }
            val req = claude.takeRequest()
            assertEquals("GET", req.method)
            assertEquals("/v1/models", req.path)
            assertEquals("bad-key", req.getHeader("x-api-key"))
            vm.testKey()
            await(vm.ui) { it.keyStatus == KeyStatus.OK }
            vm.removeKey()
            await(vm.ui) { !it.hasKey && it.keyStatus == null && !it.keyMissingAfterRestore }
            assertNull(runBlocking { c.apiKeyStore.get() })
        } finally {
            claude.shutdown()
        }
    }

    @Test
    fun aTestThatEndsAfterRemoveKeyIsIgnored() {
        val claude = MockWebServer().apply { start() }
        try {
            claude.enqueue(MockResponse().setResponseCode(200).setBody("""{"data":[]}""").setHeadersDelay(300, TimeUnit.MILLISECONDS))
            val c = TestContainer.create(claude = claude, key = "test-key")
            val vm = SettingsViewModel(c)
            await(vm.ui) { it.loaded && it.hasKey }
            vm.testKey()
            await(vm.ui) { it.keyStatus == KeyStatus.TESTING }
            vm.removeKey()
            await(vm.ui) { !it.hasKey && it.keyStatus == null }
            claude.takeRequest()
            // The answer arrives after the removal: it must not show "OK" for a key that's gone.
            val end = System.currentTimeMillis() + 1000
            while (System.currentTimeMillis() < end) {
                shadowOf(Looper.getMainLooper()).idle()
                assertNull(vm.ui.value.keyStatus)
                Thread.sleep(10)
            }
        } finally {
            claude.shutdown()
        }
    }

    @Test
    fun twoQuickAisleMovesBothCount() {
        val c = TestContainer.create()
        val vm = SettingsViewModel(c)
        val start = await(vm.ui) { it.loaded && it.aisles.size > 3 }.aisles
        val third = start[2]
        // Two taps before either write lands.
        vm.moveAisle(third, -1)
        vm.moveAisle(third, -1)
        assertEquals(third, vm.ui.value.aisles.first())
        val end = System.currentTimeMillis() + 5000
        while (runBlocking { c.repository.aisleOrder() }.first() != third && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals(listOf(third, start[0], start[1]) + start.drop(3), runBlocking { c.repository.aisleOrder() })
    }

    @Test
    fun aKeyThatCannotBeOneNeverReachesAnthropic() {
        val c = TestContainer.create()
        val vm = SettingsViewModel(c)
        await(vm.ui) { it.loaded }
        vm.saveKey("caf\u00e9-key")
        await(vm.ui) { it.keyStatus == KeyStatus.MALFORMED }
        assertFalse(vm.ui.value.hasKey)
    }

    @Test
    fun aislesGoToTheEndsAndBackToTheDefault() {
        val c = TestContainer.create()
        val vm = SettingsViewModel(c)
        val start = await(vm.ui) { it.loaded && it.aisles.size > 3 }.aisles
        val third = start[2]
        vm.moveAisleTo(third, 0)
        assertEquals(third, vm.ui.value.aisles.first())
        vm.moveAisleTo(third, start.lastIndex)
        assertEquals(third, vm.ui.value.aisles.last())
        val end = System.currentTimeMillis() + 5000
        while (runBlocking { c.repository.aisleOrder() }.last() != third && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(10)
        }
        assertEquals(third, runBlocking { c.repository.aisleOrder() }.last())
        vm.resetAisleOrder()
        assertEquals(AisleCode.entries.toList(), vm.ui.value.aisles)
        await(vm.ui) { runBlocking { c.repository.aisleOrder() } == AisleCode.entries.toList() }
    }

    @Test
    fun aRemovedStapleCanBeUndone() {
        val c = TestContainer.create()
        val vm = SettingsViewModel(c)
        val got = mutableListOf<UiMessage>()
        val watching = CoroutineScope(Dispatchers.Main).launch { vm.events.collect { got += it } }
        await(vm.ui) { "salt" in it.staples }
        vm.removeStaple("salt")
        await(vm.ui) { "salt" !in it.staples && got.size == 1 }
        assertEquals("salt is no longer always at home", got.single().text)
        runBlocking { got.single().undo!!.invoke() }
        await(vm.ui) { "salt" in it.staples }
        watching.cancel()
    }

    @Test
    fun keyMissingAfterRestoreAndLostNotice() {
        val c = TestContainer.create()
        runBlocking {
            c.settings.setKeyWasSet(true)
            c.settings.setKeyLostNotice(true)
        }
        val vm = SettingsViewModel(c)
        await(vm.ui) { it.keyMissingAfterRestore && it.keyLost }
        vm.dismissKeyLost()
        await(vm.ui) { !it.keyLost }
    }

    @Test
    fun modelCapsAndSpend() {
        val c = TestContainer.create()
        runBlocking {
            val today = c.today().toString()
            c.db.dao().putUsage(ClaudeUsageEntity(today, ClaudeModel.HAIKU.id, 1_000_000, 1_000_000, 0, 0, 3))
        }
        val vm = SettingsViewModel(c)
        val ui = await(vm.ui) { it.callsToday == 3 }
        assertEquals(0.60, ui.spentThisMonthUsd, 1e-9)
        assertEquals(ClaudeModel.HAIKU, ui.model)
        vm.setModel(ClaudeModel.SONNET)
        assertFalse(vm.setMonthlyCap("lots"))
        assertFalse(vm.setMonthlyCap("500"))
        assertTrue(vm.setMonthlyCap("$5"))
        assertTrue(vm.setDailyCalls("20"))
        await(vm.ui) { it.model == ClaudeModel.SONNET && it.monthlyCapUsd == 5.0 && it.dailyCalls == 20 }
        assertEquals(5.0, runBlocking { c.settings.guard() }.monthlyCapUsd, 0.0)
    }

    @Test
    fun staplesAndAisleOrder() {
        val c = TestContainer.create()
        val vm = SettingsViewModel(c)
        val ui = await(vm.ui) { it.staples.isNotEmpty() && it.aisles.isNotEmpty() }
        assertTrue(ui.staples.toString(), "salt" in ui.staples)
        vm.removeStaple("salt")
        await(vm.ui) { "salt" !in it.staples }
        val second = ui.aisles[1]
        vm.moveAisle(second, -1)
        await(vm.ui) { it.aisles.first() == second }
        assertEquals(second, runBlocking { c.repository.aisleOrder() }.first())
        vm.moveAisle(second, -1)
        assertEquals(second, await(vm.ui) { true }.aisles.first())
        assertTrue(AisleCode.PRODUCE in ui.aisles)
    }

    @Test
    fun backupToAFileAndRestoreItWithUndo() {
        val c = TestContainer.create()
        c.add("2 onions", "1 carrot")
        runBlocking { c.settings.setUnits(com.robert4lyf.hypershop.core.merge.UnitsSetting.IMPERIAL) }
        val vm = SettingsViewModel(c)
        val got = mutableListOf<UiMessage>()
        val watching = CoroutineScope(Dispatchers.Main).launch { vm.events.collect { got += it } }
        await(vm.ui) { it.loaded }
        val file = File(c.context.cacheDir, "backup-${System.nanoTime()}.json")
        vm.backupTo(Uri.fromFile(file))
        await(vm.ui) { got.size == 1 }
        assertEquals("Backed up 1 recipe and 2 items", got[0].text)
        assertTrue(file.length() > 0)
        // The phone moves on, then the backup comes back: a prompt first, nothing changed until "Restore".
        runBlocking {
            c.repository.clearAll()
            c.settings.setUnits(com.robert4lyf.hypershop.core.merge.UnitsSetting.METRIC)
        }
        // Robolectric's resolver reads only registered streams; the real one opens what the picker returned.
        val resolver = shadowOf(c.context.contentResolver)
        resolver.registerInputStreamSupplier(Uri.fromFile(file)) { file.inputStream() }
        vm.inspectRestore(Uri.fromFile(file))
        val prompt = await(vm.ui) { it.restorePrompt != null }.restorePrompt!!
        assertEquals(1, prompt.recipes)
        assertEquals(2, prompt.itemsOnList)
        assertTrue(runBlocking { c.repository.snapshot() }.liveItems.isEmpty())
        vm.cancelRestore()
        await(vm.ui) { it.restorePrompt == null }
        assertTrue(runBlocking { c.repository.snapshot() }.liveItems.isEmpty())
        vm.inspectRestore(Uri.fromFile(file))
        await(vm.ui) { it.restorePrompt != null }
        vm.confirmRestore()
        await(vm.ui) { got.size == 2 }
        assertEquals("Restored 1 recipe and 2 items", got[1].text)
        assertEquals(2, runBlocking { c.repository.snapshot() }.liveItems.size)
        assertEquals(com.robert4lyf.hypershop.core.merge.UnitsSetting.IMPERIAL, runBlocking { c.settings.units.first() })
        // Undo puts back what the phone had before the restore (an empty list, metric).
        runBlocking { got[1].undo!!.invoke() }
        assertTrue(runBlocking { c.repository.snapshot() }.liveItems.isEmpty())
        assertEquals(com.robert4lyf.hypershop.core.merge.UnitsSetting.METRIC, runBlocking { c.settings.units.first() })
        watching.cancel()
    }

    @Test
    fun undoOfARestoreIsRefusedOnceTheListHasMovedOn() {
        val c = TestContainer.create()
        c.add("2 onions", "1 carrot")
        val vm = SettingsViewModel(c)
        val got = mutableListOf<UiMessage>()
        val watching = CoroutineScope(Dispatchers.Main).launch { vm.events.collect { got += it } }
        await(vm.ui) { it.loaded }
        val file = File(c.context.cacheDir, "backup-${System.nanoTime()}.json")
        vm.backupTo(Uri.fromFile(file))
        await(vm.ui) { got.size == 1 }
        runBlocking { c.repository.clearAll() }
        shadowOf(c.context.contentResolver).registerInputStreamSupplier(Uri.fromFile(file)) { file.inputStream() }
        vm.inspectRestore(Uri.fromFile(file))
        await(vm.ui) { it.restorePrompt != null }
        vm.confirmRestore()
        await(vm.ui) { got.size == 2 }
        // Something bought after the restore: Undo would take the purchase back, so it is refused.
        val l = runBlocking { c.repository.snapshot() }
        runBlocking { c.repository.tick(l.itemByKey("cat:onion")!!.id) }
        runBlocking { got[1].undo!!.invoke() }
        await(vm.ui) { got.size == 3 }
        assertEquals("Can't undo: the list has changed since the restore", got[2].text)
        val after = runBlocking { c.repository.snapshot() }
        assertEquals(2, after.liveItems.size)
        assertTrue(after.isDone(after.itemByKey("cat:onion")!!.id))
        watching.cancel()
    }

    @Test
    fun aFileThatIsNotABackupIsRefusedWithoutChangingAnything() {
        val c = TestContainer.create()
        c.add("2 onions")
        val vm = SettingsViewModel(c)
        val got = mutableListOf<UiMessage>()
        val watching = CoroutineScope(Dispatchers.Main).launch { vm.events.collect { got += it } }
        await(vm.ui) { it.loaded }
        val resolver = shadowOf(c.context.contentResolver)
        val notJson = File(c.context.cacheDir, "notes-${System.nanoTime()}.txt").apply { writeText("milk, eggs") }
        resolver.registerInputStreamSupplier(Uri.fromFile(notJson)) { notJson.inputStream() }
        vm.inspectRestore(Uri.fromFile(notJson))
        await(vm.ui) { got.size == 1 }
        assertEquals("That file isn't a HyperShop backup", got[0].text)
        val newer = File(c.context.cacheDir, "new-${System.nanoTime()}.json")
        newer.writeText("""{"format":"hypershop-backup","version":99}""")
        resolver.registerInputStreamSupplier(Uri.fromFile(newer)) { newer.inputStream() }
        vm.inspectRestore(Uri.fromFile(newer))
        await(vm.ui) { got.size == 2 }
        assertTrue(got[1].text, got[1].text.startsWith("That backup was made by a newer HyperShop"))
        val broken =
            File(c.context.cacheDir, "broken-${System.nanoTime()}.json").apply {
                // One item whose id is not below nextId: a HyperShop backup, but one that cannot be trusted.
                val item =
                    """{"id":5,"mergeKey":"cat:onion","catalogId":null,"normName":"onion","displayName":"onion",""" +
                        """"aisleCode":"PRODUCE","position":0,"rev":1,"deletedAt":null,"updatedAt":0}"""
                val data = """{"listRev":1,"nextId":1,"catalogVersion":"x","items":[$item]}"""
                writeText("""{"format":"hypershop-backup","version":1,"createdAt":1,"data":$data}""")
            }
        resolver.registerInputStreamSupplier(Uri.fromFile(broken)) { broken.inputStream() }
        vm.inspectRestore(Uri.fromFile(broken))
        await(vm.ui) { got.size == 3 }
        assertEquals("That backup file is damaged, so nothing was changed", got[2].text)
        assertNull(vm.ui.value.restorePrompt)
        assertEquals(1, runBlocking { c.repository.snapshot() }.liveItems.size)
        watching.cancel()
    }
}
