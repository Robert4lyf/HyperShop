package com.robert4lyf.hypershop.device

import android.util.Log
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.robert4lyf.hypershop.HyperShopApplication
import com.robert4lyf.hypershop.MainActivity
import com.robert4lyf.hypershop.ui.recipes.PhotoDecodes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assume.assumeTrue
import org.junit.FixMethodOrder
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.MethodSorters

/**
 * U.4: the measurements T.4 left out, on the installed app itself (the real database and photo folder, not
 * a test container), so it is emulator-only and runs on request (`-e startup true`, tools/device_perf.sh).
 * The cold starts are timed by the script with `am start -W`, before and after [a_seedThreeHundredPhotos];
 * this class seeds, and times Saved surviving a rotation (an activity recreated, as the system does).
 */
@RunWith(AndroidJUnit4::class)
@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class StartupProbeTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    private val enabled = InstrumentationRegistry.getArguments().getString("startup") == "true"

    private val app get() = InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as HyperShopApplication

    private fun report(
        what: String,
        value: Double,
        unit: String,
    ) = Log.i(TAG, "%-52s %8.1f %s".format(what, value, unit))

    @Test
    fun a_seedThreeHundredPhotos() {
        assumeTrue("run on request only", enabled)
        val c = app.container
        runBlocking { c.awaitReady() }
        val have = runBlocking { c.repository.observeLibrary().first() }.size
        if (have < SEEDED) c.photographed(SEEDED - have)
        report("seeded: saved recipes with a photo", runBlocking { c.repository.observeLibrary().first() }.size.toDouble(), "")
    }

    @Test
    fun b_savedSurvivesARotation() {
        assumeTrue("run on request only", enabled)
        val c = app.container
        runBlocking { c.awaitReady() }
        val newest = "lib-" + runBlocking { c.repository.observeLibrary().first() }.maxBy { it.lastAddedAt }.key
        val recipesTab = hasText("Recipes") and SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab)
        compose.waitUntil(30_000) { compose.onAllNodes(recipesTab).fetchSemanticsNodes().isNotEmpty() }
        compose.onNode(recipesTab).performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag("view-library").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithTag("view-library").performClick()
        compose.waitUntil(30_000) { compose.onAllNodesWithTag(newest).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()

        val decodes = PhotoDecodes.count.get()
        val bytes = PhotoDecodes.bytes.get()
        val start = System.nanoTime()
        compose.activityRule.scenario.recreate()
        // Back on Saved, its newest card drawn again: what the person sees after turning the phone.
        val back =
            runCatching { compose.waitUntil(10_000) { compose.onAllNodesWithTag(newest).fetchSemanticsNodes().isNotEmpty() } }.isSuccess
        val ms = (System.nanoTime() - start) / 1e6
        compose.waitForIdle()
        report("rotation on Saved (300 photos): back to Saved", if (back) 1.0 else 0.0, "(1 = yes)")
        report("rotation on Saved (300 photos): time", ms, "ms")
        report("rotation on Saved (300 photos): photos decoded", (PhotoDecodes.count.get() - decodes).toDouble(), "")
        report("rotation on Saved (300 photos): bitmap bytes made", (PhotoDecodes.bytes.get() - bytes) / 1048576.0, "MB")
    }

    private companion object {
        const val TAG = "HyperShopPerf"
        const val SEEDED = 300
    }
}
