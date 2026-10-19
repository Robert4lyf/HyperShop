package com.robert4lyf.hypershop.ui.recipes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
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
    fun aRecipeAlreadyOnTheListAsksBeforeAddingAgain() {
        val added = mutableListOf<String>()
        compose.setContent {
            HyperShopTheme(dynamicColor = false) {
                RecipesScreen(USES, onRemove = {}, onAddInStock = {}, library = LibraryActions(onAdd = { added += it.key }))
            }
        }
        compose.onNodeWithTag("view-library").performClick()
        val list = compose.onNodeWithTag("recipes")
        // Not on the list: straight in.
        list.performScrollToNode(hasTestTag("lib-add-r:9"))
        compose.onNodeWithTag("lib-add-r:9").performClick()
        // On the list: "Add again" asks first.
        list.performScrollToNode(hasTestTag("lib-add-r:1"))
        compose.onNodeWithTag("lib-add-r:1").performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithTag("lib-add-r:1").performClick()
        compose.onNodeWithTag("add-again-confirm").performClick()
        assertEquals(listOf("r:9", "r:1"), added)
    }

    @Test
    fun anEmptyListWithSavedRecipesSaysSo() {
        compose.setContent {
            HyperShopTheme(dynamicColor = false) { RecipesScreen(USES.copy(uses = emptyList()), onRemove = {}, onAddInStock = {}) }
        }
        // An empty list opens on the library; the list says it is empty.
        compose.onNodeWithTag("lib-r:9").assertExists()
        compose.onNodeWithTag("view-list").performClick()
        compose.onNodeWithTag("nothing-yet").assertExists()
        compose.onNodeWithText("Nothing on the list").assertExists()
    }

    @Test
    fun thePhotoOptionNeedsAKey() {
        compose.setContent { HyperShopTheme { AddRecipeContent(AddRecipeActions(), onDone = {}, hasKey = false) } }
        compose.onNodeWithText("Needs a Claude key").assertExists()
        compose.onNodeWithTag("add-photo").performClick()
        compose.onNodeWithTag("photo-camera").assertIsNotEnabled()
        compose.onNodeWithTag("photo-pick").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Back").performClick()
        compose.onNodeWithText("Needs a Claude key").assertExists()
    }

    @Test
    fun withAKeyThePhotoButtonsWork() {
        compose.setContent { HyperShopTheme { AddRecipeContent(AddRecipeActions(), onDone = {}, start = "PHOTO") } }
        compose.onNodeWithTag("photo-camera").assertIsEnabled()
        compose.onNodeWithTag("photo-pick").assertIsEnabled()
    }

    @Test
    fun thePhotoFormStartsWithTheLastBookAndTakesAPage() {
        compose.setContent {
            HyperShopTheme(
                dynamicColor = false,
            ) { AddRecipeContent(AddRecipeActions(), onDone = {}, start = "PHOTO", lastBook = "Ottolenghi Simple") }
        }
        compose.onNodeWithTag("photo-book").assertTextContains("Ottolenghi Simple")
        compose.onNodeWithTag("photo-page").performTextInput("142")
        compose.onNodeWithTag("photo-page").assertTextContains("142")
        compose.onRoot().captureRoboImage(Shots.path("add-photo-light.png"))
    }

    @Test
    fun tapASavedRecipeToSeeItsIngredients() {
        val opened = mutableListOf<String>()
        compose.setContent {
            HyperShopTheme(dynamicColor = false) {
                RecipesScreen(USES, onRemove = {}, onAddInStock = {}, library = LibraryActions(onOpen = { opened += it.key }))
            }
        }
        compose.onNodeWithTag("view-library").performClick()
        compose.onNodeWithTag("recipes").performScrollToNode(hasTestTag("lib-r:9"))
        compose.onNodeWithTag("lib-r:9").performClick()
        assertEquals(listOf("r:9"), opened)
    }

    @Test
    fun recipeDetailShowsTheIngredientsUnderTheirHeadings() {
        var added = 0
        var removed = 0
        compose.setContent {
            HyperShopTheme(dynamicColor = false) { RecipeDetailContent(DETAIL, onAdd = { added++ }, onRemove = { removed++ }) }
        }
        compose.onNodeWithText("For the dressing").assertExists()
        compose.onNodeWithText("200g red lentils").assertExists()
        compose.onNodeWithTag("detail-add").performClick()
        compose.onNodeWithTag("detail-remove").performClick()
        assertEquals(1, added)
        assertEquals(1, removed)
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
    fun theListAndTheLibraryAreSeparateAndANewRecipeShowsOnTheList() {
        var ui by mutableStateOf(USES.copy(uses = USES.uses.take(1)))
        compose.setContent { HyperShopTheme(dynamicColor = false) { RecipesScreen(ui, onRemove = {}, onAddInStock = {}) } }
        // Opens on the list: its cards, not the library's.
        compose.onNodeWithText("Remove from list").assertExists()
        compose.onNodeWithTag("lib-r:9").assertDoesNotExist()
        compose.onNodeWithTag("view-library").performClick()
        compose.onNodeWithTag("lib-r:9").assertExists()
        compose.onNodeWithText("Remove from list").assertDoesNotExist()
        compose.onRoot().captureRoboImage(Shots.path("recipes-library-light.png"))
        // A recipe added to the list brings the list back.
        ui = USES
        compose.waitForIdle()
        compose.onAllNodesWithText("Remove from list").assertCountEquals(3)
        compose.onNodeWithTag("lib-r:9").assertDoesNotExist()
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
