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
import androidx.compose.ui.test.getUnclippedBoundsInRoot
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
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
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
                LibraryRow(9, "r:9", "Red lentil dal", null, 11, 1_700_000_000_000, onList = false),
                LibraryRow(
                    1,
                    "r:1",
                    "Weeknight chilli",
                    "https://example.com/chilli",
                    14,
                    1_760_000_000_000,
                    onList = true,
                    photo = TestPhotos.dish,
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

    private fun show(dark: Boolean = false) =
        compose.setContent {
            HyperShopTheme(dark = dark, dynamicColor = false) {
                RecipesScreen(USES, onRemove = { events += "remove ${it.useId}" })
            }
        }

    @Test
    fun removeIsOneTapWithUndoOnTheMessage() {
        show()
        compose.onNodeWithContentDescription("Serves 4 · 6 still needed · 3 at home").assertExists()
        // Every use can be removed from its menu; the snackbar's Undo puts it back.
        compose.onNodeWithContentDescription("Serves 8 · 5 still needed").assertExists()
        compose.onNodeWithTag("more-2").performClick()
        compose.onNodeWithTag("remove-2").performClick()
        assertEquals(listOf("remove 2"), events)
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
                RecipesScreen(USES, onRemove = {}, library = LibraryActions(onAdd = { added += it.key }))
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
            HyperShopTheme(dynamicColor = false) { RecipesScreen(USES.copy(uses = emptyList()), onRemove = {}) }
        }
        // An empty list opens on the library; the list says it is empty.
        compose.onNodeWithTag("lib-r:9").assertExists()
        compose.onNodeWithTag("view-list").performClick()
        compose.onNodeWithTag("nothing-yet").assertExists()
        compose.onNodeWithText("Nothing on the list", substring = true).assertExists()
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
                RecipesScreen(USES, onRemove = {}, library = LibraryActions(onOpen = { opened += it.key }))
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
        compose.setContent { HyperShopTheme(dynamicColor = false) { RecipesScreen(ui, onRemove = {}) } }
        // Opens on the list: its cards, not the library's.
        compose.onNodeWithTag("use-1").assertExists()
        compose.onNodeWithTag("lib-r:9").assertDoesNotExist()
        compose.onNodeWithTag("view-library").performClick()
        compose.onNodeWithTag("lib-r:9").assertExists()
        compose.onNodeWithTag("use-1").assertDoesNotExist()
        TestPhotos.settle(compose)
        compose.onRoot().captureRoboImage(Shots.path("recipes-library-light.png"))
        // A recipe added to the list brings the list back.
        ui = USES
        compose.waitForIdle()
        compose.onNodeWithTag("use-3").assertExists()
        compose.onNodeWithTag("lib-r:9").assertDoesNotExist()
    }

    @Test
    fun tapARecipeOnTheListToSeeItsIngredients() {
        val opened = mutableListOf<Long>()
        compose.setContent {
            HyperShopTheme(dynamicColor = false) { RecipesScreen(USES, onRemove = {}, onOpenUse = { opened += it.useId }) }
        }
        compose.onNodeWithTag("use-2").performClick()
        assertEquals(listOf(2L), opened)
        // "Typed into the list" said nothing the card doesn't.
        compose.onNodeWithText("Typed into the list").assertDoesNotExist()
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
    fun aRecipesPhotoIsItsBandOnBothTabs() {
        show()
        // On the list: the chilli, which has a photo, gets a tall band; the cake keeps its mosaic strip.
        val withPhoto = compose.onNodeWithTag("use-1").getUnclippedBoundsInRoot()
        val without = compose.onNodeWithTag("use-2").getUnclippedBoundsInRoot()
        assertTrue((withPhoto.bottom - withPhoto.top) - (without.bottom - without.top) > 60.dp)
        // Saved: the same.
        compose.onNodeWithTag("view-library").performClick()
        val saved = compose.onNodeWithTag("lib-r:1").getUnclippedBoundsInRoot()
        val plain = compose.onNodeWithTag("lib-r:9").getUnclippedBoundsInRoot()
        assertTrue((saved.bottom - saved.top) - (plain.bottom - plain.top) > 60.dp)
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
    fun aRatedRecipeSaysSoOnItsCard() {
        val rated = USES.copy(library = USES.library.map { if (it.key == "r:9") it.copy(rating = 5) else it })
        compose.setContent { HyperShopTheme(dynamicColor = false) { RecipesScreen(rated, onRemove = {}) } }
        compose.onNodeWithTag("view-library").performClick()
        compose.onNodeWithText("★ 5/5", substring = true).assertExists()
    }

    @Test
    fun switchingBetweenOnTheListAndSavedLeavesTheLabelsWhereTheyAre() {
        compose.setContent { HyperShopTheme(dynamicColor = false) { RecipesScreen(USES, onRemove = {}) } }

        fun lefts() =
            listOf(
                "On the list",
                "Saved",
            ).map { compose.onNodeWithText(it, useUnmergedTree = true).getUnclippedBoundsInRoot().left }
        val before = lefts()
        compose.onNodeWithTag("view-library").performClick()
        compose.waitForIdle()
        // A checkmark on the selected side used to push its label along on every switch.
        assertEquals(before, lefts())
        compose.onNodeWithTag("view-list").performClick()
        compose.waitForIdle()
        assertEquals(before, lefts())
    }

    @Test
    fun savedHasASortAndAHighlyRatedChipAndSaysWhenNothingIsRatedSo() {
        val got = mutableListOf<String>()
        var ui by mutableStateOf(USES)
        compose.setContent {
            HyperShopTheme(dynamicColor = false) {
                RecipesScreen(
                    ui,
                    onRemove = {},
                    library = LibraryActions(onSort = { got += "sort ${it.name}" }, onHighlyRated = { got += "rated $it" }),
                )
            }
        }
        compose.onNodeWithTag("view-library").performClick()
        compose.onNodeWithTag("library-sort").performClick()
        compose.onNodeWithTag("sort-RATING").performClick()
        compose.onNodeWithTag("filter-highly-rated").performClick()
        assertEquals(listOf("sort RATING", "rated true"), got)
        ui = USES.copy(library = emptyList(), highlyRated = true)
        compose.waitForIdle()
        compose.onNodeWithText("No recipes rated 4★ or 5★ yet").assertExists()
    }
}
