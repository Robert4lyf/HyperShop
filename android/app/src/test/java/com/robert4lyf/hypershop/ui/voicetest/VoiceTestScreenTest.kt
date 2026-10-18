package com.robert4lyf.hypershop.ui.voicetest

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.robert4lyf.hypershop.testutil.Shots
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import com.robert4lyf.hypershop.voice.VoiceTestLog
import com.robert4lyf.hypershop.voice.VoiceTestService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class VoiceTestScreenTest {
    @get:Rule val compose = createComposeRule()

    private val sample =
        VoiceTestUi(
            packStatus = "English (UK) speech pack: installed",
            quickStatus = "Heard \"next three\" in 420ms (on-device)",
            log =
                listOf(
                    "10:01:02 — background test started",
                    "10:01:30 heard: next three (0.91) | next tree",
                    "10:02:02 button: KEYCODE_MEDIA_PLAY_PAUSE",
                ),
        )

    @Test
    fun buttonsAskForTheChecks() {
        val got = mutableListOf<String>()
        compose.setContent {
            HyperShopTheme(dynamicColor = false) {
                VoiceTestScreen(
                    sample,
                    VoiceTestActions(
                        onCheckPack = { got += "pack" },
                        onQuickCheck = { got += "quick $it" },
                        onStart = { got += "start $it" },
                        onShare = { got += "share" },
                    ),
                )
            }
        }
        compose.onNodeWithTag("check-pack").performClick()
        compose.onNodeWithTag("quick-on-device").performClick()
        compose.onNodeWithTag("voice-test").performScrollToNode(hasTestTag("bg-start"))
        compose.onNodeWithTag("bg-start").performClick()
        compose.onNodeWithTag("voice-test").performScrollToNode(hasTestTag("share-log"))
        compose.onNodeWithTag("share-log").performClick()
        assertEquals(
            listOf(
                "pack",
                "quick true",
                "start ${VoiceTestService.Options(headsetMic = true, onDevice = true, mode = VoiceTestService.Mode.SEGMENTED)}",
                "share",
            ),
            got,
        )
        compose.onNodeWithTag("voice-test").performScrollToNode(hasText("10:02:02 button: KEYCODE_MEDIA_PLAY_PAUSE"))
    }

    @Test
    fun downloadWaitsWhileOneRunsAndTheRowsAreSwitches() {
        compose.setContent {
            HyperShopTheme(dynamicColor = false) {
                VoiceTestScreen(sample.copy(downloading = true, packStatus = "Downloading… 40%"), VoiceTestActions())
            }
        }
        compose.onNodeWithTag("download-pack").assertIsNotEnabled()
        compose.onNodeWithText("Downloading… 40%").assertExists()
        compose.onNodeWithTag("voice-test").performScrollToNode(hasText("Use the headset microphone"))
        compose.onNodeWithText("Use the headset microphone").assertIsOn()
    }

    @Test
    fun downloadIsOfferedWhenNoneRuns() {
        compose.setContent { HyperShopTheme(dynamicColor = false) { VoiceTestScreen(sample, VoiceTestActions()) } }
        compose.onNodeWithTag("download-pack").assertIsEnabled()
    }

    @Test
    fun theLogKeepsItsLinesInAFile() {
        VoiceTestLog.init(ApplicationProvider.getApplicationContext())
        VoiceTestLog.clear()
        VoiceTestLog.log("heard: done")
        assertTrue(VoiceTestLog.text().endsWith("heard: done"))
        VoiceTestLog.clear()
        assertEquals("", VoiceTestLog.text())
    }

    @Test
    fun recordLight() {
        compose.setContent { HyperShopTheme(dynamicColor = false) { VoiceTestScreen(sample, VoiceTestActions()) } }
        compose.onRoot().captureRoboImage(Shots.path("voice-test-light.png"))
    }
}
