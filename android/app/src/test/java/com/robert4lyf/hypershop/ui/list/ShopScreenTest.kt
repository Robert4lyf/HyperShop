package com.robert4lyf.hypershop.ui.list

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertWidthIsAtLeast
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.robert4lyf.hypershop.settings.ListMode
import com.robert4lyf.hypershop.settings.ListView
import com.robert4lyf.hypershop.testutil.Shots
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The shop screen: the planning list's state, drawn for a supermarket. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ShopScreenTest {
    @get:Rule val compose = createComposeRule()

    private val events = mutableListOf<String>()
    private val actions =
        ListActions(
            onToggle = { events += "toggle ${it.id}" },
            onOpenItem = { events += "open ${it.id}" },
            onFinish = { events += "finish" },
            onClearAll = { events += "clear" },
            onMode = { events += "mode $it" },
        )
    private val shop = SAMPLE_LIST.copy(mode = ListMode.SHOP)

    private fun show(
        ui: ListUi = shop,
        dark: Boolean = false,
    ) = compose.setContent { HyperShopTheme(dark = dark, dynamicColor = false) { ListScreen(ui, actions) } }

    @Test
    fun shopModeDrawsTheShopScreenAndPlanGoesBack() {
        show()
        compose.onNodeWithTag("shop").assertExists()
        // Nothing to add or review here.
        compose.onNodeWithTag("add-item").assertDoesNotExist()
        compose.onNodeWithTag("in-stock").assertDoesNotExist()
        compose.onNodeWithTag("shop-left").assertTextContains("5 left")
        compose.onNodeWithText("1 of 6 bought · Now: Produce").assertExists()
        compose.onNodeWithTag("shop-plan").performClick()
        assertEquals(listOf("mode PLAN"), events)
    }

    @Test
    fun planModeShowsTheShopButtonOnlyWithSomethingToShopFor() {
        var ui by mutableStateOf(SAMPLE_LIST)
        compose.setContent { HyperShopTheme(dynamicColor = false) { ListScreen(ui, actions) } }
        compose.onNodeWithTag("start-shopping").performClick()
        assertEquals(listOf("mode SHOP"), events)
        ui = ListUi(loaded = true)
        compose.onNodeWithTag("start-shopping").assertDoesNotExist()
        // An empty list in shopping mode is the planning screen: there is nothing to walk.
        ui = ListUi(loaded = true, mode = ListMode.SHOP)
        compose.onNodeWithTag("shop").assertDoesNotExist()
    }

    @Test
    fun boughtRowsHideUnderOneRowPerAisleAndComeBackOnTap() {
        show()
        // Carrot (bought) is hidden; the row under Produce says so and opens it.
        compose.onNodeWithTag("item-3").assertDoesNotExist()
        compose.onNodeWithTag("item-1").assertExists()
        val toggle = compose.onNodeWithTag("shop-bought-aisle-PRODUCE")
        assertEquals("Collapsed", toggle.fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        compose.onNodeWithText("1 bought").assertExists()
        toggle.performClick()
        compose.onNodeWithTag("item-3").assertExists()
        assertEquals("Bought", compose.onNodeWithTag("tick-3").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription))
        // Unticking from the shown bought rows is the same tick target as always.
        compose.onNodeWithTag("tick-3").performClick()
        assertEquals(listOf("toggle 3"), events)
        // An aisle with nothing bought has no such row.
        compose.onNodeWithTag("shop-bought-aisle-DAIRY_EGGS").assertDoesNotExist()
    }

    @Test
    fun theTickIsLargeAndSeparateFromTheDetailsTap() {
        show()
        val tick = compose.onNodeWithTag("tick-4")
        tick.assertHeightIsAtLeast(48.dp)
        tick.assertWidthIsAtLeast(48.dp)
        assertEquals(Role.Checkbox, tick.fetchSemanticsNode().config.getOrNull(SemanticsProperties.Role))
        compose.onNodeWithTag("item-4").performClick()
        tick.performClick()
        assertEquals(listOf("open 4", "toggle 4"), events)
    }

    @Test
    fun theCurrentAisleIsMarkedAndNextJumpsToTheOneAfter() {
        show()
        // Produce still has things left, so it is "Now"; the next aisle with anything left is Dairy & Eggs.
        compose.onNodeWithText("Now").assertExists()
        compose.onNodeWithTag("shop-next").assertTextContains("Next: Dairy & Eggs")
        compose.onNodeWithTag("shop-next").performClick()
        // Nothing changes on the list from a jump.
        assertEquals(emptyList<String>(), events)
    }

    @Test
    fun nextAisleWrapsAndDisappearsWhenNothingIsLeft() {
        val groups = groups(shop)
        val cells = cells(groups, emptySet())
        val dairy = cells.indexOfFirst { it is ShopCell.Header && it.group.key == "aisle-DAIRY_EGGS" }
        // From the top: Dairy. From Dairy: Tins. From Tins (the last): round to Produce.
        assertEquals("aisle-DAIRY_EGGS", nextAisle(cells, 0)!!.cell.group.key)
        assertEquals("aisle-TINS_JARS", nextAisle(cells, dairy)!!.cell.group.key)
        val tins = cells.indexOfFirst { it is ShopCell.Header && it.group.key == "aisle-TINS_JARS" }
        assertEquals("aisle-PRODUCE", nextAisle(cells, tins)!!.cell.group.key)
        // Only the aisle you are in has anything left: there is no "next".
        val onlyProduce =
            shop.copy(
                aisles =
                    shop.aisles.mapIndexed {
                            i,
                            (a, rows),
                        ->
                        a to if (i == 0) rows else rows.map { it.copy(state = TickState.DONE) }
                    },
            )
        assertNull(nextAisle(cells(groups(onlyProduce), emptySet()), 0))
        val allDone = shop.copy(aisles = shop.aisles.map { (a, rows) -> a to rows.map { it.copy(state = TickState.DONE) } })
        assertNull(nextAisle(cells(groups(allDone), emptySet()), 0))
    }

    @Test
    fun allBoughtShowsFinishAndNoNext() {
        val allDone = shop.copy(aisles = shop.aisles.map { (a, rows) -> a to rows.map { it.copy(state = TickState.DONE) } })
        show(allDone)
        compose.onNodeWithTag("shop-left").assertTextContains("All bought")
        compose.onNodeWithTag("shop-next").assertDoesNotExist()
        compose.onNodeWithTag("finish").performClick()
        assertEquals(listOf("finish"), events)
    }

    @Test
    fun planningByRecipeStillShopsAisleByAisle() {
        val rows = SAMPLE_LIST.aisles.flatMap { it.second }
        val ui =
            shop.copy(
                view = ListView.RECIPE,
                recipes =
                    listOf(
                        RecipeSection("use-1", "Weeknight chilli", RecipeSection.Kind.RECIPE, rows.take(3)),
                        RecipeSection("by-hand", "Added by hand", RecipeSection.Kind.BY_HAND, rows.drop(3)),
                    ),
            )
        show(ui)
        // The shop is walked in the store's order; the plan keeps its own grouping for when you go back.
        compose.onNodeWithTag("shop-aisle-PRODUCE").assertExists()
        compose.onNodeWithTag("shop-recipe-use-1").assertDoesNotExist()
        compose.onNodeWithTag("shop-next").assertTextContains("Next: Dairy & Eggs")
    }

    @Test
    fun nowIsTheAisleAtTheTopWhileItHasSomethingLeft() {
        val cells = cells(groups(shop), emptySet())
        val dairy = cells.indexOfFirst { it is ShopCell.Header && it.group.key == "aisle-DAIRY_EGGS" }
        assertEquals("aisle-PRODUCE", nowAisle(cells, 0)!!.key)
        // Scrolled into Dairy's rows: Dairy is "Now", and "Next" is the aisle after it, never Dairy itself.
        assertEquals("aisle-DAIRY_EGGS", nowAisle(cells, dairy + 1)!!.key)
        assertEquals("aisle-TINS_JARS", nextAisle(cells, dairy + 1)!!.cell.group.key)
        // An aisle all bought is not "Now": "Next" says where to go.
        val produceDone =
            shop.copy(
                aisles =
                    shop.aisles.mapIndexed {
                            i,
                            (a, rows),
                        ->
                        a to if (i == 0) rows.map { it.copy(state = TickState.DONE) } else rows
                    },
            )
        val done = cells(groups(produceDone), emptySet())
        assertNull(nowAisle(done, 0))
        assertEquals("aisle-DAIRY_EGGS", nextAisle(done, 0)!!.cell.group.key)
    }

    @Test
    fun theTopBarNamesTheCurrentAisle() {
        show()
        compose.onNodeWithText("Now: Produce", substring = true).assertExists()
    }

    @Test
    fun shownBoughtRowsSurviveRecreation() {
        // A rotation or a process recreation: the aisle whose bought rows were opened stays open.
        val tester = StateRestorationTester(compose)
        tester.setContent { HyperShopTheme(dynamicColor = false) { ListScreen(shop, actions) } }
        compose.onNodeWithTag("shop-bought-aisle-PRODUCE").performClick()
        compose.onNodeWithTag("item-3").assertExists()
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("item-3").assertExists()
        assertEquals(
            "Expanded",
            compose.onNodeWithTag("shop-bought-aisle-PRODUCE").fetchSemanticsNode().config.getOrNull(SemanticsProperties.StateDescription),
        )
    }

    @Test
    fun recordLight() {
        show()
        compose.onRoot().captureRoboImage(Shots.path("shop-light.png"))
    }

    @Test
    fun recordDark() {
        show(dark = true)
        compose.onRoot().captureRoboImage(Shots.path("shop-dark.png"))
    }

    private fun androidx.compose.ui.test.SemanticsNodeInteraction.assertTextContains(text: String) {
        val texts = fetchSemanticsNode().config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty()
        assertTrue("$texts contains $text", texts.any { it.contains(text) })
    }
}
