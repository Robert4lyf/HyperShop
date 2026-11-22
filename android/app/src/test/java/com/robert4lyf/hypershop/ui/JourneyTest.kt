package com.robert4lyf.hypershop.ui

import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.RuleParser
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.importing.ImportInput
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** A small recipe page with its ingredients in JSON-LD, as most recipe sites publish them. */
private val CHILLI_PAGE =
    """
    <html><head><title>Weeknight chilli</title>
    <script type="application/ld+json">
    {"@context":"https://schema.org","@type":"Recipe","name":"Weeknight chilli","recipeYield":"4",
     "recipeIngredient":["2 onions","500g beef mince","400g tin chopped tomatoes","400g tin kidney beans","1 tsp salt"]}
    </script></head><body><h1>Weeknight chilli</h1></body></html>
    """.trimIndent()

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

    /** Waits for [tag], idling the main looper between looks; on a timeout, says which tag and where the app was. */
    private fun waitFor(tag: String) {
        val end = System.currentTimeMillis() + 10_000
        while (compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()) {
            check(System.currentTimeMillis() < end) { "\"$tag\" never appeared; route ${route()}" }
            compose.waitForIdle()
            Thread.sleep(20)
        }
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

    /** Holds the handle [tag] and drags it [dy] px: one gesture, counted with the taps. */
    private fun drag(
        tag: String,
        dy: Float,
    ) {
        node(tag).performTouchInput {
            down(center)
            repeat(20) {
                advanceEventTime(16)
                moveBy(androidx.compose.ui.geometry.Offset(0f, dy / 20))
            }
            advanceEventTime(16)
            up()
        }
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

    /** Swipes [list] up, as a person does, until [tag] is on screen: one scroll however many swipes. */
    private fun swipeTo(
        list: String,
        tag: String,
    ) {
        scrolls++
        val end = System.currentTimeMillis() + 10_000
        while (compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()) {
            check(System.currentTimeMillis() < end) { "\"$tag\" not found by swiping $list" }
            compose.onNodeWithTag(list).performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        // On to the end, as a thumb does, so the row is clear of a snackbar at the bottom.
        repeat(2) {
            compose.onNodeWithTag(list).performTouchInput { swipeUp() }
            compose.waitForIdle()
        }
        settle()
    }

    /**
     * A message (its few seconds, with Undo) over the row about to be tapped: a person waits it out or swipes it away.
     * Not counted. Step AA.4 keeps messages off the bottom bar; a list row can still be under one.
     */
    private fun waitOutAMessageOver(tag: String) {
        val row = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInRoot
        val message = compose.onAllNodes(hasTestTag("snackbar"), useUnmergedTree = true).fetchSemanticsNodes().firstOrNull()?.boundsInRoot
        if (message == null || message.height == 0f || !message.overlaps(row)) return
        org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(6))
        compose.waitUntil(5_000) { compose.onAllNodes(hasText("Undo")).fetchSemanticsNodes().isEmpty() }
    }

    /** Scrolls [list] to [tag] (a scroll, counted apart from taps). */
    private fun scrollTo(
        list: String,
        tag: String,
    ) {
        scrolls++
        // Idling the main looper between tries, as [waitFor] does: waitUntil alone does not run it, so a list still
        // loading (its rows arrive there) would never come in, and on a slow machine the scroll timed out.
        val end = System.currentTimeMillis() + 10_000
        while (runCatching { compose.onNodeWithTag(list).performScrollToNode(hasTestTag(tag)) }.isFailure) {
            check(System.currentTimeMillis() < end) { "\"$tag\" never came into \"$list\"; route ${route()}" }
            compose.waitForIdle()
            Thread.sleep(20)
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

    /**
     * The core job: a recipe link shared to the app (an offline page served on this machine, no Claude key), the review
     * accepted as it is, every item ticked, the trip finished.
     */
    @Test
    fun coreShareToFinish() {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(CHILLI_PAGE))
        try {
            val c = TestContainer.create(pages = server)
            show(c)
            // The share happens outside the app, so it is not a tap. It is queued as importShared queues a shared link
            // (the page is on plain http on this machine, which a real share's link parsing would not keep).
            runBlocking {
                c.awaitReady()
                c.queueReview(c.importJobs.enqueue(ImportInput(server.url("/chilli").toString())))
            }
            settle()
            tap("add")
            compose.waitUntil(10_000) { runBlocking { c.repository.snapshot() }.liveItems.size >= 4 }
            waitFor("tick-${runBlocking { c.repository.snapshot() }.liveItems.first().id}")
            settle()
            for (item in runBlocking { c.repository.snapshot() }.liveItems) {
                val tag = "tick-${item.id}"
                if (compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()) scrollTo("list", tag)
                waitOutAMessageOver(tag)
                tap(tag)
            }
            // Step AA.4: the review's "Added" message sits above the Finish bar now, so nothing waits for it to go.
            tap("finish")
            compose.waitUntil(10_000) { runBlocking { c.repository.snapshot() }.liveItems.isEmpty() }
            record("core share to finish", c)
            assertTrue(runBlocking { c.repository.snapshot() }.liveItems.isEmpty())
        } finally {
            server.shutdown()
        }
    }

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

    /** Two finished trips on which toothpaste and kitchen roll were bought by hand: they are regulars now. */
    private fun AppContainer.twoTripsOfRegulars() =
        runBlocking {
            awaitReady()
            repeat(2) {
                for (name in listOf("toothpaste", "kitchen roll")) {
                    val id = repository.manualAdd(name, com.robert4lyf.hypershop.core.model.QtyBase.PRESENCE, null)
                    repository.tick(id)
                }
                repository.finish()
            }
        }

    /**
     * Step AB: adding by hand to a list that already has items. Bin bags typed in full, then two things added often
     * (toothpaste and kitchen roll, regulars from two finished trips), the fastest way the app offers.
     */
    @Test
    fun addingByHand() {
        val c = TestContainer.create()
        c.twoTripsOfRegulars()
        runBlocking {
            for (name in listOf("milk", "bread", "eggs")) {
                c.repository.manualAdd(name, com.robert4lyf.hypershop.core.model.QtyBase.PRESENCE, null)
            }
        }
        show(c)
        waitFor("tick-${c.itemId("cat:milk")}")
        type("add-item", "bin bags")
        compose.waitUntil(10_000) { runBlocking { c.repository.snapshot() }.itemByKey("cat:bin_bags") != null }
        addTwoRegulars(c)
        compose.waitUntil(10_000) {
            val l = runBlocking { c.repository.snapshot() }
            listOf("toothpaste", "kitchen roll").all { l.itemByKey(c.repository.target(it).mergeKey) != null }
        }
        record("adding by hand", c)
    }

    /** Toothpaste and kitchen roll, the owner's regulars, onto the list. */
    private fun addTwoRegulars(c: AppContainer) {
        // AB.0 went through Buy again (list ⋮ › Buy again › each chip › Done: 5 taps, a sheet). AB.1: the field is still
        // focused after Enter, and empty, so the usual items are chips under it: one tap each.
        tap("usual-${c.repository.target("toothpaste").mergeKey}")
        tap("usual-${c.repository.target("kitchen roll").mergeKey}")
    }

    /** Step AC: Salad before Vegetables, set in Settings › Aisles › Walking order and seen on the list. */
    @Test
    fun saladBeforeVegetables() {
        val c = TestContainer.create()
        runBlocking {
            c.awaitReady()
            for (name in listOf(
                "onion",
                "lettuce",
            )) c.repository.manualAdd(name, com.robert4lyf.hypershop.core.model.QtyBase.PRESENCE, null)
        }
        show(c)
        val onion = c.itemId("cat:onion")
        val lettuce = c.itemId("cat:lettuce")
        waitFor("tick-$onion")
        // Step AD.1: Walking order is Settings' first row, and Salad is dragged by its handle above Vegetables.
        tap("open-settings")
        tap("open-aisles")
        waitFor("groups-PRODUCE")
        tap("groups-PRODUCE")
        drag("drag-group-PRODUCE-Salad", -compose.onNodeWithTag("group-PRODUCE-Vegetables").fetchSemanticsNode().boundsInRoot.height)
        compose.waitUntil(10_000) {
            runBlocking { c.repository.shelfOrder(1) }.groupSort(AisleCode.PRODUCE, "Salad", 0) <
                runBlocking { c.repository.shelfOrder(1) }.groupSort(AisleCode.PRODUCE, "Vegetables", 0)
        }
        // Counted to the change: the order is stored for the list, the shared text, the widget and the watch. The two
        // backs to see it on the list are not counted (a person carries on in Settings or goes back, as they like).
        record("salad before vegetables", c)
        tap("aisles-back")
        tap("settings-back")
        waitFor("tick-$lettuce")
        val top = { id: Long -> compose.onNodeWithTag("tick-$id", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top }
        compose.waitUntil(10_000) { top(lettuce) < top(onion) }
    }

    /** Meal-based shopping: three saved recipes put on the list, one served to 5, rice partly at home. */
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
        // Step Z: Recipes, Add to list on each of the three: each goes on the list at once, planned for this week.
        tap("tab-${Routes.RECIPES}")
        for (k in keys) {
            scrollTo("recipes", "lib-add-$k")
            tap("lib-add-$k")
            // Step AA: on the list, the card shows "− Serves N +" in place of Add to list; the chilli is for 5.
            if (k == keys.first()) tap("lib-serves-$k-more")
        }
        compose.waitUntil(10_000) { runBlocking { c.planner.week(c.planner.currentWeek()) }.count { it.useId != null } == 3 }
        val chilli = runBlocking { c.planner.week(c.planner.currentWeek()) }.first { it.title == "Weeknight chilli" }.useId
        compose.waitUntil(10_000) { runBlocking { c.repository.snapshot() }.uses[chilli!!]?.servings == 5 }
        tap("tab-${Routes.LIST}")
        // Rice partly at home (300 g of the 500 g): Step Z has no food-at-home check; the rice's amount is changed on
        // the list, in its sheet, to what is still to buy.
        val rice = c.itemId("cat:basmati_rice")
        if (compose.onAllNodes(hasTestTag("item-$rice")).fetchSemanticsNodes().isEmpty()) scrollTo("list", "item-$rice")
        waitOutAMessageOver("item-$rice")
        tap("item-$rice")
        tap("item-change")
        // Step AA: "Change amount" opens with the amount focused and selected: no tap on the field.
        node("edit-qty").assertIsFocused()
        node("edit-qty").performTextReplacement("200 g")
        typed++
        tap("save-item")
        compose.waitUntil(10_000) { compose.onAllNodes(isDialog()).fetchSemanticsNodes().isEmpty() }
        settle()
        record("meal-based shopping", c)
        val list = runBlocking { c.repository.snapshot() }
        assertEquals(setOf(Routes.LIST), setOf(route()))
        assertTrue(list.liveItems.size >= 6)
        assertEquals("200 g", com.robert4lyf.hypershop.ui.list.ItemEdit.text(list.total(rice)))
    }
}
