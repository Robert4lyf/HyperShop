package com.robert4lyf.hypershop.ui.meals

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.robert4lyf.hypershop.core.merge.UseProgress
import com.robert4lyf.hypershop.testutil.Shots
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MealsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val events = mutableListOf<String>()
    private val ui =
        MealsUi(
            loaded = true,
            meals =
                listOf(
                    Meal(1, "Weeknight chilli", 4, UseProgress(6, 0, 0, 0), 2, "bbcgoodfood.com"),
                    Meal(2, "Red lentil dal", null, UseProgress(0, 0, 4, 0), 0, "Ottolenghi Simple · p. 142"),
                ),
            picks =
                listOf(
                    Pick(10, "k1", "Weeknight chilli", 9, true, "bbcgoodfood.com"),
                    Pick(11, "k2", "Red lentil dal", 7, true, "Ottolenghi Simple · p. 142"),
                    Pick(12, "k3", "Fish pie", 11, false, null),
                ),
            hasLibrary = true,
        )
    private val actions =
        MealsActions(
            onAdd = { events += "add ${it.title}" },
            onServings = { m, n -> events += "serves ${m.useId} $n" },
            onRemove = { events += "remove ${it.useId}" },
            onList = { events += "list" },
        )

    @Test
    fun mealsShowProgressServingsAndRemoveWithAsk() {
        compose.setContent { HyperShopTheme(dynamicColor = false) { MealsScreen(ui, actions) } }
        compose.onNodeWithText("2 meals · 6 items still needed").assertExists()
        compose.onNodeWithText("6 still needed · 2 at home · bbcgoodfood.com").assertExists()
        compose.onNodeWithText("All bought · Ottolenghi Simple · p. 142").assertExists()
        compose.onNodeWithTag("meal-more-1").performClick()
        compose.onNodeWithTag("meal-fewer-1").performClick()
        // No stepper for a recipe that doesn't say how many it serves.
        compose.onNodeWithTag("meal-serves-2").assertDoesNotExist()
        compose.onNodeWithTag("meal-remove-2").performClick()
        compose.onNodeWithText("Keep").performClick()
        compose.onNodeWithTag("meal-remove-2").performClick()
        compose.onNodeWithTag("meals-remove-confirm").performClick()
        compose.onNodeWithTag("meals-list").performClick()
        assertEquals(listOf("serves 1 1", "serves 1 -1", "remove 2", "list"), events)
    }

    @Test
    fun pickingAddsAndAPickOnTheListAsksFirst() {
        compose.setContent { HyperShopTheme(dynamicColor = false) { MealsScreen(ui, actions) } }
        compose.onNodeWithTag("pick-add-k3").performClick()
        // On the list already: no Add button; the row asks.
        compose.onNodeWithTag("pick-add-k1").assertDoesNotExist()
        compose.onNodeWithTag("pick-k1").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("pick-k1").performClick()
        compose.onNodeWithTag("meals-add-again").performClick()
        assertEquals(listOf("add Fish pie", "add Weeknight chilli"), events)
    }

    @Test
    fun emptyLibraryExplainsWhereRecipesComeFrom() {
        compose.setContent { HyperShopTheme(dynamicColor = false) { MealsScreen(MealsUi(loaded = true), actions) } }
        compose.onNodeWithText("No saved recipes yet").assertExists()
        compose.onNodeWithTag("meals-list").assertDoesNotExist()
    }

    @Test
    fun recordLight() {
        compose.setContent { HyperShopTheme(dynamicColor = false) { MealsScreen(ui, actions) } }
        compose.onRoot().captureRoboImage(Shots.path("meals-light.png"))
    }

    @Test
    fun recordDark() {
        compose.setContent { HyperShopTheme(dark = true, dynamicColor = false) { MealsScreen(ui, actions) } }
        compose.onRoot().captureRoboImage(Shots.path("meals-dark.png"))
    }
}
