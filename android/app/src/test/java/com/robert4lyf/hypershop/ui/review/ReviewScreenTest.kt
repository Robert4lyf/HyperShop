package com.robert4lyf.hypershop.ui.review

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsOff
import androidx.compose.ui.test.assertIsOn
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
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
                        ReviewRow(
                            "cat:garlic",
                            "garlic",
                            "3 cloves",
                            false,
                            true,
                            AisleCode.PRODUCE,
                            checkReason = "a range that goes down",
                            raw = "3-2 cloves garlic",
                            range = RangeChoice(5, "3–2", listOf("3 cloves", "2 cloves")),
                        ),
                    ),
                AisleCode.MEAT_FISH to
                    listOf(
                        ReviewRow(
                            "cat:beef_mince",
                            "beef mince",
                            "500 g",
                            false,
                            false,
                            AisleCode.MEAT_FISH,
                            withList = "500 g · 1 kg with what's on your list",
                        ),
                    ),
                AisleCode.SPICES_CONDIMENTS to
                    listOf(
                        ReviewRow("cat:salt", "salt", null, true, false, AisleCode.SPICES_CONDIMENTS),
                        ReviewRow(
                            "name:chipotle paste or sauce",
                            "chipotle paste",
                            "1 tbsp",
                            false,
                            false,
                            AisleCode.SPICES_CONDIMENTS,
                            choice = Choice(9, "chipotle paste", "chipotle sauce"),
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
                edits = ReviewEdits(onChoose = { events += "choose $it" }, onSetAmount = { id, t -> events += "amount $id $t" }),
            )
        }
    }

    @Test
    fun eachRowIsOneNodeStatingNameAmountAndWhetherItIsAdded() {
        // Step AA: TalkBack reads the row (name, amount, "Will be added") and toggles it with the row's own action; the
        // checkbox is for the finger only.
        show()
        val node = compose.onNodeWithTag("row-cat:onion").fetchSemanticsNode()
        val texts = node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
        assertTrue("texts $texts", texts.containsAll(listOf("Onion", "2")))
        assertEquals("Will be added", node.config.getOrNull(SemanticsProperties.StateDescription))
        val action = node.config[SemanticsActions.CustomActions].single()
        assertEquals("Mark at home", action.label)
        compose.runOnIdle { action.action() }
        compose.onNodeWithTag("row-cat:onion").performClick()
        assertEquals(listOf("stock cat:onion true", "row cat:onion"), events)
    }

    @Test
    fun aSavedRecipeBeingEditedHasNoInStockOrServings() {
        show(SAMPLE_REVIEW.copy(editing = true, savedOnly = true))
        compose.onAllNodesWithTag("add-cat:onion", useUnmergedTree = true).assertCountEquals(0)
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
        compose.onNodeWithContentDescription("Retry with Claude").performClick()
        compose.onNodeWithText("Serves 4").fetchSemanticsNode()
        // No help paragraphs.
        compose.onNodeWithTag("edit-hint").assertDoesNotExist()
        assertEquals(listOf("add", "save only", "cancel", "retry"), events)
    }

    @Test
    fun everyRowHasACheckboxTickedMeansAddUntickedIsAtHome() {
        // Step AA: the pill became a leading checkbox. Ticked adds; an unticked row is at home and says so.
        show()
        val rows = SAMPLE_REVIEW.aisles.flatMap { it.second }
        rows.forEach { r ->
            val box = compose.onNodeWithTag("add-${r.key}", useUnmergedTree = true)
            if (r.inStock) box.assertIsOff() else box.assertIsOn()
        }
        compose.onAllNodesWithText("At home", substring = true).assertCountEquals(rows.count { it.inStock })
        compose.onNodeWithTag("add-cat:onion", useUnmergedTree = true).performClick()
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
        // Step AC: what was taken and what the recipe says, with a button for each reading; no "Check:" line.
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("3 cloves · recipe says 3–2"))
        compose.onNodeWithTag("reading-cat:garlic-2 cloves", useUnmergedTree = true).performClick()
        assertTrue(events.last() == "amount 5 2 cloves")
    }

    @Test
    fun amountsHaveNoPlusAndAChoiceIsTwoButtons() {
        // Step AA: what goes on, then what the list will hold in words; "A or B" as its two options, no "Check".
        show()
        compose.onNodeWithText("500 g · 1 kg with what's on your list", useUnmergedTree = true).fetchSemanticsNode()
        compose.onAllNodesWithText("+", substring = true, useUnmergedTree = true).assertCountEquals(0)
        compose.onNode(hasScrollAction()).performScrollToNode(hasText("Chipotle sauce"))
        compose.onNodeWithTag("chosen-9").assertIsSelected().performClick()
        compose.onNodeWithTag("other-9").assertIsNotSelected().performClick()
        compose.onAllNodesWithText("Check:", substring = true, useUnmergedTree = true).assertCountEquals(0)
        assertEquals(listOf("choose 9"), events)
    }

    @Test
    @Config(qualifiers = "w360dp-h640dp-xhdpi")
    fun onASmallPhoneTheIngredientsStartOnTheFirstScreen() {
        // Step AC: a plain, compact header (no picture behind it), so the first rows show without scrolling.
        // Step AD.3: Claude read it (no note under the title), as most reviews; the range row and three aisles stay.
        show(SAMPLE_REVIEW.copy(claudeNote = null))
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val bar = compose.onNodeWithTag("add").getUnclippedBoundsInRoot()
        val first = compose.onNodeWithTag("row-cat:onion").getUnclippedBoundsInRoot()
        assertTrue("first row $first above the bar $bar in $root", first.bottom <= bar.top)
        // Step AD.3: the list's slim aisle rows, so at least four ingredients show under the header.
        val shown =
            listOf("cat:onion", "cat:garlic", "cat:beef_mince", "cat:salt", "name:chipotle paste or sauce").count { key ->
                compose.onAllNodesWithTag("row-$key").fetchSemanticsNodes().firstOrNull()?.let {
                    compose.onNodeWithTag("row-$key").getUnclippedBoundsInRoot().bottom <= bar.top
                } == true
            }
        compose.onRoot().captureRoboImage(Shots.path("review-360x640.png"))
        assertTrue("$shown ingredients above the bar", shown >= 4)
        // Each aisle heading is one 48 dp row, as on the list.
        val heading = compose.onNodeWithTag("review-aisle-PRODUCE").getUnclippedBoundsInRoot()
        assertTrue("a slim aisle row: $heading", heading.bottom - heading.top <= 56.dp)
        compose.onNodeWithText("2 to add", useUnmergedTree = true).assertExists()
    }

    @Test
    fun claudesNoteCanBePutAway() {
        show()
        compose.onNodeWithTag("claude-note").assertExists()
        compose.onNodeWithTag("claude-note-close").performClick()
        compose.onNodeWithTag("claude-note").assertDoesNotExist()
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
