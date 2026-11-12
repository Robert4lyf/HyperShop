package com.robert4lyf.hypershop.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.RuleParser
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/**
 * The two everyday journeys through the real app shell, counted: taps (clicks and keyboard actions), entries typed,
 * and screen changes (destinations and dialogs or sheets that open). The counts are written to
 * build/reports/journeys.txt, so a change to either journey is measured, not estimated.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class JourneyTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var nav: NavHostController
    private var taps = 0
    private var typed = 0
    private var screens = 0
    private var scrolls = 0
    private var lastRoute: String? = null
    private var dialogShown = false

    private fun show(c: AppContainer) {
        compose.setContent {
            nav = rememberNavController()
            HyperShopTheme(dynamicColor = false) { HyperShopApp(c, nav) }
        }
        settle()
        lastRoute = route()
    }

    private fun route() = nav.currentBackStackEntry?.destination?.route

    /** Lets the app settle, then counts a destination or a dialog/sheet that appeared since the last look. */
    private fun settle() {
        repeat(3) {
            compose.waitForIdle()
            Thread.sleep(60)
        }
        compose.waitForIdle()
        val r = route()
        if (lastRoute != null && r != lastRoute) screens++
        lastRoute = r
        val dialog = compose.onAllNodes(isDialog()).fetchSemanticsNodes().isNotEmpty()
        if (dialog && !dialogShown) screens++
        dialogShown = dialog
    }

    private fun waitFor(tag: String) =
        compose.waitUntil(10_000) {
            compose.waitForIdle()
            compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
        }

    private fun node(tag: String): SemanticsNodeInteraction {
        waitFor(tag)
        return compose.onNodeWithTag(tag)
    }

    private fun tap(tag: String) {
        node(tag).performClick()
        taps++
        settle()
    }

    /** Types [text] into a field: a tap to focus it, the entry, and Done on the keyboard when [done]. */
    private fun type(
        tag: String,
        text: String,
        done: Boolean = true,
    ) {
        node(tag).performClick()
        taps++
        node(tag).performTextInput(text)
        typed++
        if (done) {
            node(tag).performImeAction()
            taps++
        }
        settle()
    }

    /** Scrolls [list] to [tag] (a scroll, counted apart from taps). */
    private fun scrollTo(
        list: String,
        tag: String,
    ) {
        scrolls++
        compose.waitUntil(10_000) {
            runCatching { compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag)) }.isSuccess
        }
        settle()
    }

    private fun record(
        journey: String,
        c: AppContainer,
    ) {
        val line = "%-24s taps=%d typed=%d scrolls=%d screens=%d".format(journey, taps, typed, scrolls, screens)
        println("JOURNEY $line")
        File("build/reports").mkdirs()
        File("build/reports/journeys.txt").appendText(line + "\n")
    }

    private fun AppContainer.saveRecipe(
        title: String,
        vararg lines: String,
    ): String =
        runBlocking {
            val parser = RuleParser(catalog)
            val s =
                repository.storeRecipe(
                    "https://example.com/${title.lowercase().replace(' ', '-')}", title, 4, UnitLocale.METRIC, "WEB",
                    ParsedBy.RULES, null, false, lines.map { parser.parse(it) },
                )
            repository.saveToLibrary(s.recipe.id, null)
            repository.libraryKeyOf(s.recipe.id)!!
        }

    private fun AppContainer.itemId(key: String) = runBlocking { repository.snapshot() }.itemByKey(key)!!.id

    /** Quick shopping: a first run, two things typed, both bought, the trip finished. */
    @Test
    fun quickShopping() {
        val c = TestContainer.create()
        show(c)
        type("add-item", "milk")
        type("add-item", "bread")
        tap("tick-${c.itemId("cat:milk")}")
        tap("tick-${c.itemId("cat:bread")}")
        tap("finish")
        compose.waitUntil(10_000) { runBlocking { c.repository.snapshot() }.liveItems.isEmpty() }
        record("quick shopping", c)
        assertTrue(runBlocking { c.repository.snapshot() }.liveItems.isEmpty())
    }

    /** Meal-based shopping: three saved recipes chosen for this week, one served to 5, put on the list, rice partly at home. */
    @Test
    fun mealBasedShopping() {
        val c = TestContainer.create()
        val keys =
            listOf(
                c.saveRecipe("Weeknight chilli", "2 onions", "500g beef mince", "200g basmati rice"),
                c.saveRecipe("Red lentil dal", "1 onion", "200g red lentils", "300g basmati rice"),
                c.saveRecipe("Pesto pasta", "300g penne", "1 jar pesto"),
            )
        show(c)
        // Step Q: Meals, Add meal (a sheet with the saved recipes), three added, Done.
        if (route() != Routes.MEALS) tap("tab-${Routes.MEALS}")
        tap("meals-add-meal")
        for (k in keys) {
            scrollTo("add-meal-list", "pick-add-$k")
            tap("pick-add-$k")
        }
        tap("add-meal-done")
        val chilli = runBlocking { c.planner.week(c.planner.currentWeek()) }.first { it.title == "Weeknight chilli" }.id
        scrollTo("meals", "meal-more-$chilli")
        tap("meal-more-$chilli")
        // Make shopping list ends on the list; the optional food-at-home check is a chip there.
        tap("meals-add-to-list")
        compose.waitUntil(10_000) { runBlocking { c.repository.snapshot() }.itemByKey("cat:basmati_rice") != null }
        tap("list-home-check")
        scrollTo("home-check-list", "home-some-cat:basmati_rice")
        tap("home-some-cat:basmati_rice")
        type("home-some-amount", "300 g", done = false)
        tap("home-some-set")
        // The amount is saved, and the dialog closes, before the next tap.
        compose.waitUntil(10_000) { compose.onAllNodes(hasTestTag("home-some-dialog")).fetchSemanticsNodes().isEmpty() }
        tap("home-check-done")
        record("meal-based shopping", c)
        val list = runBlocking { c.repository.snapshot() }
        val rice = list.itemByKey("cat:basmati_rice")!!
        assertEquals(setOf(Routes.LIST), setOf(route()))
        assertTrue(list.liveItems.size >= 6)
        assertTrue(rice.id > 0)
    }
}
