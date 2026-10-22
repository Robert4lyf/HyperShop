package com.robert4lyf.hypershop.ui.meals

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.RuleParser
import com.robert4lyf.hypershop.data.MealShopping
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.ui.HyperShopApp
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The returning weekly shop through the whole app, as the device scenario drives it. It
 * found the "Planned 2 meals again" snackbar over the Add button: the tap hit the snackbar.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class PlanFlowTest {
    @get:Rule val compose = createComposeRule()

    private var made: com.robert4lyf.hypershop.di.AppContainer? = null

    @org.junit.After
    fun stop() {
        made?.let {
            it.applicationScope.cancel()
            it.db.close()
        }
    }

    @Test
    fun repeatAddAndShop() {
        val c = TestContainer.create().also { made = it }
        val parser = RuleParser(c.catalog)
        runBlocking {
            for ((title, lines) in listOf(
                "Chilli" to listOf("2 onions", "1 tsp ground cumin"),
                "Dal" to listOf("1 onion", "200g basmati rice"),
            )) {
                val stored =
                    c.repository.storeRecipe(
                        "https://example.com/$title", title, 4, UnitLocale.METRIC, "WEB", ParsedBy.RULES, null, false,
                        lines.map {
                            parser.parse(it)
                        },
                    )
                c.repository.saveToLibrary(stored.recipe.id, null)
                c.planner.plan(c.planner.currentWeek() - 1, "https://example.com/$title")
            }
        }
        compose.setContent { HyperShopTheme { HyperShopApp(c) } }

        fun waitTag(t: String) = compose.waitUntil(10_000) { compose.onAllNodesWithTag(t).fetchSemanticsNodes().isNotEmpty() }
        waitTag("empty-plan")
        compose.onNodeWithTag("empty-plan").performClick()
        waitTag("repeat-week-button")
        compose.onNodeWithTag("repeat-week-button").performClick()
        waitTag("meals-add-to-list")
        compose.onNodeWithTag("meals-add-to-list").performClick()
        compose.waitUntil(10_000) {
            runBlocking { c.planner.week(c.planner.currentWeek()) }.let {
                    m ->
                m.size == 2 && m.all { it.shopping == MealShopping.TO_BUY }
            }
        }
        waitTag("meals-shop")
    }
}
