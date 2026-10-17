package com.robert4lyf.hypershop.ui.settings

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.claude.ClaudeModel
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.data.ClaudeUsageEntity
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.await
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SettingsViewModelTest {
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
        vm.setWatchVibrate(false)
        await(vm.ui) { !it.watchVibrate }
        assertFalse(runBlocking { c.settings.watchVibrate.first() })
        assertTrue(AisleCode.PRODUCE in ui.aisles)
    }
}
