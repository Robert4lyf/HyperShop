package com.robert4lyf.hypershop.ui.list

import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.RuleParser
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.ui.HyperShopApp
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * Step Q: Buy again, from the list's own chip, with real history: the usual things offered and added in one tap.
 * It also guards Q.16: the sheet once stayed on "loading" with the data in hand, about one run in three.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class BuyAgainSheetTest {
    @get:Rule val compose = createComposeRule()

    /** Each tag and text on screen, for a failure message. */
    private fun onScreen(): List<String> =
        compose.onAllNodes(SemanticsMatcher("any") { true }, true).fetchSemanticsNodes()
            .map {
                    n ->
                listOfNotNull(
                    n.config.getOrNull(SemanticsProperties.TestTag),
                    n.config.getOrNull(SemanticsProperties.Text)?.joinToString(),
                ).joinToString(":")
            }
            .filter { it.isNotEmpty() }

    private fun waitFor(
        tag: String,
        why: String = "",
    ) {
        val end = System.currentTimeMillis() + 10_000
        while (compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()) {
            check(System.currentTimeMillis() < end) { "\"$tag\" never appeared $why; on screen: ${onScreen()}".trim() }
            compose.waitForIdle()
            Thread.sleep(20)
        }
    }

    @Test
    fun theSheetOffersTheUsualThingsAndAddAllPutsThemOn() {
        val c = TestContainer.create()
        val parser = RuleParser(c.catalog)
        runBlocking {
            // Two finished trips with the same things: they are "your usual".
            repeat(2) {
                for (line in listOf("milk", "bread", "6 eggs")) {
                    parser.parse(line).let { c.repository.manualAdd(it.name, it.shopBase(UnitLocale.METRIC), it.shopUnit) }
                }
                for (i in c.repository.snapshot().liveItems) c.repository.tick(i.id)
                c.repository.finish()
            }
            parser.parse("onions").let { c.repository.manualAdd(it.name, it.shopBase(UnitLocale.METRIC), it.shopUnit) }
        }
        compose.setContent { HyperShopTheme(dynamicColor = false) { HyperShopApp(c) } }
        waitFor("list")
        val end = System.currentTimeMillis() + 10_000
        while (compose.onAllNodes(hasTestTag("list-buy-again")).fetchSemanticsNodes().isEmpty()) {
            check(System.currentTimeMillis() < end) { "no Buy again chip" }
            compose.onNodeWithTag("list").performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        // On to the end, as a thumb does: a chip only just in at the bottom edge takes no tap at its centre.
        repeat(2) {
            compose.onNodeWithTag("list").performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        compose.onNodeWithTag("list-buy-again").assertIsDisplayed().performClick()
        waitFor("buy-again-sheet", "(the chip's tap did not open the sheet)")
        waitFor("buy-again-all", "(the sheet opened without the usual things)")
        compose.onNodeWithTag("buy-again-all").performClick()
        val done = System.currentTimeMillis() + 10_000
        while (runBlocking { c.repository.snapshot() }.itemByKey("cat:milk") == null) {
            check(System.currentTimeMillis() < done) { "milk not added" }
            compose.waitForIdle()
            Thread.sleep(20)
        }
        assertTrue(runBlocking { c.repository.snapshot() }.itemByKey("cat:bread") != null)
        compose.onNodeWithTag("buy-again-done").performClick()
    }
}
