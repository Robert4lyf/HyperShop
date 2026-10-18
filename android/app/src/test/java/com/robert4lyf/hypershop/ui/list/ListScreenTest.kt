package com.robert4lyf.hypershop.ui.list

import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
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
        compose.onNodeWithText("Finish shopping").performClick()
        compose.onNodeWithText("5 left").fetchSemanticsNode()
        assertEquals(listOf("open 1", "toggle 1", "finish"), events)
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
        compose.onNodeWithTag("view-toggle").performClick()
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
        compose.onNodeWithTag("need-cat:olive_oil").assertDoesNotExist()
        compose.onNodeWithTag("in-stock").performScrollTo().performClick()
        compose.onNodeWithText("2tbsp olive oil").assertExists()
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
            )
        compose.setContent {
            HyperShopTheme(dark = dark, dynamicColor = false) {
                ListScreen(ui, actions, header = { listHeader(ui, header) }, empty = { EmptyState(header.onSample) })
            }
        }
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
        compose.onNodeWithText("2 recipes ready to review").performClick()
        compose.onNodeWithText("Try a sample list").performClick()
        // Paste link lives on the Recipes tab only.
        compose.onNodeWithText("Paste link").assertDoesNotExist()
        assertEquals(listOf("review 7", "sample"), events)
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
