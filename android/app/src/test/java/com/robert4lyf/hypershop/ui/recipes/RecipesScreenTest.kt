package com.robert4lyf.hypershop.ui.recipes

import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
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
        library =
            listOf(
                LibraryRow(9, "r:9", "Red lentil dal", null, 11, 1_700_000_000_000, onList = false),
                LibraryRow(1, "r:1", "Weeknight chilli", "https://example.com/chilli", 14, 1_760_000_000_000, onList = true),
            ),
    )

private val DETAIL =
    RecipeDetail(
        LibraryRow(9, "r:9", "Red lentil dal", null, 5, 1_700_000_000_000, onList = false),
        servings = 4,
        sections =
            listOf(
                null to listOf("200g red lentils", "1 onion, finely chopped", "2 garlic cloves", "1 tsp turmeric"),
                "For the dressing" to listOf("Juice of 1 lemon"),
            ),
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
        compose.onNodeWithContentDescription("Serves 4 · 6 items on the list · 3 in stock").assertExists()
        compose.onNodeWithText("Add the in-stock items now").performClick()
        // Only the chilli has in-stock lines, so there is one such button (onNodeWithText above
        // would fail on two); every use can be removed, after a confirmation.
        compose.onAllNodesWithText("Remove from list").assertCountEquals(3)
        compose.onNodeWithContentDescription("Serves 8 · 5 items on the list").assertExists()
        compose.onAllNodesWithText("Remove from list")[1].performClick()
        compose.onNodeWithText("Keep").performClick()
        compose.onAllNodesWithText("Remove from list")[1].performClick()
        compose.onNodeWithText("Remove").performClick()
        assertEquals(listOf("in stock 1", "remove 2"), events)
    }

    @Test
    fun addARecipeByLinkOrByHand() {
        val got = mutableListOf<String>()
        val add = AddRecipeActions(onLink = { got += "link $it" }, onManual = { n, sv, l -> got += "manual $n $sv ${l.lines().size}" })
        var done = 0
        compose.setContent { HyperShopTheme { AddRecipeContent(add, onDone = { done++ }) } }
        compose.onNodeWithTag("add-link").performClick()
        compose.onNodeWithTag("link-field").performTextInput("https://example.com/soup")
        compose.onNodeWithTag("link-go").performClick()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithTag("add-manual").performClick()
        compose.onNodeWithTag("manual-go").assertIsNotEnabled()
        compose.onNodeWithTag("manual-name").performTextInput("Gran's soup")
        compose.onNodeWithTag("manual-serves").performTextInput("6")
        compose.onNodeWithTag("manual-lines").performTextInput("2 onions\n1 leek")
        compose.onNodeWithTag("manual-go").performClick()
        assertEquals(listOf("link https://example.com/soup", "manual Gran's soup 6 2"), got)
        assertEquals(2, done)
    }

    @Test
    fun tapASavedRecipeToSeeItsIngredients() {
        val opened = mutableListOf<String>()
        compose.setContent {
            HyperShopTheme(dynamicColor = false) {
                RecipesScreen(USES, onRemove = {}, onAddInStock = {}, library = LibraryActions(onOpen = { opened += it.key }))
            }
        }
        compose.onNodeWithTag("recipes").performScrollToNode(hasTestTag("lib-r:9"))
        compose.onNodeWithTag("lib-r:9").performClick()
        assertEquals(listOf("r:9"), opened)
    }

    @Test
    fun recipeDetailShowsTheIngredientsUnderTheirHeadings() {
        var added = 0
        compose.setContent { HyperShopTheme(dynamicColor = false) { RecipeDetailContent(DETAIL, onAdd = { added++ }) } }
        compose.onNodeWithText("For the dressing").assertExists()
        compose.onNodeWithText("200g red lentils").assertExists()
        compose.onNodeWithTag("detail-add").performClick()
        assertEquals(1, added)
        compose.onRoot().captureRoboImage(Shots.path("recipe-detail-light.png"))
    }

    @Test
    fun recordAddSheet() {
        compose.setContent {
            HyperShopTheme { AddRecipeContent(AddRecipeActions(), onDone = {}) }
        }
        compose.onRoot().captureRoboImage(Shots.path("add-recipe-light.png"))
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
