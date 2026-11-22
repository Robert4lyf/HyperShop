package com.robert4lyf.hypershop.ui

import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.RuleParser
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.testutil.Shots
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Step AA.5: on a small phone (360 × 640 dp) the list's first aisle heading and at least three items are on screen
 * without scrolling, through the real app shell (its tab bar included), and the Add item field is pinned.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class CompactHeaderTest {
    @get:Rule val compose = createComposeRule()

    private fun AppContainer.onTheList(
        title: String,
        vararg lines: String,
    ) = runBlocking {
        awaitReady()
        val parser = RuleParser(catalog)
        val s =
            repository.storeRecipe(
                "https://example.com/${title.lowercase().replace(' ', '-')}", title, 4, UnitLocale.METRIC, "WEB",
                ParsedBy.RULES, null, false, lines.map { parser.parse(it) },
            )
        repository.saveToLibrary(s.recipe.id, null)
        planner.addToList(repository.libraryKeyOf(s.recipe.id)!!)
    }

    private fun waitForTag(prefix: String) =
        compose.waitUntil(10_000) {
            compose.onAllNodes(
                androidx.compose.ui.test.SemanticsMatcher("tag $prefix") {
                    it.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.TestTag)?.startsWith(prefix) == true
                },
            ).fetchSemanticsNodes().isNotEmpty()
        }

    @Test
    fun theFirstAisleAndThreeItemsAreOnScreenAt360By640() {
        val c = TestContainer.create()
        c.onTheList(
            "Weeknight chilli",
            "2 onions",
            "3 carrots",
            "1 red pepper",
            "1 courgette",
            "500g beef mince",
            "4 sausages",
            "400g tin chopped tomatoes",
        )
        compose.setContent { HyperShopTheme(dynamicColor = false) { HyperShopApp(c, rememberNavController()) } }
        waitForTag("tick-")
        compose.waitForIdle()
        val root = compose.onRoot().fetchSemanticsNode().boundsInRoot
        val tabs = compose.onNode(hasTestTag("tab-${Routes.LIST}"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val visibleBottom = minOf(root.bottom, tabs.top)
        val aisle = compose.onAllNodes(hasTestTag("aisle-PRODUCE"), useUnmergedTree = true).fetchSemanticsNodes()
        assertTrue("the first aisle heading is on screen", aisle.isNotEmpty() && aisle.first().boundsInRoot.bottom <= visibleBottom)
        val items =
            runBlocking { c.repository.snapshot() }.liveItems.mapNotNull { item ->
                compose.onAllNodes(hasTestTag("tick-${item.id}"), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
            }
        val shown = items.count { it.top >= 0f && it.bottom <= visibleBottom }
        // Step AC: slim aisle rows and denser items: at least five under the top row and the Add field.
        assertTrue("$shown items on screen above $visibleBottom: $items", shown >= 5)
        // The Add field is pinned: above the list, not one of its scrolled items.
        val add = compose.onNode(hasTestTag("add-item"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val list = compose.onNode(hasTestTag("list"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertTrue("the Add field $add sits above the list $list", add.bottom <= list.top + 0.5f)
        compose.onRoot().captureRoboImage(Shots.path("list-360x640.png"))
    }
}
