package com.robert4lyf.hypershop.ui.review

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
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
        badge = Badge.CLAUDE_PENDING,
        originalServings = 4,
        canAskClaude = true,
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
        compose.onNodeWithText("Add 4 items").assertIsEnabled().performClick()
        compose.onNodeWithText("Cancel").performClick()
        compose.onNodeWithText("Waiting for Claude").fetchSemanticsNode()
        compose.onNodeWithText("Retry with Claude").performClick()
        compose.onNodeWithText("Serves 4").fetchSemanticsNode()
        assertEquals(listOf("add", "cancel", "retry"), events)
    }

    @Test
    fun theInStockPillTogglesWithoutOpeningTheSheet() {
        show()
        compose.onAllNodesWithText("At home")[0].performClick()
        compose.onAllNodesWithText("At home")[3].performClick()
        assertEquals(listOf("stock cat:onion true", "stock cat:salt false"), events)
    }

    @Test
    fun servingsUnknownStartsAtOneAndTheSummarySaysWhatHappens() {
        show(SAMPLE_REVIEW.copy(servings = null))
        compose.onNodeWithText("Serves ?").fetchSemanticsNode()
        compose.onNodeWithContentDescription("More servings").performClick()
        assertEquals(listOf("servings 1"), events)
        compose.onNodeWithTag("summary").assertTextEquals("4 to add · 1 at home · 1 to check")
        compose.onNodeWithText("Check: has 'or'").fetchSemanticsNode()
    }

    @Test
    fun nothingToAddKeepsTheButtonButDisabled() {
        val all = SAMPLE_REVIEW.aisles.map { (a, rows) -> a to rows.map { it.copy(inStock = true) } }
        show(SAMPLE_REVIEW.copy(aisles = all, servings = 6))
        compose.onNodeWithText("Nothing to add — all at home").assertIsNotEnabled()
        compose.onNodeWithTag("servings-original").assertTextEquals("(recipe: 4)")
        compose.onNodeWithText("Already on your list").assertDoesNotExist()
    }

    @Test
    fun aRowTheListAlreadyCoversSaysSo() {
        show(SAMPLE_REVIEW.copy(aisles = listOf(AisleCode.PRODUCE to listOf(SAMPLE_REVIEW.aisles[0].second[0].copy(adds = false)))))
        compose.onNodeWithText("Already on your list").fetchSemanticsNode()
        compose.onNodeWithTag("add").assertIsNotEnabled()
    }

    @Test
    fun noticeShowsWhenTheListChanged() {
        show(SAMPLE_REVIEW.copy(notice = "List changed — review updated", badge = Badge.CLAUDE))
        compose.onNodeWithText("List changed — review updated").fetchSemanticsNode()
        compose.onNodeWithText("Read by Claude").fetchSemanticsNode()
    }

    @Test
    fun recordLight() {
        show()
        compose.onRoot().captureRoboImage(Shots.path("review-light.png"))
    }

    @Test
    fun recordDark() {
        show(dark = true)
        compose.onRoot().captureRoboImage(Shots.path("review-dark.png"))
    }
}
