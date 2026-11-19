package com.robert4lyf.hypershop.ui.meals

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.robert4lyf.hypershop.core.merge.UseProgress
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.plan.Week
import com.robert4lyf.hypershop.data.BuyAgainView
import com.robert4lyf.hypershop.data.HomeRow
import com.robert4lyf.hypershop.data.MealShopping
import com.robert4lyf.hypershop.data.Regular
import com.robert4lyf.hypershop.testutil.Shots
import com.robert4lyf.hypershop.testutil.TestPhotos
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
                    Meal(
                        1,
                        "Weeknight chilli",
                        4,
                        MealShopping.TO_BUY,
                        UseProgress(6, 0, 0, 0),
                        "bbcgoodfood.com",
                        photo = TestPhotos.dish,
                        notes = "Half the chilli; 20 min longer",
                    ),
                    Meal(2, "Red lentil dal", null, MealShopping.NOT_ON_LIST, null, "Ottolenghi Simple · p. 142"),
                ),
            itemsLeft = 6,
            picks =
                listOf(
                    Pick("k1", "Weeknight chilli", 9, 1, "bbcgoodfood.com", TestPhotos.dish),
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
        )
    private val actions =
        MealsActions(
            onPlan = { events += "plan ${it.title}" },
            onServings = { m, n -> events += "serves ${m.id} $n" },
            onRemove = { events += "remove ${it.id}" },
            onCooked = { events += "cooked ${it.id}" },
            onRegular = { events += "regular ${it.name}" },
            onAllRegulars = { events += "all regulars" },
            onRepeatLastShop = { events += "last shop" },
            onHave = { rs -> events += "have ${rs.joinToString { it.name }}" },
            onNeed = { events += "need ${it.name}" },
            onList = { events += "list" },
            onRepeat = { events += "repeat" },
        )

    private fun show(u: MealsUi = ui) = compose.setContent { HyperShopTheme(dynamicColor = false) { MealsScreen(u, actions) } }

    private fun scrollTo(tag: String) = compose.onNodeWithTag("meals").performScrollToNode(hasTestTag(tag))

    @Test
    fun theWeekSaysWhatIsCookingAndAddToListIsTheOneAction() {
        show()
        compose.onNodeWithText("This week").assertExists()
        compose.onNodeWithText("19–25 Oct · 2 meals · 6 items still needed").assertExists()
        compose.onNodeWithText("6 still needed · bbcgoodfood.com").assertExists()
        // Step Z: no "Not on your list yet" and no Make shopping list.
        compose.onNodeWithText("Ottolenghi Simple · p. 142").assertExists()
        compose.onNodeWithText("Make shopping list").assertDoesNotExist()
        compose.onNodeWithTag("meal-more-1").performClick()
        // No stepper for a recipe that doesn't say how many it serves.
        compose.onNodeWithTag("meal-serves-2").assertDoesNotExist()
        // Add to list opens the saved recipes; each one's button puts it on the list.
        compose.onNodeWithTag("meals-add-meal").performClick()
        compose.onNodeWithTag("pick-add-k1").assertTextEquals("Add to list")
        compose.onNodeWithTag("pick-add-k3").performClick()
        compose.onNodeWithTag("add-meal-done").performClick()
        compose.onNodeWithTag("add-meal-sheet").assertDoesNotExist()
        assertEquals(listOf("serves 1 1", "plan Fish pie"), events)
    }

    @Test
    fun removingAMealIsOneTapWithUndoOnTheMessage() {
        show()
        compose.onNodeWithTag("meal-remove-1").performClick()
        compose.onNodeWithTag("meal-remove-2").performClick()
        assertEquals(listOf("remove 1", "remove 2"), events)
    }

    @Test
    fun buyAgainAndTheHomeCheckAreNotOnTheMealsScreen() {
        // Step Q: they live with the list (a sheet) and on the focused home check.
        show()
        compose.onNodeWithTag("buy-again").assertDoesNotExist()
        compose.onNodeWithTag("home-cat:cumin").assertDoesNotExist()
        compose.onNodeWithTag("misc-add").assertDoesNotExist()
        compose.onNodeWithTag("pick-k3").assertDoesNotExist()
    }

    @Test
    fun theBuyAgainSectionAddsChipsAllAndLastShop() {
        compose.setContent { HyperShopTheme(dynamicColor = false) { BuyAgainSection(ui, actions) } }
        compose.onNodeWithTag("regular-cat:milk").assertHeightIsAtLeast(48.dp)
        compose.onNodeWithTag("regular-cat:milk").performClick()
        // On the list already: a tap does nothing.
        compose.onNodeWithTag("regular-cat:bread").performClick()
        compose.onNodeWithTag("buy-again-all").performClick()
        compose.onNodeWithText("Add all 2").assertExists()
        compose.onNodeWithTag("repeat-last-shop").performClick()
        assertEquals(listOf("regular milk", "all regulars", "last shop"), events)
    }

    @Test
    fun theHomeCheckAsksOncePerIngredientWithPlainActions() {
        compose.setContent { HyperShopTheme(dynamicColor = false) { HomeCheckScreen(ui, actions, onBack = { events += "back" }) } }
        compose.onNodeWithText("Food at home").assertExists()
        // Combined: rice is wanted by two meals, asked about once.
        compose.onNodeWithText("Needed for 2 meals").assertExists()
        compose.onNodeWithTag("home-have-cat:cumin").assertTextEquals("Have enough")
        compose.onNodeWithTag("home-have-cat:cumin").performClick()
        compose.onNodeWithTag("home-need-cat:salt").performClick()
        compose.onNodeWithTag("home-check-done").performClick()
        assertEquals(listOf("have ground cumin", "need salt", "back"), events)
        compose.onRoot().captureRoboImage(Shots.path("home-check-light.png"))
    }

    @Test
    fun anEmptyWeekOffersTheLastOne() {
        show(
            ui.copy(
                meals = emptyList(),
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
        show(ui.copy(isCurrent = false, isPast = true, weekName = "Last week"))
        compose.onNodeWithTag("meal-remove-1").assertDoesNotExist()
        compose.onNodeWithTag("meals-add-meal").assertDoesNotExist()
        compose.onNodeWithTag("buy-again").assertDoesNotExist()
        compose.onNodeWithTag("meals-search").assertDoesNotExist()
    }

    @Test
    fun emptyLibraryExplainsWhereRecipesComeFrom() {
        show(MealsUi(loaded = true, weekName = "This week"))
        compose.onNodeWithText("Save a recipe first: share a recipe link to HyperShop, or tap Add to list › New recipe.").assertExists()
        compose.onNodeWithTag("meals-add-meal").performClick()
        compose.onNodeWithTag("new-recipe").assertExists()
    }

    @Test
    fun aRejectedHomeAmountStaysVisibleForCorrection() {
        var attempts = 0
        val row = HomeRow("cat:rice", "rice", listOf("Soup"), false, false, needed = "500 g", onHand = "100 g")
        compose.setContent {
            HyperShopTheme(dynamicColor = false) {
                HomeCheckScreen(
                    ui.copy(home = listOf(row)),
                    actions.copy(onHaveSome = { _, _, done ->
                        attempts++
                        done(if (attempts == 1) "Keep the amount already allocated to earlier purchases." else null)
                    }),
                    onBack = {},
                )
            }
        }
        compose.onNodeWithText("100 g at home").assertExists()
        compose.onNodeWithTag("home-some-cat:rice").performClick()
        compose.onNodeWithTag("home-some-amount").assertTextContains("100 g")
        compose.onNodeWithTag("home-some-amount").performTextReplacement("not an amount")
        compose.onNodeWithTag("home-some-set").performClick()
        compose.onNodeWithText("Enter a positive amount using the requirement's measure, e.g. 300 g.").assertIsDisplayed()
        assertEquals(0, attempts)
        compose.onNodeWithTag("home-some-amount").performTextReplacement("200 g")
        // What it will mean, before Set.
        compose.onNodeWithTag("home-some-preview").assertTextEquals("200 g at home → buy 300 g")
        compose.onNodeWithTag("home-some-set").performClick()
        compose.onNodeWithText("Keep the amount already allocated to earlier purchases.").assertIsDisplayed()
        compose.onNodeWithTag("home-some-amount").assertTextContains("200 g")
        compose.onNodeWithTag("home-some-set").assertIsDisplayed()
        compose.onNodeWithTag("home-some-dialog").captureRoboImage(Shots.path("home-amount-dialog-light.png"))
        compose.onNodeWithTag("home-some-amount").performTextReplacement("300 g")
        compose.onNodeWithTag("home-some-set").performClick()
        compose.onNodeWithTag("home-some-amount").assertDoesNotExist()
        assertEquals(2, attempts)
    }

    @Test
    fun recordLight() {
        show()
        TestPhotos.settle(compose)
        compose.onRoot().captureRoboImage(Shots.path("meals-light.png"))
    }

    @Test
    fun recordDark() {
        compose.setContent { HyperShopTheme(dark = true, dynamicColor = false) { MealsScreen(ui, actions) } }
        compose.onRoot().captureRoboImage(Shots.path("meals-dark.png"))
    }

    @Test
    fun aPlannedMealShowsYourNotesForIt() {
        show()
        compose.onNodeWithTag("meal-notes-1").assertTextEquals("Half the chilli; 20 min longer")
        compose.onNodeWithTag("meal-notes-2").assertDoesNotExist()
    }
}
