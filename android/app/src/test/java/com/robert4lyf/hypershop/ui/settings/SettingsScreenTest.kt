package com.robert4lyf.hypershop.ui.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.testutil.Shots
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val UI =
    SettingsUi(
        staples = listOf("olive oil", "pepper", "salt", "water"),
        aisles = AisleCode.entries.take(4),
        spentThisMonthUsd = 0.04,
        callsToday = 3,
    )

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class SettingsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val events = mutableListOf<String>()
    private val actions =
        SettingsActions(
            onRemoveStaple = { events += "staple $it" },
            onMoveAisle = { a, d -> events += "aisle $a $d" },
            onWatchVibrate = { events += "vibrate $it" },
            onSaveKey = { events += "key ${it.length}" },
            onDebugLog = { events += "log $it" },
        )

    private fun show(
        ui: SettingsUi = UI,
        dark: Boolean = false,
    ) = compose.setContent { HyperShopTheme(dark = dark, dynamicColor = false) { SettingsScreen(ui, actions) } }

    @Test
    fun groupsKeyFieldAndControls() {
        show()
        compose.onNodeWithContentDescription("Remove salt").performClick()
        compose.onNodeWithContentDescription("Move ${AisleCode.entries[1].displayName} up").performClick()
        val list = compose.onNodeWithTag("settings")
        list.performScrollToNode(hasTestTag("key-field"))
        val field = compose.onNodeWithTag("key-field")
        assertTrue(field.fetchSemanticsNode().config.contains(SemanticsProperties.Password))
        field.performTextInput("abcdef")
        list.performScrollToNode(hasText("Save and test"))
        compose.onNodeWithText("Save and test").performClick()
        list.performScrollToNode(hasTestTag("privacy"))
        compose.onNodeWithTag("privacy").assertExists()
        list.performScrollToNode(hasTestTag("spend"))
        compose.onNodeWithText("Spent this month: $0.04 of $2.00 · 3 of 50 calls today").assertExists()
        list.performScrollToNode(hasText("Keep a debug log (on this phone only)"))
        compose.onNodeWithText("Keep a debug log (on this phone only)").assertIsOff().performClick()
        assertEquals(listOf("staple salt", "aisle ${AisleCode.entries[1]} -1", "key 6", "log true"), events)
    }

    @Test
    fun savedKeyShowsTestAndRemove() {
        show(UI.copy(hasKey = true, keyStatus = KeyStatus.BAD_KEY))
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("key-status"))
        compose.onNodeWithText("✗ Anthropic didn't accept that key").assertExists()
        compose.onNodeWithText("Remove key").assertExists()
    }

    @Test
    fun keyLostNoticeShowsOnce() {
        show(UI.copy(keyLost = true))
        compose.onNodeWithText("Claude key removed").assertExists()
    }

    @Test
    fun recordLight() {
        show()
        compose.onRoot().captureRoboImage(Shots.path("settings-light.png"))
    }

    @Test
    fun recordDark() {
        show(UI.copy(hasKey = true, keyStatus = KeyStatus.OK), dark = true)
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("spend"))
        compose.onRoot().captureRoboImage(Shots.path("settings-dark.png"))
    }
}
