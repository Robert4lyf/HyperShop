package com.robert4lyf.hypershop.ui.recipes

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.robert4lyf.hypershop.core.merge.UseProgress
import com.robert4lyf.hypershop.data.UseSummary
import com.robert4lyf.hypershop.testutil.Shots
import com.robert4lyf.hypershop.testutil.TestPhotos
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val USES =
    RecipesUi(
        listOf(
            UseSummary(1, 1, "Weeknight chilli", "https://example.com/chilli", 4, UseProgress(6, 0, 0, 0), 3, libraryKey = "r:1"),
            UseSummary(2, 2, "Lemon drizzle cake", null, 8, UseProgress(5, 0, 0, 0), 0),
            UseSummary(3, null, "Added by hand", null, null, UseProgress(2, 0, 0, 0), 0),
        ),
        loaded = true,
        library =
            listOf(
                LibraryRow(9, "r:9", "Red lentil dal", null, 11, 1_700_000_000_000, onList = false, writtenFor = 4),
                LibraryRow(
                    1,
                    "r:1",
                    "Weeknight chilli",
                    "https://example.com/chilli",
                    14,
                    1_760_000_000_000,
                    onList = true,
                    photo = TestPhotos.dish,
                    servings = 4,
                    listCount = 1,
                    timesAdded = 3,
                    lastAddedToList = 1_760_000_000_000,
                ),
            ),
        photos = mapOf("r:1" to TestPhotos.dish),
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

    /** The recipes in this shop (the tab opens on saved recipes, X.12). */
    private fun show(dark: Boolean = false) {
        compose.setContent {
            HyperShopTheme(dark = dark, dynamicColor = false) {
                RecipesScreen(USES)
            }
        }
    }

    @Test
    fun aCardHasOneStatusLineTheSiteElseWhatItServes() {
        // Step AD.5: one status line that never wraps: the source site if there is one, else "Serves N"; "last added"
        // is on the recipe's own page, not the card.
        show()
        compose.onNodeWithTag("lib-status-r:9", useUnmergedTree = true).assertTextEquals("Serves 4")
        compose.onNodeWithTag("lib-status-r:1", useUnmergedTree = true).assertTextEquals("example.com")
        compose.onAllNodesWithText("last added", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("ingredients", substring = true).assertCountEquals(0)
    }

    @Test
    fun theRecipePageSaysWhenItLastWentOnTheList() {
        val detail = DETAIL.copy(row = USES.library.first { it.key == "r:1" })
        compose.setContent { HyperShopTheme(dynamicColor = false) { RecipeDetailContent(detail, onAdd = {}) } }
        compose.onNodeWithText("last added", substring = true).assertExists()
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
    fun onTheListTheCardShowsServingsInPlaceOfAddToListAndItsMenuRemovesIt() {
        // Step AA: "Add to list" only off the list. Step AC: on it, a ticked "On your list" beside the servings.
        val got = mutableListOf<String>()
        compose.setContent {
            HyperShopTheme(dynamicColor = false) {
                RecipesScreen(
                    USES,
                    library =
                        LibraryActions(
                            onAdd = { got += "add ${it.key}" },
                            onServings = { r, n -> got += "serves ${r.key} $n" },
                            onRemoveFromList = { got += "remove ${it.key}" },
                        ),
                )
            }
        }
        val list = compose.onNodeWithTag("recipes")
        // Not on the list: Add to list.
        list.performScrollToNode(hasTestTag("lib-add-r:9"))
        compose.onNodeWithTag("lib-add-r:9").performClick()
        compose.onNodeWithTag("lib-serves-r:9").assertDoesNotExist()
        compose.onNodeWithTag("lib-on-list-r:9", useUnmergedTree = true).assertDoesNotExist()
        // On the list: "✓ On your list" (one node, read as such) and "− Serves 4 +", no Add to list.
        list.performScrollToNode(hasTestTag("lib-serves-r:1"))
        compose.onNodeWithTag("lib-on-list-r:1").assertTextEquals("On your list")
        compose.onNodeWithTag("lib-add-r:1").assertDoesNotExist()
        compose.onNodeWithTag("lib-serves-r:1").assertTextEquals("Serves 4")
        compose.onNodeWithTag("lib-serves-r:1-more").performClick()
        compose.onNodeWithTag("lib-serves-r:1-more").performClick()
        compose.onNodeWithTag("lib-serves-r:1").assertTextEquals("Serves 6")
        compose.onNodeWithContentDescription("More for Weeknight chilli").performClick()
        compose.onNodeWithTag("lib-remove-from-list-r:1").performClick()
        assertEquals(listOf("add r:9", "serves r:1 5", "serves r:1 6", "remove r:1"), got)
    }

    @Test
    fun anEmptyListWithSavedRecipesSaysSo() {
        compose.setContent {
            HyperShopTheme(dynamicColor = false) { RecipesScreen(USES.copy(uses = emptyList())) }
        }
        // The recipe book (cut 1 of the review): the saved recipes, whatever is in this shop.
        compose.onNodeWithTag("lib-r:9").assertExists()
        compose.onNodeWithTag("use-1").assertDoesNotExist()
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
                RecipesScreen(USES, library = LibraryActions(onOpen = { opened += it.key }))
            }
        }
        compose.onNodeWithTag("recipes").performScrollToNode(hasTestTag("lib-r:9"))
        compose.onNodeWithTag("lib-r:9").performClick()
        assertEquals(listOf("r:9"), opened)
    }

    @Test
    fun recipeDetailShowsTheKeptMethodNumberedUnderItsHeadings() {
        val kept = DETAIL.copy(row = DETAIL.row.copy(method = listOf("Rinse the lentils.", "For the tarka:", "Fry the cumin.")))
        compose.setContent { HyperShopTheme(dynamicColor = false) { RecipeDetailContent(kept, onAdd = {}, onRemove = {}) } }
        compose.onNodeWithTag("recipe-detail").performScrollToNode(hasTestTag("method"))
        compose.onNodeWithText("Method").assertExists()
        compose.onNodeWithTag("recipe-detail").performScrollToNode(hasContentDescription("Step 2. Fry the cumin."))
        compose.onNodeWithText("For the tarka").assertExists()
        compose.onNodeWithContentDescription("Step 1. Rinse the lentils.").assertExists()
        compose.onRoot().captureRoboImage(Shots.path("recipe-method-light.png"))
        // Steps are numbered through; a heading takes no number.
        assertEquals(listOf(1, null, 2), methodLines(kept.row.method).map { it.number })
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
    fun cookingViewUsesOnlySavedTextAndKeepsServingContext() {
        compose.setContent {
            HyperShopTheme(dynamicColor = false) { RecipeDetailContent(DETAIL, onAdd = {}) }
        }
        compose.onNodeWithTag("cook-recipe").performClick()
        compose.onNodeWithText("Ingredients as saved").assertExists()
        compose.onNode(hasText("200g red lentils") and hasAnyAncestor(hasTestTag("cooking-view"))).assertExists()
        compose.onNodeWithTag("cooking-view").captureRoboImage(Shots.path("cooking-view-light.png"))
        compose.onNodeWithText("Back to recipe").performClick()
        compose.onNodeWithTag("cooking-view").assertDoesNotExist()
    }

    @Test
    fun cookingViewOffersTheOriginalOnlyWithALink() {
        // Step Z: no paragraph about what the view holds; no link and no book, and it invents nothing.
        var detail by mutableStateOf(DETAIL)
        compose.setContent { HyperShopTheme(dynamicColor = false) { RecipeDetailContent(detail, onAdd = {}) } }
        compose.onNodeWithTag("cook-recipe").performClick()
        compose.onNodeWithTag("cooking-scope").assertDoesNotExist()
        compose.onNodeWithTag("cooking-original").assertDoesNotExist()
        compose.onNodeWithText("Back to recipe").performClick()
        // From a book: where the method is.
        detail = DETAIL.copy(row = DETAIL.row.copy(bookRef = "Ottolenghi Simple · p. 142"))
        compose.onNodeWithTag("cook-recipe").performClick()
        compose.onNodeWithTag("cooking-original").assertDoesNotExist()
        compose.onNodeWithText("Back to recipe").performClick()
        // From a page: the original is one tap away.
        detail = DETAIL.copy(row = DETAIL.row.copy(url = "https://recipes.example/dal"))
        compose.onNodeWithTag("cook-recipe").performClick()
        compose.onNodeWithTag("cooking-original").assertExists()
        compose.onNodeWithTag("cooking-view").captureRoboImage(Shots.path("cooking-view-link-light.png"))
    }

    @Test
    fun recordAddSheet() {
        compose.setContent {
            HyperShopTheme { AddRecipeContent(AddRecipeActions(), onDone = {}) }
        }
        compose.onRoot().captureRoboImage(Shots.path("add-recipe-light.png"))
    }

    @Test
    fun theTabIsTheRecipeBookAndSaysWhatIsOnTheList() {
        val ui by mutableStateOf(USES.copy(uses = USES.uses.take(1)))
        compose.setContent { HyperShopTheme(dynamicColor = false) { RecipesScreen(ui) } }
        // The recipe book (X.12, cut 1 of the review): this shop's recipes are on the Shopping tab's recipe view.
        compose.onNodeWithTag("lib-r:9").assertExists()
        compose.onNodeWithTag("use-1").assertDoesNotExist()
        // On the list: its servings, changed in place (AA.1).
        compose.onNodeWithTag("lib-serves-r:1").assertExists()
        TestPhotos.settle(compose)
        compose.onRoot().captureRoboImage(Shots.path("recipes-library-light.png"))
    }

    @Test
    fun aRecipeOnTheListShowsItsInStockLines() {
        var edited = 0
        val detail =
            UseDetail(
                USES.uses.first(),
                writtenFor = 6,
                sections =
                    listOf(
                        null to listOf("500g beef mince" to false, "2 onions" to false, "1 tsp salt" to true),
                        "To serve" to listOf("Soured cream" to true),
                    ),
            )
        compose.setContent {
            HyperShopTheme(dynamicColor = false) { UseDetailContent(detail, onEdit = { edited++ }, onRemove = {}, photo = TestPhotos.dish) }
        }
        compose.onNodeWithText("Serves 4 (written for 6)", substring = true).assertExists()
        compose.onAllNodesWithText("At home").assertCountEquals(2)
        compose.onNodeWithTag("use-detail-edit").performClick()
        assertEquals(1, edited)
        compose.onNodeWithTag("use-photo").assertExists()
        TestPhotos.settle(compose)
        compose.onRoot().captureRoboImage(Shots.path("use-detail-light.png"))
    }

    @Test
    fun recordLight() {
        show()
        TestPhotos.settle(compose)
        compose.onRoot().captureRoboImage(Shots.path("recipes-light.png"))
    }

    @Test
    fun recordDark() {
        show(dark = true)
        TestPhotos.settle(compose)
        compose.onRoot().captureRoboImage(Shots.path("recipes-dark.png"))
    }

    @Test
    fun everyCardIsTheSameCompactRowWithASquareThumbnail() {
        // Step AD.5: no full-width photo band on any card: the chilli's photo is cropped into the same 48 dp square as
        // the dal's art, beside the title.
        show()

        fun bounds(tag: String) = compose.onNodeWithTag(tag, useUnmergedTree = true).getUnclippedBoundsInRoot()
        for (key in listOf("r:1", "r:9")) {
            compose.onNodeWithTag("lib-band-$key", useUnmergedTree = true).assertDoesNotExist()
            val thumb = bounds("lib-thumb-$key")
            assertEquals(48f, (thumb.right - thumb.left).value, 1f)
            assertEquals(48f, (thumb.bottom - thumb.top).value, 1f)
            assertTrue(bounds("lib-title-$key").left > thumb.right)
        }
        val chilli = bounds("lib-r:1")
        val dal = bounds("lib-r:9")
        // Both cards the same shape: on the list or not, about one height.
        assertEquals((dal.bottom - dal.top).value, (chilli.bottom - chilli.top).value, 8f)
    }

    @Test
    fun theRecipeSheetTakesStarsNotesAndAPhoto() {
        val photo =
            java.io.File.createTempFile("dal", ".jpg").apply {
                val b = android.graphics.Bitmap.createBitmap(640, 360, android.graphics.Bitmap.Config.ARGB_8888)
                b.eraseColor(android.graphics.Color.rgb(210, 120, 40))
                outputStream().use { b.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }
            }
        var row by mutableStateOf(DETAIL.row.copy(url = "https://example.com/dal", rating = 4, notes = "Simmer 10 min longer"))
        val got = mutableListOf<String>()
        val actions =
            RecipeNoteActions(
                onRate = { _, n -> got += "rate $n" },
                onNotes = { _, t -> got += "notes $t" },
                onChoosePhoto = { got += "choose" },
                onPagePhoto = { got += "page" },
                onRemovePhoto = { got += "remove" },
            )
        compose.setContent {
            HyperShopTheme(
                dynamicColor = false,
            ) { RecipeDetailContent(DETAIL.copy(row = row), onAdd = {}, notes = actions) }
        }
        // The rating is one choice of five, read as such.
        compose.onNodeWithTag("rating").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription, "4 of 5 stars"))
        compose.onNodeWithTag("rate-2").performClick()
        // Notes are saved on Save only: typing alone changes nothing.
        compose.onNodeWithText("Simmer 10 min longer").assertExists()
        compose.onNodeWithTag("cooking-notes-save").assertDoesNotExist()
        compose.onNodeWithTag("cooking-notes").performTextReplacement("Simmer 10 min longer. Half the chilli.")
        compose.onNodeWithTag("cooking-notes-save").performClick()
        // No photo yet, and the recipe has a page: its photo can be fetched, or one chosen.
        compose.onNodeWithTag("photo-page").performClick()
        compose.onNodeWithTag("photo-choose").performClick()
        compose.onNodeWithTag("photo-remove").assertDoesNotExist()
        assertEquals(listOf("rate 2", "notes Simmer 10 min longer. Half the chilli.", "page", "choose"), got)
        // With a photo: it heads the sheet, and can be removed or changed.
        row = row.copy(photo = photo.path)
        compose.waitForIdle()
        compose.onNodeWithTag("recipe-photo").assertExists()
        compose.onNodeWithTag("photo-page").assertDoesNotExist()
        compose.onNodeWithText("Change photo", substring = true).assertExists()
        compose.onNodeWithTag("photo-remove").performClick()
        assertEquals("remove", got.last())
        // The photo is read off the main thread: let it arrive before the picture is taken.
        Thread.sleep(500)
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(Shots.path("recipe-notes-light.png"))
    }

    @Test
    fun aRatingIsOnTheRecipesPageNotItsCard() {
        // Step AD.5: the card's one status line is the site or the servings; the stars are on the recipe's page.
        val rated = USES.copy(library = USES.library.map { if (it.key == "r:9") it.copy(rating = 5) else it })
        compose.setContent { HyperShopTheme(dynamicColor = false) { RecipesScreen(rated) } }
        compose.onAllNodesWithText("★ 5/5", substring = true).assertCountEquals(0)
        compose.onNodeWithTag("lib-status-r:9", useUnmergedTree = true).assertTextEquals("Serves 4")
    }

    @Test
    fun sortAndTheHighlyRatedChipWaitForTenRecipesAndSayWhenNothingIsRatedSo() {
        val got = mutableListOf<String>()
        // Step Z: with fewer than ten saved recipes, no sort and no filter.
        var ui by mutableStateOf(USES)
        compose.setContent {
            HyperShopTheme(dynamicColor = false) {
                RecipesScreen(
                    ui,
                    library = LibraryActions(onSort = { got += "sort ${it.name}" }, onHighlyRated = { got += "rated $it" }),
                )
            }
        }
        compose.onNodeWithTag("library-sort").assertDoesNotExist()
        compose.onNodeWithTag("filter-highly-rated").assertDoesNotExist()
        ui = USES.copy(savedCount = SORT_FROM)
        compose.waitForIdle()
        compose.onNodeWithTag("library-sort").performClick()
        compose.onNodeWithTag("sort-RATING").performClick()
        compose.onNodeWithTag("filter-highly-rated").performClick()
        assertEquals(listOf("sort RATING", "rated true"), got)
        ui = USES.copy(library = emptyList(), highlyRated = true)
        compose.waitForIdle()
        compose.onNodeWithText("No recipes rated 4★ or 5★ yet").assertExists()
    }
}
