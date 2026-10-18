package com.robert4lyf.hypershop.ui.list

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
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

internal val SAMPLE_LIST =
    ListUi(
        aisles =
            listOf(
                AisleCode.PRODUCE to
                    listOf(
                        ListRow(1, "onion", "1 more onion", "2 already ticked", TickState.PARTIAL, AisleCode.PRODUCE),
                        ListRow(2, "coriander", "coriander", "1 × 30 g pack", TickState.OPEN, AisleCode.PRODUCE),
                        ListRow(3, "carrot", "2 carrots", null, TickState.DONE, AisleCode.PRODUCE),
                    ),
                AisleCode.DAIRY_EGGS to
                    listOf(
                        ListRow(4, "milk", "700 ml milk", "1 × 4 pint (2.27 l) bottle", TickState.OPEN, AisleCode.DAIRY_EGGS),
                        ListRow(5, "butter", "250 g butter", "1 × 250 g block", TickState.OPEN, AisleCode.DAIRY_EGGS),
                    ),
                AisleCode.TINS_JARS to
                    listOf(
                        ListRow(6, "chopped tomatoes", "2 tins chopped tomatoes", "1 × 400 g tin", TickState.OPEN, AisleCode.TINS_JARS),
                    ),
            ),
        loaded = true,
    )

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ListScreenTest {
    @get:Rule val compose = createComposeRule()

    private val events = mutableListOf<String>()
    private val actions =
        ListActions(
            onToggle = { events += "toggle ${it.id}" },
            onOpenItem = { events += "open ${it.id}" },
            onFinish = { events += "finish" },
            onClearAll = { events += "clear" },
            onNeed = { events += "need ${it.key}" },
        )

    private fun show(dark: Boolean = false) =
        compose.setContent {
            HyperShopTheme(dark = dark, dynamicColor = false) { ListScreen(SAMPLE_LIST, actions) }
        }

    @Test
    fun onlyTheTickTicksAndTheRowOpensDetails() {
        show()
        val tick = compose.onNodeWithTag("tick-1").fetchSemanticsNode()
        assertEquals("Partly ticked", tick.config.getOrNull(SemanticsProperties.StateDescription))
        assertEquals(listOf("1 more onion"), tick.config.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals(Role.Checkbox, tick.config.getOrNull(SemanticsProperties.Role))
        compose.onNodeWithTag("item-1").performClick()
        compose.onNodeWithTag("tick-1").performClick()
        assertEquals("Ticked", compose.onNodeWithTag("tick-3").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        // "Finish shopping" sits in the bar at the bottom, in reach, once something is ticked.
        compose.onNodeWithText("Finish shopping").performClick()
        compose.onNodeWithTag("finish").assertExists()
        compose.onNodeWithText("5 left").fetchSemanticsNode()
        assertEquals(listOf("open 1", "toggle 1", "finish"), events)
    }

    @Test
    fun noFinishBarUntilSomethingIsTicked() {
        val ui = SAMPLE_LIST.copy(aisles = listOf(SAMPLE_LIST.aisles[1]))
        compose.setContent { HyperShopTheme(dynamicColor = false) { ListScreen(ui, actions) } }
        compose.onNodeWithTag("finish").assertDoesNotExist()
        compose.onNodeWithText("Finish shopping").assertDoesNotExist()
    }

    @Test
    fun rowsReadAlikeAndShowTheirRecipe() {
        val rows =
            listOf(
                ListRow(1, "milk", "700 ml milk", null, TickState.OPEN, AisleCode.DAIRY_EGGS, from = "Weeknight chilli"),
                ListRow(2, "onion", "1 more onion", "2 already ticked", TickState.PARTIAL, AisleCode.DAIRY_EGGS, from = "Weeknight chilli"),
                ListRow(3, "butter", "250 g butter", "1 × 250 g block", TickState.OPEN, AisleCode.DAIRY_EGGS, from = "Weeknight chilli"),
            )
        val ui = SAMPLE_LIST.copy(aisles = listOf(AisleCode.DAIRY_EGGS to rows))
        compose.setContent { HyperShopTheme(dynamicColor = false) { ListScreen(ui, actions) } }
        compose.onNodeWithText("700 ml Milk").assertExists()
        compose.onNodeWithText("1 more Onion").assertExists()
        compose.onNodeWithText("2 already ticked · Weeknight chilli").assertExists()
        compose.onNodeWithText("1 × 250 g block").assertExists()
        // The pack note keeps its line; the recipe goes under it.
        assertEquals(listOf("1 × 250 g block", "Weeknight chilli"), subLines(rows[2]))
        assertEquals("Coriander", displayMain("coriander", "coriander"))
        assertEquals("2 Leaves", displayMain("2 leaves", "leaf"))
        assertEquals("2 tins Chopped tomatoes", displayMain("2 tins chopped tomatoes", "chopped tomatoes"))
    }

    @Test
    fun loadingShowsASpinnerAndNoCount() {
        compose.setContent { HyperShopTheme(dynamicColor = false) { ListScreen(ListUi(), actions) } }
        compose.onNodeWithTag("loading").assertExists()
        compose.onNodeWithText("HyperShop").assertDoesNotExist()
        compose.onNodeWithText("SHOPPING LIST").assertExists()
    }

    @Test
    fun clearAllAsksFirst() {
        show()
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Clear all").performClick()
        compose.onNodeWithText("6 items. You can undo for 10 seconds.").assertExists()
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(emptyList<String>(), events)
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Clear all").performClick()
        compose.onNodeWithTag("clear-confirm").performClick()
        assertEquals(listOf("clear"), events)
    }

    @Test
    fun aislesCollapseAndExpand() {
        show()
        val header = compose.onNodeWithTag("aisle-PRODUCE")
        assertEquals("Expanded", header.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        header.performClick()
        compose.onNodeWithTag("item-1").assertDoesNotExist()
        compose.onNodeWithTag("item-4").assertExists()
        assertEquals("Collapsed", header.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        header.performClick()
        compose.onNodeWithTag("item-1").assertExists()
    }

    @Test
    fun categorisedShowsGroupLabelsAndTheToggle() {
        val rows =
            listOf(
                ListRow(1, "onion", "2 onions", null, TickState.OPEN, AisleCode.PRODUCE, "Vegetables"),
                ListRow(2, "basil", "basil", null, TickState.OPEN, AisleCode.PRODUCE, "Herbs"),
            )
        val ui = SAMPLE_LIST.copy(aisles = listOf(AisleCode.PRODUCE to rows), categorised = true)
        compose.setContent { HyperShopTheme(dynamicColor = false) { ListScreen(ui, actions.copy(onToggleView = { events += "view" })) } }
        compose.onNodeWithText("Vegetables").assertExists()
        compose.onNodeWithText("Herbs").assertExists()
        // The switch lives in the menu, as a checkable "Group by type".
        compose.onNodeWithContentDescription("More").performClick()
        val item = compose.onNodeWithTag("view-toggle")
        assertEquals(ToggleableState.On, item.fetchSemanticsNode().config.getOrNull(SemanticsProperties.ToggleableState))
        item.performClick()
        assertEquals(listOf("view"), events)
    }

    @Test
    fun inStockOpensAndNeedItAdds() {
        val ui =
            SAMPLE_LIST.copy(
                aisles = SAMPLE_LIST.aisles.take(1),
                inStock = listOf(InStockRow("cat:olive_oil", "olive oil", "2tbsp olive oil", "Weeknight chilli", AisleCode.OILS_VINEGARS)),
            )
        compose.setContent { HyperShopTheme(dynamicColor = false) { ListScreen(ui, actions) } }
        // Open to start with, so what to check isn't hidden; the header closes and reopens it.
        compose.onNodeWithText("2tbsp olive oil").assertExists()
        compose.onNodeWithTag("in-stock").performScrollTo().performClick()
        compose.onNodeWithTag("need-cat:olive_oil").assertDoesNotExist()
        compose.onNodeWithTag("in-stock").performScrollTo().performClick()
        compose.onNodeWithTag("need-cat:olive_oil").performScrollTo().performClick()
        assertEquals(listOf("need cat:olive_oil"), events)
    }

    private fun showWithHeader(
        ui: ListUi,
        dark: Boolean = false,
    ) {
        val header =
            HeaderActions(
                onAdd = { events += "add $it" },
                suggestions = { q -> if (q.startsWith("on")) listOf("onion", "spring onion") else emptyList() },
                onReview = { events += "review $it" },
                onSample = { events += "sample" },
                onPasteLink = { events += "paste" },
            )
        compose.setContent {
            HyperShopTheme(dark = dark, dynamicColor = false) {
                ListScreen(ui, actions, header = { listHeader(ui, header) }, empty = { EmptyState(header.onSample, header.onPasteLink) })
            }
        }
    }

    @Test
    fun aSuggestionKeepsTheAmountTyped() {
        val header =
            HeaderActions(
                onAdd = { events += "add $it" },
                suggestions = { q -> if (q.contains("on")) listOf("onion") else emptyList() },
                onReview = {},
                onSample = {},
                onPick = { typed, s -> events += "pick $typed $s" },
            )
        compose.setContent {
            HyperShopTheme(dynamicColor = false) { ListScreen(SAMPLE_LIST, actions, header = { listHeader(SAMPLE_LIST, header) }) }
        }
        compose.onNodeWithTag("add-item").performTextInput("2 on")
        compose.onNodeWithTag("suggestion-onion").performClick()
        assertEquals(listOf("pick 2 on onion"), events)
    }

    @Test
    fun plusOneSaysWhatItAdds() {
        var p by mutableStateOf(PlusOne(1, "onion", "onion"))
        compose.setContent { HyperShopTheme(dynamicColor = false) { PlusOneDialog(p, onYes = { events += "yes" }, onNo = {}) } }
        compose.onNodeWithText("Add one more onion to it?").assertExists()
        compose.onNodeWithText("Add").performClick()
        p = PlusOne(1, "onion", "200 g onion", amount = "200 g")
        compose.onNodeWithText("Add 200 g more onion to it?").assertExists()
        compose.onNodeWithText("Add 200 g").performClick()
        assertEquals(listOf("yes", "yes"), events)
    }

    @Test
    fun addFieldEnterSuggestionsAndPaste() {
        showWithHeader(SAMPLE_LIST)
        val field = compose.onNodeWithTag("add-item")
        field.performTextInput("on")
        compose.onNodeWithTag("suggestion-spring onion").performClick()
        field.performTextInput("2 leeks")
        field.performImeAction()
        // The field is cleared and keeps focus, so the keyboard stays up.
        field.assertIsFocused()
        field.performTextInput("milk\neggs")
        assertEquals(listOf("add spring onion", "add 2 leeks", "add milk\neggs"), events)
    }

    @Test
    fun readyCardAndEmptyState() {
        showWithHeader(ListUi(loaded = true, readyJobs = listOf(7, 8)))
        val card = compose.onNodeWithTag("ready-card")
        assertEquals(Role.Button, card.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        compose.onNodeWithText("Tap to review the first; the rest follow").assertExists()
        // Several waiting: the card opens the oldest.
        compose.onNodeWithText("2 recipes ready to review").performClick()
        compose.onNodeWithText("Try a sample list").performClick()
        // "Paste a link" imports what is on the clipboard, like a share.
        compose.onNodeWithText("Paste a link").performClick()
        assertEquals(listOf("review 7", "sample", "paste"), events)
    }

    @Test
    fun recordEmptyLight() {
        showWithHeader(ListUi(loaded = true, readyJobs = listOf(7)))
        compose.onRoot().captureRoboImage(Shots.path("list-empty-light.png"))
    }

    @Test
    fun recordEmptyDark() {
        showWithHeader(ListUi(loaded = true, readyJobs = listOf(7)), dark = true)
        compose.onRoot().captureRoboImage(Shots.path("list-empty-dark.png"))
    }

    @Test
    fun recordLight() {
        show()
        compose.onRoot().captureRoboImage(Shots.path("list-light.png"))
    }

    @Test
    fun recordDark() {
        show(dark = true)
        compose.onRoot().captureRoboImage(Shots.path("list-dark.png"))
    }
}
