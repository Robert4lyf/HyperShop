package com.robert4lyf.hypershop.device

import android.net.Uri
import android.util.Log
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ComposeTimeoutException
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.backup.BackupManager
import com.robert4lyf.hypershop.backup.Inspection
import com.robert4lyf.hypershop.backup.RestoreOutcome
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.plan.Week
import com.robert4lyf.hypershop.data.MealShopping
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.settings.ListMode
import com.robert4lyf.hypershop.ui.HyperShopApp
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Release B's scenarios on a device (docs/design/release-b.md): the real
 * composables over Room on the device's SQLite. Scenario B also counts the
 * taps, typed entries and screens of a returning weekly shop, both the quick
 * way and item by item, and logs them under HyperShopEffort for
 * docs/reports/release-b-effort.md. Run in CI's device job, never on a phone
 * with a real list.
 */
@RunWith(AndroidJUnit4::class)
class WeeklyShopScenariosTest {
    @get:Rule val compose = createComposeRule()

    private var taps = 0
    private var typed = 0
    private var scrolls = 0

    private fun show(c: AppContainer) {
        compose.setContent { HyperShopTheme { HyperShopApp(c) } }
        compose.waitForIdle()
    }

    private fun waitFor(what: () -> Boolean) = compose.waitUntil(15_000) { runCatching(what).getOrDefault(false) }

    /** [waitFor], and on a timeout says what was awaited and what the screen and the data held instead. */
    private fun waitFor(
        what: String,
        state: () -> String,
        until: () -> Boolean,
    ) {
        try {
            waitFor(until)
        } catch (timeout: ComposeTimeoutException) {
            throw AssertionError("Timed out waiting for $what. ${runCatching(state).getOrElse { "(state unreadable: $it)" }}", timeout)
        }
    }

