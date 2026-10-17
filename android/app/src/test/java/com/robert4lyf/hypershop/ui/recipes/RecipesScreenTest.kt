package com.robert4lyf.hypershop.ui.recipes

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.robert4lyf.hypershop.data.UseSummary
import com.robert4lyf.hypershop.testutil.Shots
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val USES =
    RecipesUi(
        listOf(
            UseSummary(1, 1, "Weeknight chilli", "https://example.com/chilli", 4, 6, 3),
            UseSummary(2, 2, "Lemon drizzle cake", null, 8, 5, 0),
            UseSummary(3, null, "Added by hand", null, null, 2, 0),
        ),
        loaded = true,
    )

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class RecipesScreenTest {
    @get:Rule val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private fun show(dark: Boolean = false) =
        compose.setContent {
            HyperShopTheme(dark = dark, dynamicColor = false) {
                RecipesScreen(USES, onRemove = { events += "remove ${it.useId}" }, onAddInStock = { events += "in stock ${it.useId}" })
            }
        }

    @Test
    fun removeAsksFirstAndInStockOnlyWhenSomeWere() {
        show()
        compose.onNodeWithText("Serves 4 · 6 items on the list · 3 in stock").assertExists()
        compose.onNodeWithText("Add the in-stock items now").performClick()
        // Only the chilli has in-stock lines, so there is one such button (onNodeWithText above
        // would fail on two); every use can be removed, after a confirmation.
        compose.onAllNodesWithText("Remove from list").assertCountEquals(3)
        compose.onNodeWithText("Serves 8 · 5 items on the list").assertExists()
        compose.onAllNodesWithText("Remove from list")[1].performClick()
        compose.onNodeWithText("Keep").performClick()
        compose.onAllNodesWithText("Remove from list")[1].performClick()
        compose.onNodeWithText("Remove").performClick()
        assertEquals(listOf("in stock 1", "remove 2"), events)
    }

    @Test
    fun recordLight() {
        show()
        compose.onRoot().captureRoboImage(Shots.path("recipes-light.png"))
    }

    @Test
    fun recordDark() {
        show(dark = true)
        compose.onRoot().captureRoboImage(Shots.path("recipes-dark.png"))
    }
}
