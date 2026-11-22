package com.robert4lyf.hypershop.ui.recipes

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.RuleParser
import com.robert4lyf.hypershop.testutil.Shots
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.ui.HyperShopApp
import com.robert4lyf.hypershop.ui.Routes
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Step AD.5: on a small phone (360 × 640 dp), through the real app shell, at least four saved recipes are in view at once. */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class RecipesCompactTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun fourRecipesShowAt360By640() {
        val c = TestContainer.create()
        val titles = listOf("Weeknight chilli", "Red lentil dal", "Pesto pasta", "Fish pie", "Thai green curry", "Shakshuka")
        runBlocking {
            c.awaitReady()
            val parser = RuleParser(c.catalog)
            val ids =
                titles.map { t ->
                    val s =
                        c.repository.storeRecipe(
                            "https://www.bbcgoodfood.com/recipes/${t.lowercase().replace(' ', '-')}", t, 4, UnitLocale.METRIC, "WEB",
                            ParsedBy.RULES, null, false, listOf(parser.parse("2 onions")),
                        )
                    c.repository.saveToLibrary(s.recipe.id, null)
                    s.recipe.id
                }
            // One on the list, so its card shows the tick and the stepper.
            c.planner.addToList(c.repository.libraryKeyOf(ids.first())!!)
        }
        compose.setContent { HyperShopTheme(dynamicColor = false) { HyperShopApp(c, rememberNavController()) } }
        compose.waitUntil(10_000) {
            compose.onAllNodes(hasTestTag("tab-${Routes.RECIPES}"), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
        }
        compose.onNode(hasTestTag("tab-${Routes.RECIPES}"), useUnmergedTree = true).performClick()
        val title =
            SemanticsMatcher("a recipe's title") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("lib-title-") == true }
        compose.waitUntil(10_000) { compose.onAllNodes(title, useUnmergedTree = true).fetchSemanticsNodes().size >= 4 }
        compose.waitForIdle()
        compose.onRoot().captureRoboImage(Shots.path("recipes-360x640.png"))
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val tabs = compose.onNode(hasTestTag("tab-${Routes.RECIPES}"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val bottom = minOf(root.bottom, tabs.top)
        // Visible: its title wholly on screen above the tab bar (the fourth card's buttons may run under it).
        val shown =
            compose.onAllNodes(title, useUnmergedTree = true).fetchSemanticsNodes().count {
                it.boundsInRoot.top >= 0f && it.boundsInRoot.bottom <= bottom
            }
        assertTrue("$shown recipe titles on screen above $bottom", shown >= 4)
    }
}
