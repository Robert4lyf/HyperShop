package com.robert4lyf.hypershop.ui.settings

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.testutil.Shots
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Step AC.2: an aisle opens to show its groups, which move with the aisles' own controls and labels. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class WalkingOrderScreenTest {
    @get:Rule val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private val ui =
        SettingsUi(
            aisles = AisleCode.entries.toList(),
            groups = mapOf(AisleCode.PRODUCE to listOf("Vegetables", "Salad", "Herbs", "Fruit", null)),
            loaded = true,
        )

    @Test
    fun anAisleOpensToItsGroupsWhichMoveLikeAisles() {
        compose.setContent {
            HyperShopTheme(dynamicColor = false) {
                AislesScreen(
                    ui,
                    SettingsActions(
                        onMoveGroup = { a, g, by -> events += "move $a $g $by" },
                        onMoveGroupTo = { a, g, i -> events += "to $a $g $i" },
                    ),
                )
            }
        }
        compose.onNodeWithTag("group-PRODUCE-Salad").assertDoesNotExist()
        compose.onNodeWithContentDescription("Show the groups in Produce").performScrollTo().performClick()
        compose.onNodeWithContentDescription("Move Salad up").performScrollTo().performClick()
        compose.onNodeWithTag("group-up-PRODUCE-Vegetables").performScrollTo().assertExists()
        compose.onNodeWithText("Everything else").performScrollTo().assertExists()
        compose.onNodeWithContentDescription("More for Salad").performScrollTo().performClick()
        compose.onNodeWithText("Move to top").performClick()
        compose.onRoot().captureRoboImage(Shots.path("walking-order-groups-light.png"))
        assertEquals(listOf("move PRODUCE Salad -1", "to PRODUCE Salad 0"), events)
    }
}
