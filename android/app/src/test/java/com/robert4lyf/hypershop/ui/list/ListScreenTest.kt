package com.robert4lyf.hypershop.ui.list

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isHeading
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeLeft
import androidx.compose.ui.test.swipeRight
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
            onRemove = { events += "remove ${it.id}" },
            onFinish = { events += "finish" },
            onClearAll = { events += "clear" },
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
        // The bought carrot is folded under its aisle's "1 bought" row until that is opened.
        compose.onNodeWithTag("tick-3").assertDoesNotExist()
        compose.onNodeWithTag("bought-PRODUCE").performClick()
        assertEquals("Bought", compose.onNodeWithTag("tick-3").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        // "Finish shopping" sits in the bar at the bottom, in reach, once something is ticked.
        compose.onNodeWithText("Finish shopping").performClick()
        compose.onNodeWithTag("finish").assertExists()
        compose.onNodeWithText("5 to buy · 1 bought").fetchSemanticsNode()
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
        // Step Z: no instructions; the arrows and "Done arranging" say what to do.
        compose.onNodeWithTag("arrange-hint").assertDoesNotExist()
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
    fun rowsReadAlikeAndSayTheirRecipeOnlyToTalkBackInStoreOrder() {
        val rows =
            listOf(
                ListRow(1, "milk", "700 ml milk", null, TickState.OPEN, AisleCode.DAIRY_EGGS, from = "Weeknight chilli"),
                ListRow(2, "onion", "1 more onion", "2 already bought", TickState.PARTIAL, AisleCode.DAIRY_EGGS, from = "Weeknight chilli"),
                ListRow(3, "butter", "250 g butter", "1 × 250 g block", TickState.OPEN, AisleCode.DAIRY_EGGS, from = "Weeknight chilli"),
            )
        val ui = SAMPLE_LIST.copy(aisles = listOf(AisleCode.DAIRY_EGGS to rows))
        compose.setContent { HyperShopTheme(dynamicColor = false) { ListScreen(ui, actions) } }
        compose.onNodeWithText("700 ml milk").assertExists()
        compose.onNodeWithText("1 more onion").assertExists()
        // Step AD.6: in store order the recipe is not drawn; the bought count keeps its line.
        compose.onNodeWithText("2 already bought", useUnmergedTree = true).assertExists()
        compose.onAllNodesWithText("Weeknight chilli", substring = true, useUnmergedTree = true).assertCountEquals(0)
        // TalkBack still says what it is for.
        val said = compose.onNodeWithTag("item-1").fetchSemanticsNode().config[SemanticsProperties.ContentDescription]
        assertEquals(listOf("700 ml milk, for Weeknight chilli"), said)
        assertEquals(listOf("1 × 250 g block"), subLines(rows[2]))
        // Grouped by recipe (the shared card): the recipe under the name, the pack note on a line of its own.
        assertEquals(listOf("1 × 250 g block", "Weeknight chilli"), subLines(rows[2], showFrom = true))
        assertEquals(listOf("2 already bought · Weeknight chilli"), subLines(rows[1], showFrom = true))
        // A capital at the start of the row only (UX review 9): mid-line capitals read as typos.
        assertEquals("Coriander", displayMain("coriander", "coriander"))
        assertEquals("2 leaves", displayMain("2 leaves", "leaf"))
        assertEquals("2 tins chopped tomatoes", displayMain("2 tins chopped tomatoes", "chopped tomatoes"))
    }

    @Test
    fun aPackLineOnlyWhenItDiffersFromTheAmount() {
        // Step AD.6: the usual pack under the amount only when it says something new.
        assertEquals(null, leanSub("500g beef mince", "500g pack", "1 × 500 g pack", TickState.OPEN))
        assertEquals(null, leanSub("250g unsalted butter", "250g block", "1 × 250 g block", TickState.OPEN))
        assertEquals("4 pint bottle", leanSub("700ml milk", "4 pint bottle", "1 × 4 pint bottle", TickState.OPEN))
        assertEquals("400g tin", leanSub("2 tins chopped tomatoes", "400g tin", "1 × 400 g tin", TickState.OPEN))
        assertEquals("454g jar", leanSub("honey", "454g jar", "454 g jar", TickState.OPEN))
        // Not the pack note: kept whatever the numbers.
        assertEquals("2 already bought", leanSub("2 more onions", "2 already bought", "1 × 1 kg bag", TickState.PARTIAL))
        assertEquals("150ml needed", leanSub("1 pot double cream", "150ml needed", "1 × 150 ml pot", TickState.OPEN))
        assertEquals("200g at home", leanSub("200g rice", "200g at home", "1 × 1 kg bag", TickState.OPEN))
    }

    @Test
    fun theWatchIsMentionedOnlyWhenItNeedsAttention() {
        // Step AD.7: synced or updating says nothing on the list; only "Watch not connected" shows, under the count.
        assertEquals(null, watchWord(com.robert4lyf.hypershop.watch.WatchLinkState.APP_READY))
        assertEquals(null, watchWord(com.robert4lyf.hypershop.watch.WatchLinkState.NO_SDK))
        assertEquals(null, watchWord(com.robert4lyf.hypershop.watch.WatchLinkState.NOT_INSTALLED))
        assertEquals("Watch not connected", watchWord(com.robert4lyf.hypershop.watch.WatchLinkState.DISCONNECTED))
        assertEquals("Watch not connected", watchWord(com.robert4lyf.hypershop.watch.WatchLinkState.CONNECTED))
        compose.setContent { HyperShopTheme(dynamicColor = false) { ListScreen(SAMPLE_LIST.copy(watch = "Watch not connected"), actions) } }
        compose.onNodeWithText("Watch not connected").assertExists()
    }

    @Test
    fun aRowSwipedAwayIsRemovedAndShowsRemoveWhileMoving() {
        // Step AD.10: swiping a row still to buy, either way, takes it off; a labelled "Remove" shows under it as it moves.
        show()
        compose.onNodeWithTag("swipe-4").performTouchInput {
            down(center)
            repeat(10) {
                advanceEventTime(16)
                moveBy(Offset(-12f, 0f))
            }
        }
        compose.onNodeWithText("Remove", useUnmergedTree = true).assertExists()
        compose.onNodeWithTag("swipe-4").performTouchInput { up() }
        compose.onNodeWithTag("swipe-2").performTouchInput { swipeLeft() }
        compose.onNodeWithTag("swipe-1").performTouchInput { swipeRight() }
        compose.waitForIdle()
        assertTrue(events.toString(), "remove 2" in events && "remove 1" in events)
        // A bought row isn't swiped; TalkBack has the same as an action on a row still to buy.
        compose.onNodeWithTag("swipe-3").assertDoesNotExist()
        val action =
            compose.onNodeWithTag("item-4").fetchSemanticsNode().config[SemanticsActions.CustomActions].single { it.label == "Remove" }
        compose.runOnIdle { action.action() }
        assertTrue(events.toString(), "remove 4" in events)
    }

    @Test
    fun loadingShowsASpinnerAndNoCount() {
        compose.setContent { HyperShopTheme(dynamicColor = false) { ListScreen(ListUi(), actions) } }
        compose.onNodeWithTag("loading").assertExists()
        compose.onNodeWithText("HyperShop").assertDoesNotExist()
        // Step AA: the compact row while loading (no jump from banner to row), with no count yet.
        compose.onNodeWithText("Shopping list").assertExists()
        compose.onAllNodesWithText("to buy", substring = true).assertCountEquals(0)
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
        compose.onNode(hasText("5 to buy · 1 bought") and isHeading()).assertExists()
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
    fun boughtRowsFoldUnderOneRowPerAisleAndComeBackOnTap() {
        // Step Z: from the old shop screen, now on the one list.
        show()
        compose.onNodeWithTag("item-3").assertDoesNotExist()
        compose.onNodeWithTag("item-1").assertExists()
        val toggle = compose.onNodeWithTag("bought-PRODUCE")
        assertEquals("Collapsed", toggle.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        compose.onNodeWithText("1 bought").assertExists()
        toggle.performClick()
        compose.onNodeWithTag("item-3").assertExists()
        // Unticking from the shown bought rows is the same tick target as always.
        compose.onNodeWithTag("tick-3").performClick()
        assertEquals(listOf("toggle 3"), events)
        compose.onNodeWithTag("bought-PRODUCE").performClick()
        compose.onNodeWithTag("item-3").assertDoesNotExist()
        // An aisle with nothing bought has no such row.
        compose.onNodeWithTag("bought-DAIRY_EGGS").assertDoesNotExist()
    }

    @Test
    fun aTickedRowStaysAMomentSoTappingAgainUndoesItThenFolds() {
        var ui by mutableStateOf(SAMPLE_LIST)
        val live =
            actions.copy(onToggle = { r ->
                events += "toggle ${r.id}"
                ui =
                    ui.copy(
                        aisles =
                            ui.aisles.map { (a, rows) ->
                                a to
                                    rows.map {
                                        when {
                                            it.id != r.id -> it
                                            it.state == TickState.DONE -> it.copy(state = TickState.OPEN)
                                            else -> it.copy(state = TickState.DONE)
                                        }
                                    }
                            },
                    )
            })
        compose.mainClock.autoAdvance = false
        compose.setContent { HyperShopTheme(dark = false, dynamicColor = false) { ListScreen(ui, live) } }
        compose.onNodeWithTag("tick-4").performClick()
        compose.mainClock.advanceTimeBy(300)
        // Still in place, read as bought: a second tap unticks it.
        compose.onNodeWithTag("tick-4").assertExists()
        compose.onNodeWithTag("tick-4").performClick()
        compose.mainClock.advanceTimeBy(LINGER_MS + 500)
        compose.onNodeWithTag("tick-4").assertExists()
        assertEquals(listOf("toggle 4", "toggle 4"), events)
        // Ticked, unticked and ticked again inside the moment: the moment runs from the last tap, and left ticked,
        // the row then folds into the aisle's "bought" row.
        compose.onNodeWithTag("tick-4").performClick()
        compose.mainClock.advanceTimeBy(LINGER_MS - 1000)
        compose.onNodeWithTag("tick-4").performClick()
        compose.mainClock.advanceTimeBy(500)
        compose.onNodeWithTag("tick-4").performClick()
        compose.mainClock.advanceTimeBy(LINGER_MS - 500)
        compose.onNodeWithTag("tick-4").assertExists()
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithTag("tick-4").assertDoesNotExist()
        compose.onNodeWithTag("bought-DAIRY_EGGS").assertExists()
    }

    @Test
    fun changeStoreIsInTheMenuOnlyWithASecondStore() {
        var ui by mutableStateOf(SAMPLE_LIST)
        compose.setContent {
            HyperShopTheme(dynamicColor = false) { ListScreen(ui, actions.copy(onChangeStore = { events += "store" })) }
        }
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithTag("change-store").assertDoesNotExist()
        compose.onNodeWithTag("view-STORE").assertExists()
        compose.onNodeWithTag("arrange").assertExists()
        // No shopping mode: nothing to switch into.
        compose.onNodeWithText("Shopping view").assertDoesNotExist()
        // Any choice closes the menu.
        compose.onNodeWithText("Share shopping list").performClick()
        ui = SAMPLE_LIST.copy(secondStore = true, storeName = "Big shop")
        compose.onNodeWithContentDescription("More").performClick()
        compose.onNodeWithText("Change store (Big shop)").performClick()
        assertEquals(listOf("store"), events)
    }

    @Test
    fun categorisedShowsGroupLabelsAndTheToggle() {
        val two =
            listOf(
                ListRow(1, "onion", "2 onions", null, TickState.OPEN, AisleCode.PRODUCE, "Vegetables"),
                ListRow(2, "basil", "basil", null, TickState.OPEN, AisleCode.PRODUCE, "Herbs"),
            )
        // Step AC: a short aisle shows no group labels (its groups keep their order); six or more items show them.
        var ui by mutableStateOf(SAMPLE_LIST.copy(aisles = listOf(AisleCode.PRODUCE to two), view = ListView.STORE))
        compose.setContent { HyperShopTheme(dynamicColor = false) { ListScreen(ui, actions.copy(onView = { events += "view $it" })) } }
        compose.onNodeWithText("Vegetables").assertDoesNotExist()
        val six = two + (3L..6L).map { ListRow(it, "herb $it", "herb $it", null, TickState.OPEN, AisleCode.PRODUCE, "Herbs") }
        ui = ui.copy(aisles = listOf(AisleCode.PRODUCE to six))
        compose.onNodeWithText("Vegetables").assertExists()
        compose.onNodeWithText("Herbs").assertExists()
        // The layouts live in the menu, as a radio group with "Store order" chosen.
        compose.onNodeWithContentDescription("More").performClick()
        assertEquals(true, compose.onNodeWithTag("view-STORE").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        assertEquals(false, compose.onNodeWithTag("view-RECIPE").fetchSemanticsNode().config.getOrNull(SemanticsProperties.Selected))
        compose.onNodeWithTag("view-RECIPE").performClick()
        assertEquals(listOf("view RECIPE"), events)
    }

    @Test
    fun byRecipeShowsACardPerRecipe() {
        val actions =
            actions.copy(
                onUseRemove = { events += "remove ${it.key}" },
                onUseOpen = { events += "open ${it.key}" },
                onUseServings = { s, n -> events += "serves ${s.key} $n" },
            )
        val rows = SAMPLE_LIST.aisles.flatMap { it.second }
        val ui =
            SAMPLE_LIST.copy(
                view = ListView.RECIPE,
                recipes =
                    listOf(
                        RecipeSection("use-1", "Weeknight chilli", RecipeSection.Kind.RECIPE, rows.take(3), photo = TestPhotos.dish),
                        RecipeSection("use-2", "Red lentil dal", RecipeSection.Kind.RECIPE, listOf(rows[5]), servings = 4),
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
        // The recipe's own actions live on its card (cut 1 of the review); the shared card has none.
        compose.onNodeWithTag("recipe-more-use-2").performClick()
        compose.onNodeWithTag("recipe-remove-use-2").performClick()
        compose.onNodeWithTag("recipe-more-use-2").performClick()
        compose.onNodeWithTag("recipe-open-use-2").performClick()
        compose.onNodeWithTag("recipe-more-shared").assertDoesNotExist()
        // Step AA: servings in the menu, in place: no dialog, no Done; each press is written at once.
        compose.onNodeWithTag("recipe-more-use-2").performClick()
        compose.onNodeWithTag("recipe-servings-use-2").assertTextEquals("Serves 4")
        compose.onNodeWithTag("recipe-servings-use-2-more").performClick()
        compose.onNodeWithTag("servings-done").assertDoesNotExist()
        compose.onNodeWithTag("recipe-servings-use-2").assertTextEquals("Serves 5")
        androidx.test.espresso.Espresso.pressBack()
        assertEquals(listOf("remove use-2", "open use-2"), events.filter { it.endsWith("use-2") })
        assertEquals(listOf("serves use-2 5"), events.filter { it.startsWith("serves") })
        TestPhotos.settle(compose)
        compose.onRoot().captureRoboImage(Shots.path("list-recipes-light.png"))
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
                ListScreen(
                    ui,
                    actions,
                    header = { listHeader(ui, header) },
                    empty = { EmptyState(header.onSample, header.onPasteLink) },
                    addField = { ListAddField(header) },
                )
            }
        }
    }

    @Test
    fun aReturningShopperIsOfferedLastShopAndRecipes() {
        // Step Z: no "Plan this week" (Meals went). Step AD.11: after a shop, the next step: last shop's items back on,
        // or "Add recipes" (the Recipes tab).
        val prep = PlanPrep(loaded = true, lastShop = 4, returning = true)
        compose.setContent {
            HyperShopTheme(dynamicColor = false) {
                ListScreen(
                    ListUi(loaded = true),
                    actions,
                    empty = { EmptyState({}, {}, prep, onLastShop = { events += "last" }, onRecipes = { events += "recipes" }) },
                )
            }
        }
        compose.onNodeWithText("Nothing on the list").assertExists()
        compose.onNodeWithTag("empty-plan").assertDoesNotExist()
        compose.onNodeWithText("Last shop's 4 items").performClick()
        compose.onNodeWithText("Add recipes").performClick()
        assertEquals(listOf("last", "recipes"), events)
        compose.onNodeWithTag("paste-link").assertDoesNotExist()
    }

    @Test
    fun aReturningShopperWithNoLastShopStillGetsAddRecipes() {
        val prep = PlanPrep(loaded = true, lastShop = 0, returning = true)
        compose.setContent {
            HyperShopTheme(dynamicColor = false) {
                ListScreen(ListUi(loaded = true), actions, empty = { EmptyState({}, {}, prep, onRecipes = { events += "recipes" }) })
            }
        }
        compose.onNodeWithTag("empty-last-shop").assertDoesNotExist()
        compose.onNodeWithTag("empty-recipes").performClick()
        assertEquals(listOf("recipes"), events)
    }

    @Test
    fun theFirstRunEmptyListIsUnchanged() {
        compose.setContent {
            HyperShopTheme(
                dynamicColor = false,
            ) { ListScreen(ListUi(loaded = true), actions, empty = { EmptyState({}, {}, PlanPrep(loaded = true)) }) }
        }
        compose.onNodeWithText("Try a sample list").assertExists()
        compose.onNodeWithTag("paste-link").assertExists()
        compose.onNodeWithTag("empty-recipes").assertDoesNotExist()
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
            HyperShopTheme(dynamicColor = false) {
                ListScreen(SAMPLE_LIST, actions, header = { listHeader(SAMPLE_LIST, header) }, addField = { ListAddField(header) })
            }
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
