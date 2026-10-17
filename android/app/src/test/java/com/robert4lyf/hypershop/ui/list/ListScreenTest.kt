package com.robert4lyf.hypershop.ui.list

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
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
            onDelete = { events += "delete ${it.id}" },
            onFinish = { events += "finish" },
            onClearAll = { events += "clear" },
        )

    private fun show(dark: Boolean = false) =
        compose.setContent {
            HyperShopTheme(dark = dark, dynamicColor = false) { ListScreen(SAMPLE_LIST, actions) }
        }

    @Test
    fun rowsAreSingleNodesWithStateAndActions() {
        show()
        val node = compose.onNodeWithTag("item-1").fetchSemanticsNode()
        assertEquals("Partly ticked", node.config.getOrNull(SemanticsProperties.StateDescription))
        assertEquals(listOf("1 more onion, 2 already ticked"), node.config.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals(listOf("Delete", "Details"), node.config[SemanticsActions.CustomActions].map { it.label })
        compose.onNodeWithTag("item-1").performClick()
        val delete =
            compose.onNodeWithTag(
                "item-4",
            ).fetchSemanticsNode().config[SemanticsActions.CustomActions].first { it.label == "Delete" }
        compose.runOnIdle { delete.action() }
        assertEquals("Ticked", compose.onNodeWithTag("item-3").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        compose.onNodeWithText("Finish shopping").performClick()
        compose.onNodeWithText("5 left").fetchSemanticsNode()
        assertEquals(listOf("toggle 1", "delete 4", "finish"), events)
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
        compose.onNodeWithText("Paste link").performClick()
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
