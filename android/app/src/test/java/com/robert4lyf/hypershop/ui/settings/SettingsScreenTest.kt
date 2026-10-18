package com.robert4lyf.hypershop.ui.settings

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assertIsNotEnabled
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
        loaded = true,
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
            onMoveAisleTo = { a, i -> events += "aisle $a to $i" },
            onResetAisles = { events += "reset aisles" },
            onWatchVibrate = { events += "vibrate $it" },
            onSaveKey = { events += "key ${it.length}" },
            onRemoveKey = { events += "remove key" },
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
        list.performScrollToNode(hasText("Show the debug log"))
        compose.onNodeWithText("Show the debug log").assertIsOff().performClick()
        assertEquals(listOf("staple salt", "aisle ${AisleCode.entries[1]} -1", "key 6", "log true"), events)
    }

    @Test
    fun savedKeyShowsTestAndRemove() {
        show(UI.copy(hasKey = true, keyStatus = KeyStatus.BAD_KEY))
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("key-status"))
        compose.onNodeWithText("✗ Anthropic didn't accept that key").assertExists()
        // Removing asks first.
        compose.onNodeWithText("Remove key").performClick()
        compose.onNodeWithText("Keep").performClick()
        compose.onNodeWithText("Remove key").performClick()
        compose.onNodeWithText("Remove").performClick()
        assertEquals(listOf("remove key"), events)
    }

    @Test
    fun aMalformedKeyIsToldApartFromARejectedOne() {
        show(UI.copy(keyStatus = KeyStatus.MALFORMED))
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("key-status"))
        compose.onNodeWithText("✗ That doesn't look like a key — paste it again").assertExists()
        compose.onNodeWithContentDescription("Paste").assertExists()
    }

    @Test
    fun aislesMoveToTheEndsAndResetAfterAsking() {
        show()
        val second = AisleCode.entries[1]
        compose.onNodeWithContentDescription("More for ${second.displayName}").performClick()
        compose.onNodeWithText("Move to bottom").performClick()
        compose.onNodeWithContentDescription("More for ${second.displayName}").performClick()
        compose.onNodeWithText("Move to top").performClick()
        compose.onNodeWithTag("settings").performScrollToNode(hasTestTag("reset-aisles"))
        compose.onNodeWithTag("reset-aisles").performClick()
        compose.onNodeWithText("Keep").performClick()
        compose.onNodeWithTag("reset-aisles").performClick()
        compose.onNodeWithText("Reset").performClick()
        assertEquals(listOf("aisle $second to 3", "aisle $second to 0", "reset aisles"), events)
    }

    @Test
    fun metersReadAsOneSentenceAndTheWatchSwitchWaits() {
        show()
        val list = compose.onNodeWithTag("settings")
        list.performScrollToNode(hasTestTag("spend"))
        compose.onNodeWithContentDescription("This month: $0.04 of $2.00").assertExists()
        compose.onNodeWithContentDescription("Today: 3 of 50 calls").assertExists()
        compose.onNodeWithText("Between $0 and $100").assertExists()
        list.performScrollToNode(hasText("Vibrate on the watch when an aisle is done (coming with the watch app)"))
        compose.onNodeWithText("Vibrate on the watch when an aisle is done (coming with the watch app)").assertIsNotEnabled()
    }

    @Test
    fun aSpinnerUntilTheSettingsAreRead() {
        show(SettingsUi())
        compose.onNodeWithTag("settings-loading").assertExists()
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
