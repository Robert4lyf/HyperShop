package com.robert4lyf.hypershop.ui.review

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.robert4lyf.hypershop.core.model.AisleCode
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

internal val SAMPLE_REVIEW =
    ReviewUi(
        title = "Weeknight chilli",
        servings = 4,
        aisles =
            listOf(
                AisleCode.PRODUCE to
                    listOf(
                        ReviewRow("cat:onion", "onion", "2", false, false, AisleCode.PRODUCE),
                        ReviewRow("cat:garlic", "garlic", "3 cloves", false, false, AisleCode.PRODUCE),
                    ),
                AisleCode.MEAT_FISH to listOf(ReviewRow("cat:beef_mince", "beef mince", "500 g", false, false, AisleCode.MEAT_FISH)),
                AisleCode.SPICES_CONDIMENTS to
                    listOf(
                        ReviewRow("cat:salt", "salt", null, true, false, AisleCode.SPICES_CONDIMENTS),
                        ReviewRow(
                            "name:chipotle paste or sauce",
                            "chipotle paste",
                            "1 tbsp",
                            false,
                            true,
                            AisleCode.SPICES_CONDIMENTS,
                            checkReason = "has 'or'",
                        ),
                    ),
            ),
        badge = Badge.RULES,
        originalServings = 4,
        canAskClaude = true,
        claudeNote = "Claude couldn't be reached; the lines were read offline",
    )

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ReviewScreenTest {
    @get:Rule val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private fun show(
        ui: ReviewUi = SAMPLE_REVIEW,
        dark: Boolean = false,
    ) = compose.setContent {
        HyperShopTheme(dark = dark, dynamicColor = false) {
            ReviewScreen(
                ui,
                onInStock = { k, v -> events += "stock $k $v" },
                onRow = { events += "row $it" },
                onServings = { events += "servings $it" },
                onAdd = { events += "add" },
                onCancel = { events += "cancel" },
                onRetryClaude = { events += "retry" },
                onAisleInStock = { events += "aisle $it" },
                onClearInStock = { events += "clear" },
                onSaveForLater = { events += "save only" },
                onLater = { events += "later" },
            )
        }
    }

    @Test
    fun eachRowIsOneNodeWithAtMostThreeElements() {
        show()
        val node = compose.onNodeWithTag("row-cat:onion", useUnmergedTree = false).fetchSemanticsNode()
        val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        val toggle = node.children.count { it.config.getOrNull(SemanticsProperties.ToggleableState) != null }
        // name, quantity (what goes on) and the in-stock box.
        assertTrue("texts $texts", texts.containsAll(listOf("Onion", "+2")))
        assertTrue(texts.size + toggle <= 4) // "In stock" label + name + quantity + box, read as one node
        compose.onNodeWithTag("row-cat:onion").performClick()
        assertEquals("row cat:onion", events.last())
    }

    @Test
    fun aSavedRecipeBeingEditedHasNoInStockOrServings() {
        show(SAMPLE_REVIEW.copy(editing = true, savedOnly = true))
        compose.onAllNodesWithContentDescription("At home", substring = true).assertCountEquals(0)
        compose.onAllNodesWithText("Serves", substring = true).assertCountEquals(0)
        compose.onNodeWithText("Save changes").assertExists()
    }

    @Test
    fun headerAndActions() {
        show()
        // Step Z: one primary button; Save recipe only is in the menu, with Discard.
        compose.onNodeWithText("Add to list · 4 items").assertIsEnabled().performClick()
        compose.onNodeWithTag("save-for-later").assertDoesNotExist()
        compose.onNodeWithTag("review-more").performClick()
        compose.onNodeWithText("Save recipe only").performClick()
        compose.onNodeWithTag("review-more").performClick()
        compose.onNodeWithTag("discard-import").performClick()
        // Claude's status only because it failed, with Retry beside it; no badge or "Waiting for Claude".
        compose.onNodeWithTag("claude-note").assertExists()
        compose.onNodeWithText("Waiting for Claude").assertDoesNotExist()
        compose.onNodeWithText("Read offline").assertDoesNotExist()
        compose.onNodeWithText("Retry with Claude").performClick()
        compose.onNodeWithText("Serves 4").fetchSemanticsNode()
        // No help paragraphs.
        compose.onNodeWithTag("edit-hint").assertDoesNotExist()
        assertEquals(listOf("add", "save only", "cancel", "retry"), events)
    }

    @Test
    fun everyRowHasTheAtHomeToggleAndItIsTheOnePlaceToSaySo() {
        // Step Z: the row's pill is the one place a recipe's ingredient is said to be at home (the food-at-home check,
        // the row sheet's switch and its "Always at home" went), so every row shows it, on or off.
        show()
        val rows = SAMPLE_REVIEW.aisles.sumOf { it.second.size }
        compose.onAllNodesWithText("At home").assertCountEquals(rows)
        compose.onNodeWithContentDescription("At home: Onion").performClick()
        compose.onNodeWithTag("cupboard-hint").assertDoesNotExist()
        assertEquals(listOf("stock cat:onion true"), events)
    }

    @Test
    fun servingsUnknownStartsAtOneAndTheSummarySaysWhatHappens() {
        show(SAMPLE_REVIEW.copy(servings = null))
        compose.onNodeWithText("Serves ?").fetchSemanticsNode()
        compose.onNodeWithContentDescription("More servings").performClick()
        assertEquals(listOf("servings 1"), events)
        compose.onNodeWithTag("summary").assertTextEquals("4 to add · 1 at home")
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Check: has 'or'"))
        compose.onNodeWithText("Check: has 'or'").fetchSemanticsNode()
    }

    @Test
    fun theTitleIsAHeadingAndServingsIsAFullTarget() {
        show()
        compose.onNode(hasText(SAMPLE_REVIEW.title) and isHeading()).assertExists()
        compose.onNodeWithTag("servings").assertHeightIsAtLeast(48.dp)
    }

    @Test
    fun nothingToAddKeepsTheButtonButDisabled() {
        val all = SAMPLE_REVIEW.aisles.map { (a, rows) -> a to rows.map { it.copy(inStock = true) } }
        show(SAMPLE_REVIEW.copy(aisles = all, servings = 6))
        compose.onNodeWithText("Nothing to add — all at home").assertIsNotEnabled()
        // Also said in the hero, in full contrast: the disabled button is not the only statement.
        compose.onNodeWithTag("summary").assertTextContains("Nothing to add", substring = true)
        compose.onNodeWithTag("servings-original").assertTextEquals("(recipe: 4)")
        compose.onNodeWithText("Already on your shopping list").assertDoesNotExist()
    }

    @Test
    fun aRowTheListAlreadyCoversSaysSo() {
        show(SAMPLE_REVIEW.copy(aisles = listOf(AisleCode.PRODUCE to listOf(SAMPLE_REVIEW.aisles[0].second[0].copy(adds = false)))))
        compose.onNodeWithText("Already on your shopping list").fetchSemanticsNode()
        compose.onNodeWithTag("add").assertIsNotEnabled()
    }

    @Test
    fun noticeShowsWhenTheListChanged() {
        show(SAMPLE_REVIEW.copy(notice = "List changed — review updated", badge = Badge.CLAUDE, claudeNote = null))
        compose.onNodeWithText("List changed — review updated").fetchSemanticsNode()
        // Read by Claude, as it should be: nothing said about it, and nothing to retry.
        compose.onNodeWithText("Read by Claude").assertDoesNotExist()
        compose.onNodeWithTag("ask-claude").assertDoesNotExist()
    }

    @Test
    fun recordLight() {
        show()
        compose.onRoot().captureRoboImage(Shots.path("review-light.png"))
    }

    @Test
    fun recordWithPhoto() {
        show(SAMPLE_REVIEW.copy(photo = TestPhotos.dish))
        TestPhotos.settle(compose)
        compose.onRoot().captureRoboImage(Shots.path("review-photo-light.png"))
    }

    @Test
    fun recordDark() {
        show(dark = true)
        compose.onRoot().captureRoboImage(Shots.path("review-dark.png"))
    }
}