    /** The test tags on screen starting with [prefix], for a failure message. */
    private fun tagsOnScreen(prefix: String): List<String> =
        compose.onAllNodes(SemanticsMatcher("tag") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(prefix) == true }, true)
            .fetchSemanticsNodes()
            .mapNotNull { it.config.getOrNull(SemanticsProperties.TestTag) }

    private fun weekSummary(): String =
        compose.onAllNodesWithTag("week-summary").fetchSemanticsNodes()
            .joinToString { n -> n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString() }

    /**
     * Plans the saved recipe [key] from the Plan screen, then waits for its meal card to be on screen.
     *
     * Planning puts a meal card *above* the picks, and it arrives from Room through the screen's flow after
     * the tap's `waitForIdle` has returned (Compose's idling does not cover a flow on another dispatcher).
     * Scrolling to the next pick before it lands races it: the late card pushes that pick down, out of the
     * composition, and the next tap waits for a node that is no longer there. So the plan must be in the
     * database and in the screen's own count ("19–25 Oct · N meals · …") before anything else moves.
     */
    private fun planPick(
        c: AppContainer,
        week: Week,
        key: String,
        meals: Int,
    ) {
        scrollTo("pick-add-$key")
        tap("pick-add-$key")
        waitFor(
            "\"$key\" planned as meal $meals of week $week",
            {
                val planned = runBlocking { c.planner.week(week) }.map { it.libraryKey }
                "Planned: $planned; summary: \"${weekSummary()}\"; picks on screen: ${tagsOnScreen("pick-add-")}"
            },
        ) {
            val count = if (meals == 1) "1 meal" else "$meals meals"
            runBlocking { c.planner.week(week) }.size == meals && weekSummary().split(" · ").contains(count)
        }
    }

    private fun has(tag: String) = compose.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()

    /**
     * A shopper's tap: waits for the control to be there, as a person looks before
     * tapping, and presses the control itself (its click action, as TalkBack does).
     * An Undo snackbar can sit over part of a list for a few seconds; a person taps
     * around it, a touch injected at the control's centre would land on it.
     */
    private fun tap(tag: String) {
        // Found, then pressed: between the two the list can be laid out again (the Plan screen's picks re-sort
        // after the last one was planned) and the node be momentarily gone. A person looks again; so does this.
        // The press is never made from inside waitUntil's condition: an action there can run in the middle of
        // a frame's layout ("performMeasureAndLayout called during measure layout").
        val end = System.currentTimeMillis() + 15_000
        while (true) {
            waitFor("\"$tag\" on screen", { "On screen: ${tagsOnScreen("")}" }) { has(tag) }
            val pressed =
                try {
                    compose.onNodeWithTag(tag).performSemanticsAction(SemanticsActions.OnClick)
                    true
                } catch (gone: AssertionError) {
                    if (System.currentTimeMillis() > end) throw gone
                    false
                }
            if (pressed) break
            compose.waitForIdle()
        }
        taps++
        compose.waitForIdle()
    }

    /** Scrolls the Plan screen to [tag] (counted apart: a swipe, not a decision). */
    private fun scrollTo(tag: String) {
        waitFor { has("meals") }
        // Not while the last tap's change is still being laid out (a scroll then measured mid-layout),
        // and not before the row is in the list at all (the saved recipes load after the screen opens).
        compose.waitForIdle()
        val inList = hasTestTag(tag)
        waitFor { runCatching { compose.onNodeWithTag("meals").performScrollToNode(inList) }.isSuccess }
        scrolls++
    }

    private fun type(line: String) {
        // The field focused and its input session up before typing, as a person taps it first.
        waitFor { has("add-item") }
        compose.onNodeWithTag("add-item").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("add-item").performTextInput(line)
        compose.onNodeWithTag("add-item").performImeAction()
        typed++
        compose.waitForIdle()
    }

    private fun AppContainer.items() = runBlocking { repository.snapshot() }.liveItems

    private fun AppContainer.total(key: String): Rational? =
        runBlocking { repository.snapshot() }.let { l -> l.itemByKey(key)?.let { l.total(it.id)[BaseKey.ITEMS] } }

    private val regulars = listOf("milk", "bread", "6 eggs", "bananas", "butter", "toilet roll", "washing-up liquid")

    /**
     * Someone who has shopped here before: four saved recipes planned last week,
     * and two finished trips with the same household items typed in.
     */
    private fun returning(): AppContainer {
        val c = DeviceContainer.create()
        val recipes =
            listOf(
                c.saveRecipe("Weeknight chilli", "2 onions", "500g beef mince", "400g tin chopped tomatoes", "1 tsp ground cumin"),
                c.saveRecipe("Red lentil dal", "1 onion", "200g red lentils", "1 tsp ground cumin", "200g basmati rice"),
                c.saveRecipe("Pesto pasta", "300g penne", "1 jar pesto", "100g parmesan"),
                c.saveRecipe("Fish tacos", "4 tortillas", "2 limes", "300g white fish", "1 tbsp olive oil"),
            )
        runBlocking {
            val last = c.planner.currentWeek() - 1
            for (id in recipes) c.planner.plan(last, c.db.dao().recipe(id)!!.libraryKey!!)
            val parser = com.robert4lyf.hypershop.core.parse.RuleParser(c.catalog)
            repeat(2) {
                for (line in regulars) parser.parse(
                    line,
                ).let { c.repository.manualAdd(it.name, it.shopBase(UnitLocale.METRIC), it.shopUnit) }
                for (i in c.repository.snapshot().liveItems) c.repository.tick(i.id)
                c.repository.finish()
            }
        }
        return c
    }

    private fun effort(
        how: String,
        startMs: Long,
    ) = Log.i(
        "HyperShopEffort",
        "%-28s taps=%d typed=%d scrolls=%d time=%dms".format(how, taps, typed, scrolls, System.currentTimeMillis() - startMs),
    )

    /** A: a first list is just as quick as before: no plan card, no new step between typing and shopping. */
    @Test
    fun a_firstTimeShoppingNeedsNoPlanning() {
        val c = DeviceContainer.create()
        show(c)
        waitFor { has("empty") }
        assertTrue(!has("plan-card") && !has("empty-plan"))
        for (line in listOf("milk", "bread", "6 eggs")) type(line)
        waitFor { c.items().size == 3 }
        assertTrue(!has("plan-card"))
        tap("start-shopping")
        waitFor { has("shop") }
    }

    /** B: last week's meals again, the usual items, the cupboard checked, shopping: the quick way. */
    @Test
    fun b_returningWeeklyShopperTheQuickWay() {
        val c = returning()
        show(c)
        val t0 = System.currentTimeMillis()
        tap("empty-plan")
        tap("repeat-week-button")
        tap("meals-add-to-list")
        waitFor { runBlocking { c.planner.week(c.planner.currentWeek()) }.all { it.shopping == MealShopping.TO_BUY } }
        scrollTo("buy-again")
        tap("buy-again-all")
        waitFor { runBlocking { c.buyAgain.view() }.regulars.all { it.onList } }
        scrollTo("home-check")
        tap("home-have-all")
        waitFor { c.items().none { it.mergeKey.contains("cumin") } }
        tap("meals-shop")
        waitFor { has("shop") }
        effort("weekly shop, quick way", t0)
        // One onion row for chilli and dal; the cumin both wanted is at home, once; every regular on once.
        assertEquals(Rational.of(3), c.total("cat:onion"))
        assertTrue(c.items().none { it.mergeKey.contains("cumin") })
        assertEquals(1, c.items().count { it.displayName.contains("milk") })
        assertEquals(ListMode.SHOP, runBlocking { c.settings.listMode.first() })
        assertTrue("taps $taps", taps <= 6)
        assertEquals(0, typed)
    }

    /** B, the long way round, for comparison: each meal planned by hand, each regular typed, each cupboard item checked. */
    @Test
    fun b_returningWeeklyShopperItemByItem() {
        val c = returning()
        show(c)
        val t0 = System.currentTimeMillis()
        tap("empty-plan")
        val keys = runBlocking { c.planner.week(c.planner.currentWeek() - 1) }.map { it.libraryKey }
        for ((i, k) in keys.withIndex()) planPick(c, c.planner.currentWeek(), k, i + 1)
        tap("meals-add-to-list")
        waitFor {
            runBlocking { c.planner.week(c.planner.currentWeek()) }.let {
                    m ->
                m.size == 4 && m.all { it.shopping == MealShopping.TO_BUY }
            }
        }
        compose.onNodeWithTag("meals").assertExists()
        // Back to the list and type the usual items.
        tap("back")
        for (line in regulars) type(line)
        tap("plan-card")
        val cupboard = runBlocking { c.homeCheck.view(c.repository.snapshot()) }.filter { !it.have }.map { it.mergeKey }
        for (k in cupboard) {
            scrollTo("home-have-$k")
            tap("home-have-$k")
            // Gone from the list and from the screen: the row leaving moves the ones below it up, so the next
            // scroll waits for that rather than racing it (as [planPick] waits for a new meal card).
            waitFor("\"$k\" had at home", { "Rows on screen: ${tagsOnScreen("home-have-")}" }) {
                c.items().none { it.mergeKey == k } && !has("home-have-$k")
            }
        }
        tap("meals-shop")
        waitFor { has("shop") }
        effort("weekly shop, item by item", t0)
        assertEquals(Rational.of(3), c.total("cat:onion"))
    }

    /** C: a recipe, typed items and a regular on one list; the same thing from each is one row. */
    @Test
    fun c_mixedListMergesEverySource() {
        val c = DeviceContainer.create()
        val chilli = c.saveRecipe("Weeknight chilli", "2 onions", "200 ml milk")
        runBlocking {
            c.planner.plan(c.planner.currentWeek(), c.db.dao().recipe(chilli)!!.libraryKey!!)
            repeat(2) {
                c.repository.manualAdd("onion", QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), null)
                for (i in c.repository.snapshot().liveItems) c.repository.tick(i.id)
                c.repository.finish()
            }
        }
        show(c)
        type("1 onion")
        tap("plan-card")
        tap("meals-add-to-list")
        waitFor { c.total("cat:onion") == Rational.of(3) }
        // The onion regular is on the list already: shown there, and Add all has nothing to double.
        scrollTo("buy-again")
        waitFor { has("regular-cat:onion") }
        tap("regular-cat:onion")
        compose.waitForIdle()
        assertEquals(Rational.of(3), c.total("cat:onion"))
        assertEquals(1, c.items().count { it.mergeKey == "cat:onion" })
        assertEquals(1, c.items().count { it.mergeKey == "cat:milk" })
    }

    /** D: part bought, the app restarts, servings change: what was bought stays bought. */
    @Test
    fun d_interruptedShopKeepsWhatWasBought() {
        val first = DeviceContainer.create()
        val chilli = first.saveRecipe("Weeknight chilli", "2 onions", "1 lime")
        runBlocking {
            val week = first.planner.currentWeek()
            first.planner.plan(week, first.db.dao().recipe(chilli)!!.libraryKey!!)
            first.planner.addWeekToList(week)
            first.repository.tick(first.repository.snapshot().itemByKey("cat:onion")!!.id)
        }
        // A new container over the same database: what a restart sees.
        val c = DeviceContainer.create(db = first.db)
        show(c)
        tap("plan-card")
        val meal = runBlocking { c.planner.week(c.planner.currentWeek()) }.single()
        tap("meal-more-${meal.id}")
        tap("meal-more-${meal.id}")
        waitFor { c.total("cat:onion") == Rational.of(3) }
        val l = runBlocking { c.repository.snapshot() }
        val onion = l.itemByKey("cat:onion")!!.id
        assertEquals(Rational.of(2), l.covered(onion)[BaseKey.ITEMS])
        assertTrue(l.isPartial(onion))
    }

    /** E: next week planned without touching this week's shop. */
    @Test
    fun e_planningNextWeekLeavesThisShopAlone() {
        val c = DeviceContainer.create()
        val chilli = c.saveRecipe("Weeknight chilli", "2 onions")
        runBlocking { c.repository.manualAdd("milk", QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), null) }
        val before = c.items().map { it.mergeKey }
        show(c)
        tap("plan-card")
        tap("week-next")
        waitFor { compose.onAllNodesWithTag("week-name").fetchSemanticsNodes().any { it.config.toString().contains("Next week") } }
        val key = runBlocking { c.db.dao().recipe(chilli)!!.libraryKey!! }
        planPick(c, c.planner.currentWeek() + 1, key, 1)
        assertEquals(before, c.items().map { it.mergeKey })
        assertTrue(runBlocking { c.planner.week(c.planner.currentWeek()) }.isEmpty())
    }

    /** F: a backup with a plan and shopping history, restored on a fresh install: the plan still knows what is on the list. */
    @Test
    fun f_backupCarriesThePlanAndHistory() {
        val c = returning()
        runBlocking {
            val week = c.planner.currentWeek()
            c.planner.repeatWeek(week - 1, week)
            c.planner.addWeekToList(week)
        }
        val file = File(c.context.cacheDir, "backup-${System.nanoTime()}.json")
        assertTrue(runBlocking { BackupManager(c, "device").writeTo(c.context, Uri.fromFile(file)) }.isSuccess)
        val fresh = DeviceContainer.create()
        val m = BackupManager(fresh, "device")
        val read = runBlocking { m.inspect(fresh.context, Uri.fromFile(file)) } as Inspection.Ok
        assertTrue(runBlocking { m.restore(read.file) } is RestoreOutcome.Restored)
        val week = fresh.planner.currentWeek()
        assertEquals(4, runBlocking { fresh.planner.week(week) }.count { it.shopping == MealShopping.TO_BUY })
        assertEquals(null, runBlocking { fresh.planner.addWeekToList(week) })
        assertEquals(7, runBlocking { fresh.buyAgain.view() }.regulars.size)
    }
}
