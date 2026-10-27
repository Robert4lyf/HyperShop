package com.robert4lyf.hypershop.ui.list

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
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
import com.robert4lyf.hypershop.settings.ListView
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

internal val SAMPLE_LIST =
    ListUi(
        aisles =
            listOf(
                AisleCode.PRODUCE to
                    listOf(
                        ListRow(1, "onion", "1 more onion", "2 already bought", TickState.PARTIAL, AisleCode.PRODUCE),
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
            onArrange = { events += "arrange $it" },
            onMoveItem = { r, by -> events += "move ${r.id} $by" },
            onMoveGroup = { a, g, by -> events += "group $a $g $by" },
        )

    private fun show(dark: Boolean = false) =
        compose.setContent {
            HyperShopTheme(dark = dark, dynamicColor = false) { ListScreen(SAMPLE_LIST, actions) }
        }

    @Test
    fun onlyTheTickTicksAndTheRowOpensDetails() {
        show()
        val tick = compose.onNodeWithTag("tick-1").fetchSemanticsNode()
        assertEquals("Partly bought", tick.config.getOrNull(SemanticsProperties.StateDescription))
        assertEquals(listOf("1 more onion"), tick.config.getOrNull(SemanticsProperties.ContentDescription))
        assertEquals(Role.Checkbox, tick.config.getOrNull(SemanticsProperties.Role))
        compose.onNodeWithTag("item-1").performClick()
        compose.onNodeWithTag("tick-1").performClick()
        assertEquals("Bought", compose.onNodeWithTag("tick-3").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        // "Finish shopping" sits in the bar at the bottom, in reach, once something is ticked.
        compose.onNodeWithText("Finish shopping").performClick()
        compose.onNodeWithTag("finish").assertExists()
        compose.onNodeWithText("5 left").fetchSemanticsNode()
        assertEquals(listOf("open 1", "toggle 1", "finish"), events)
    }

    @Test
    fun arrangingMovesRowsWithinTheirGroupAndGroupsWhole() {
        val produce =
            listOf(
                ListRow(1, "onion", "2 onions", null, TickState.OPEN, AisleCode.PRODUCE, "Vegetables", key = "cat:onion"),
                ListRow(2, "carrot", "1 carrot", null, TickState.OPEN, AisleCode.PRODUCE, "Vegetables", key = "cat:carrot"),
                ListRow(3, "lettuce", "1 lettuce", null, TickState.OPEN, AisleCode.PRODUCE, "Salad", key = "cat:lettuce"),
                ListRow(4, "basil", "1 pack basil", null, TickState.OPEN, AisleCode.PRODUCE, "Herbs", key = "cat:basil"),
                ListRow(5, "dill", "1 pack dill", null, TickState.OPEN, AisleCode.PRODUCE, "Herbs", key = "cat:dill"),
            )
        val ui = SAMPLE_LIST.copy(aisles = listOf(AisleCode.PRODUCE to produce), arranging = true)
        compose.setContent { HyperShopTheme(dynamicColor = false) { ListScreen(ui, actions) } }
        compose.onNodeWithTag("arrange-hint").assertExists()
        // Arrows instead of ticks; the first of a group cannot go up, the last cannot go down.
        compose.onNodeWithTag("tick-1").assertDoesNotExist()
        compose.onNodeWithTag("up-1").assertIsNotEnabled()
        compose.onNodeWithTag("down-2").assertIsNotEnabled()
        compose.onNodeWithTag("up-group-PRODUCE-Vegetables").assertIsNotEnabled()
        compose.onNodeWithTag("down-group-PRODUCE-Herbs").assertIsNotEnabled()
        compose.onNodeWithTag("up-5").performClick()
        compose.onNodeWithTag("up-group-PRODUCE-Herbs").performClick()
        compose.onNodeWithContentDescription("Move lettuce down").assertIsNotEnabled()
        compose.onRoot().captureRoboImage(Shots.path("list-arrange-light.png"))
        compose.onNodeWithTag("arrange-done").performClick()
        assertEquals(listOf("move 5 -1", "group PRODUCE Herbs -1", "arrange false"), events)
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
                ListRow(2, "onion", "1 more onion", "2 already bought", TickState.PARTIAL, AisleCode.DAIRY_EGGS, from = "Weeknight chilli"),
                ListRow(3, "butter", "250 g butter", "1 × 250 g block", TickState.OPEN, AisleCode.DAIRY_EGGS, from = "Weeknight chilli"),
            )
        val ui = SAMPLE_LIST.copy(aisles = listOf(AisleCode.DAIRY_EGGS to rows))
        compose.setContent { HyperShopTheme(dynamicColor = false) { ListScreen(ui, actions) } }
        compose.onNodeWithText("700 ml Milk").assertExists()
        compose.onNodeWithText("1 more Onion").assertExists()
        compose.onNodeWithText("2 already bought · Weeknight chilli").assertExists()
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
        // One tap: the snackbar's Undo is the safety net, not a dialog.
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Clear all").performClick()
        assertEquals(listOf("clear"), events)
    }

    @Test
    fun theCountIsAHeading() {
        show()
        compose.onNode(hasText("5 left") and isHeading()).assertExists()
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
    fun collapsingOrOpeningAnAisleMovesTheRestStraightToTheirPlace() {
        show()
        val milk = compose.onNodeWithTag("item-4")
        compose.mainClock.autoAdvance = false
        for (step in listOf("collapse", "open")) {
            compose.onNodeWithTag("aisle-PRODUCE").performClick()
            compose.mainClock.advanceTimeByFrame()
            compose.mainClock.advanceTimeByFrame()
            val first = milk.getBoundsInRoot().top
            compose.mainClock.advanceTimeBy(2_000)
            // Where it ends up from the first frame: no slide the height of the aisle.
            assertEquals(step, milk.getBoundsInRoot().top.value, first.value, 1f)
        }
    }

    @Test
    fun aTickedRowStillSlidesToTheBottomOfItsAisle() {
        var ui by mutableStateOf(SAMPLE_LIST)
        compose.setContent { HyperShopTheme(dynamicColor = false) { ListScreen(ui, actions) } }
        val milk = compose.onNodeWithTag("item-4")
        val before = milk.getBoundsInRoot().top
        compose.mainClock.autoAdvance = false
        // Milk is ticked: it sinks below butter.
        val (aisle, rows) = SAMPLE_LIST.aisles[1]
        val ticked = listOf(rows[1], rows[0].copy(state = TickState.DONE))
        ui = SAMPLE_LIST.copy(aisles = SAMPLE_LIST.aisles.map { if (it.first == aisle) aisle to ticked else it })
        compose.mainClock.advanceTimeByFrame()
        compose.mainClock.advanceTimeByFrame()
        val moving = milk.getBoundsInRoot().top
        compose.mainClock.advanceTimeBy(2_000)
        val after = milk.getBoundsInRoot().top
        assertTrue("it moved down", after > before)
        assertTrue("on its way, not there yet", moving < after)
    }

    @Test
    fun categorisedShowsGroupLabelsAndTheToggle() {
        val rows =
            listOf(
                ListRow(1, "onion", "2 onions", null, TickState.OPEN, AisleCode.PRODUCE, "Vegetables"),
                ListRow(2, "basil", "basil", null, TickState.OPEN, AisleCode.PRODUCE, "Herbs"),
            )
        val ui = SAMPLE_LIST.copy(aisles = listOf(AisleCode.PRODUCE to rows), view = ListView.TYPE)
        compose.setContent { HyperShopTheme(dynamicColor = false) { ListScreen(ui, actions.copy(onView = { events += "view $it" })) } }
        compose.onNodeWithText("Vegetables").assertExists()
        compose.onNodeWithText("Herbs").assertExists()
        // The layouts live in the menu, as a radio group with "Group by type" chosen.
        compose.onNodeWithContentDescription("More").performClick()
        assertEquals(true, compose.onNodeWithTag("view-TYPE").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        assertEquals(false, compose.onNodeWithTag("view-RECIPE").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        compose.onNodeWithTag("view-RECIPE").performClick()
        assertEquals(listOf("view RECIPE"), events)
    }

    @Test
    fun byRecipeShowsACardPerRecipe() {
        val rows = SAMPLE_LIST.aisles.flatMap { it.second }
        val ui =
            SAMPLE_LIST.copy(
                view = ListView.RECIPE,
                recipes =
                    listOf(
                        RecipeSection("use-1", "Weeknight chilli", RecipeSection.Kind.RECIPE, rows.take(3), photo = TestPhotos.dish),
                        RecipeSection("use-2", "Red lentil dal", RecipeSection.Kind.RECIPE, listOf(rows[5])),
                        RecipeSection(
                            "shared",
                            "In more than one recipe",
                            RecipeSection.Kind.SHARED,
                            listOf(rows[3].copy(from = "Weeknight chilli, Red lentil dal")),
                        ),
                        RecipeSection("by-hand", "Added by hand", RecipeSection.Kind.BY_HAND, listOf(rows[4])),
                    ),
            )
        compose.setContent { HyperShopTheme(dynamicColor = false) { ListScreen(ui, actions) } }
        compose.onNodeWithTag("recipe-use-1").assertExists()
        compose.onNodeWithTag("aisle-PRODUCE").assertDoesNotExist()
        compose.onNodeWithTag("item-${rows.first().id}").assertExists()
        // A card closes like an aisle.
        compose.onNodeWithTag("recipe-use-1").performClick()
        compose.onNodeWithTag("item-${rows.first().id}").assertDoesNotExist()
        compose.onNodeWithTag("recipe-use-1").performClick()
        TestPhotos.settle(compose)
        compose.onRoot().captureRoboImage(Shots.path("list-recipes-light.png"))
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
    fun aWeekBoughtSaysItsShoppingIsDoneRatherThanOfferingToPlanIt() {
        // X.13: seven meals bought and the trip finished; the empty list said "Plan this week".
        val prep = PlanPrep(loaded = true, meals = 7, pending = 0, bought = 7, returning = true)
        compose.setContent {
            HyperShopTheme(dynamicColor = false) {
                ListScreen(ListUi(loaded = true), actions, empty = { EmptyState({}, {}, prep, onPlan = { events += "plan" }) })
            }
        }
        compose.onNodeWithText("This week's shopping is done").assertExists()
        compose.onNodeWithText("All 7 meals bought", substring = true).assertExists()
        compose.onNodeWithText("Nothing on the list").assertDoesNotExist()
        compose.onNodeWithTag("empty-plan").performClick()
        assertEquals(listOf("plan"), events)
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
