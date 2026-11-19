package com.robert4lyf.hypershop.ui.meals

import androidx.compose.ui.test.assertHeightIsAtLeast
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.data.BuyAgainView
import com.robert4lyf.hypershop.data.HomeRow
import com.robert4lyf.hypershop.data.Regular
import com.robert4lyf.hypershop.testutil.Shots
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Buy again's section and the food-at-home screen (they were tested with the Meals screen, which Step Z removed). */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PrepScreenTest {
    @get:Rule val compose = createComposeRule()

    private val events = mutableListOf<String>()

    private fun regular(
        name: String,
        onList: Boolean = false,
    ) = Regular("cat:$name", name, QtyBase.PRESENCE, null, false, 3, onList)

    private val ui =
        PrepUi(
            loaded = true,
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
        PrepActions(
            onRegular = { events += "regular ${it.name}" },
            onAllRegulars = { events += "all regulars" },
            onRepeatLastShop = { events += "last shop" },
            onHave = { rs -> events += "have ${rs.joinToString { it.name }}" },
            onNeed = { events += "need ${it.name}" },
        )

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
}
