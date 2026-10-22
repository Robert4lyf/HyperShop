package com.robert4lyf.hypershop.ui.meals

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.robert4lyf.hypershop.core.merge.UseProgress
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.plan.Week
import com.robert4lyf.hypershop.data.BuyAgainView
import com.robert4lyf.hypershop.data.HomeRow
import com.robert4lyf.hypershop.data.MealShopping
import com.robert4lyf.hypershop.data.Regular
import com.robert4lyf.hypershop.data.WeekPreview
import com.robert4lyf.hypershop.testutil.Shots
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.LocalDate

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class MealsScreenTest {
    @get:Rule val compose = createComposeRule()

    private val events = mutableListOf<String>()
    private val week = Week.of(LocalDate.of(2026, 10, 22))

    private fun regular(
        name: String,
        onList: Boolean = false,
    ) = Regular("cat:$name", name, QtyBase.PRESENCE, null, false, 3, onList)

    private val ui =
        MealsUi(
            loaded = true,
            week = week,
            weekName = "This week",
            range = week.range(),
            meals =
                listOf(
                    Meal(1, "Weeknight chilli", 4, MealShopping.TO_BUY, UseProgress(6, 0, 0, 0), "bbcgoodfood.com"),
                    Meal(2, "Red lentil dal", null, MealShopping.NOT_ON_LIST, null, "Ottolenghi Simple · p. 142"),
                ),
            preview = WeekPreview(meals = 1, newItems = 5, joined = 2, atHome = 1),
            itemsLeft = 6,
            picks =
                listOf(
                    Pick("k1", "Weeknight chilli", 9, 1, "bbcgoodfood.com"),
                    Pick("k3", "Fish pie", 11, 0, null),
                ),
            hasLibrary = true,
            buyAgain =
                BuyAgainView(
                    regulars = listOf(regular("milk"), regular("bread", onList = true), regular("eggs")),
                    lastShop = listOf(regular("toilet roll")),
                    lastShopAt = 1L,
                    hasHistory = true,
                ),
            home =
                listOf(
                    HomeRow("cat:cumin", "ground cumin", listOf("Weeknight chilli"), have = false, usual = false),
                    HomeRow("cat:rice", "basmati rice", listOf("Weeknight chilli", "Red lentil dal"), have = false, usual = false),
                    HomeRow("cat:salt", "salt", listOf("Weeknight chilli"), have = true, usual = true),
                ),
            listHasItems = true,
        )
    private val actions =
        MealsActions(
            onPlan = { events += "plan ${it.title}" },
            onServings = { m, n -> events += "serves ${m.id} $n" },
            onRemove = { events += "remove ${it.id}" },
            onAddToList = { events += "add week" },
            onRegular = { events += "regular ${it.name}" },
            onAllRegulars = { events += "all regulars" },
            onRepeatLastShop = { events += "last shop" },
            onHave = { rs -> events += "have ${rs.joinToString { it.name }}" },
            onNeed = { events += "need ${it.name}" },
            onStartShopping = { events += "shop" },
            onRepeat = { events += "repeat" },
        )

    private fun show(u: MealsUi = ui) = compose.setContent { HyperShopTheme(dynamicColor = false) { MealsScreen(u, actions) } }

    private fun scrollTo(tag: String) = compose.onNodeWithTag("meals").performScrollToNode(hasTestTag(tag))

    @Test
    fun theWeekSaysWhatIsCookingAndWhatTheButtonWillDo() {
        show()
        compose.onNodeWithText("This week").assertExists()
        compose.onNodeWithText("19–25 Oct · 2 meals · 1 not on the list yet").assertExists()
        compose.onNodeWithText("6 still needed · bbcgoodfood.com").assertExists()
        compose.onNodeWithText("Not on the list yet · Ottolenghi Simple · p. 142").assertExists()
        compose.onNodeWithText("Add 1 meal to the list").assertExists()
        compose.onNodeWithText("5 new items · 2 join the list · 1 usually at home").assertExists()
        compose.onNodeWithTag("meals-add-to-list").performClick()
        compose.onNodeWithTag("meal-more-1").performClick()
        // No stepper for a recipe that doesn't say how many it serves.
        compose.onNodeWithTag("meal-serves-2").assertDoesNotExist()
        assertEquals(listOf("add week", "serves 1 1"), events)
    }

    @Test
    fun removingAMealOnTheListSaysWhatComesOff() {
        show()
        compose.onNodeWithTag("meal-remove-1").performClick()
        compose
            .onNodeWithText("6 items it still needs come off the list. Anything bought stays bought. The recipe stays saved.")
            .assertExists()
        compose.onNodeWithText("Keep").performClick()
        compose.onNodeWithTag("meal-remove-2").performClick()
        compose.onNodeWithText("It comes off this week's plan. The list and the recipe stay as they are.").assertExists()
        compose.onNodeWithTag("meals-remove-confirm").performClick()
        assertEquals(listOf("remove 2"), events)
    }

    @Test
    fun buyAgainChipsAndTheAtHomeCheck() {
        show()
        scrollTo("buy-again")
        compose.onNodeWithTag("regular-cat:milk").performClick()
        // On the list already: a tap does nothing.
        compose.onNodeWithTag("regular-cat:bread").performClick()
        compose.onNodeWithTag("buy-again-all").performClick()
        compose.onNodeWithText("Add all 2").assertExists()
        compose.onNodeWithTag("repeat-last-shop").performClick()
        scrollTo("home-check")
        compose.onNodeWithText("Have all 2").performClick()
        scrollTo("home-cat:salt")
        compose.onNodeWithTag("home-need-cat:salt").performClick()
        assertEquals(
            listOf("regular milk", "all regulars", "last shop", "have ground cumin, basmati rice", "need salt"),
            events,
        )
    }

    @Test
    fun withEverythingOnTheListTheButtonIsStartShoppingAndPicksPlan() {
        show(ui.copy(meals = ui.meals.map { it.copy(shopping = MealShopping.TO_BUY, progress = UseProgress(1, 0, 0, 0)) }, preview = null))
        compose.onNodeWithTag("meals-shop").performClick()
        scrollTo("pick-k3")
        compose.onNodeWithTag("pick-add-k3").performClick()
        assertEquals(listOf("shop", "plan Fish pie"), events)
    }

    @Test
    fun anEmptyWeekOffersTheLastOne() {
        show(
            ui.copy(
                meals = emptyList(),
                preview = null,
                repeatFrom = week - 1,
                repeatName = "last week",
                repeatTitles = listOf("Chilli", "Soup"),
            ),
        )
        compose.onNodeWithText("Same as last week?").assertExists()
        compose.onNodeWithText("Chilli, Soup").assertExists()
        compose.onNodeWithTag("repeat-week-button").performClick()
        assertEquals(listOf("repeat"), events)
    }

    @Test
    fun aPastWeekIsToLookBackAtOnly() {
        show(ui.copy(isCurrent = false, isPast = true, weekName = "Last week", preview = null))
        compose.onNodeWithTag("meal-remove-1").assertDoesNotExist()
        compose.onNodeWithTag("meals-add-to-list").assertDoesNotExist()
        compose.onNodeWithTag("buy-again").assertDoesNotExist()
        compose.onNodeWithTag("meals-search").assertDoesNotExist()
    }

    @Test
    fun emptyLibraryExplainsWhereRecipesComeFrom() {
        show(MealsUi(loaded = true, weekName = "This week"))
        compose.onNodeWithText("No saved recipes yet").assertExists()
        compose.onNodeWithTag("meals-shop").assertDoesNotExist()
    }

    @Test
    fun recordLight() {
        show()
        compose.onRoot().captureRoboImage(Shots.path("meals-light.png"))
    }

    @Test
    fun recordDark() {
        compose.setContent { HyperShopTheme(dark = true, dynamicColor = false) { MealsScreen(ui, actions) } }
        compose.onRoot().captureRoboImage(Shots.path("meals-dark.png"))
    }
}
