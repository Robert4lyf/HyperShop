package com.robert4lyf.hypershop.scenarios

import android.os.Looper
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.data.MealShopping
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.importing.ImportError
import com.robert4lyf.hypershop.importing.ImportResult
import com.robert4lyf.hypershop.settings.ListMode
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.await
import com.robert4lyf.hypershop.testutil.awaitTrue
import com.robert4lyf.hypershop.testutil.readyJob
import com.robert4lyf.hypershop.ui.common.UiMessage
import com.robert4lyf.hypershop.ui.list.ListUi
import com.robert4lyf.hypershop.ui.list.ListViewModel
import com.robert4lyf.hypershop.ui.list.TickState
import com.robert4lyf.hypershop.ui.list.add
import com.robert4lyf.hypershop.ui.meals.MealsViewModel
import com.robert4lyf.hypershop.ui.recipes.RecipesViewModel
import com.robert4lyf.hypershop.ui.review.Badge
import com.robert4lyf.hypershop.ui.review.ReviewState
import com.robert4lyf.hypershop.ui.review.ReviewUi
import com.robert4lyf.hypershop.ui.review.ReviewViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Release C's scenarios D–F (docs/design/release-c.md › C.7), through the real
 * view models and repository, each on the invariants CLAUDE.md names: a purchase
 * is never undone, a planned meal is not a recipe use, ticks never create stock.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReleaseCScenariosTest {
    private val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val models = CopyOnWriteArrayList<androidx.lifecycle.ViewModel>()

    private fun <T : androidx.lifecycle.ViewModel> tracked(vm: T): T = vm.also { models += it }

    @After
    fun stop() {
        collectors.cancel()
        for (vm in models) vm.viewModelScope.cancel()
        Thread.sleep(300)
    }

    private fun ListUi.row(name: String) = aisles.flatMap { it.second }.first { it.name == name }

    private fun ReviewUi.row(key: String) = aisles.flatMap { it.second }.first { it.key == key }

    private fun MealsViewModel.shown(): MealsViewModel = tracked(this).also { vm -> collectors.launch { vm.ui.collect {} } }

    private fun messages(events: kotlinx.coroutines.flow.Flow<UiMessage>): MutableList<UiMessage> {
        val got = CopyOnWriteArrayList<UiMessage>()
        collectors.launch { events.collect { got += it } }
        return got
    }

    /** A saved recipe in "Your recipes", not on the list and not planned. */
    private fun save(
        c: AppContainer,
        title: String,
        vararg lines: String,
    ) = runBlocking {
        val parsed = lines.map { c.ruleParser.parse(it) }
        val stored =
            c.repository.storeRecipe(
                "https://example.com/${title.lowercase()}",
                title,
                4,
                UnitLocale.METRIC,
                "WEB",
                ParsedBy.RULES,
                null,
                false,
                parsed,
            )
        c.repository.saveToLibrary(stored.recipe.id, null)
    }

    private fun covered(
        c: AppContainer,
        key: String,
        base: BaseKey,
    ): Rational? = runBlocking { c.repository.snapshot() }.let { l -> l.itemByKey(key)?.let { l.covered(it.id)[base] } }

    private fun nextReview(vm: RecipesViewModel): Long {
        var id: Long? = null
        val job = CoroutineScope(Dispatchers.Main).launch { id = vm.openReview.first() }
        val end = System.currentTimeMillis() + 5000
        while (id == null && System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        job.cancel()
        return id ?: throw AssertionError("no review opened")
    }

    @Test
    fun scenarioD_aWeekPlannedShoppedInPartAndAMealRemovedKeepsWhatWasBought() {
        val c = TestContainer.create()
        save(c, "Chilli", "2 onions", "500g beef mince")
        save(c, "Soup", "1 onion", "2 carrots")
        val meals = MealsViewModel(c).shown()
        val said = messages(meals.events)
        await(meals.ui) { it.picks.size == 2 }.picks.forEach { meals.plan(it) }
        await(meals.ui) { it.meals.size == 2 && it.preview != null }
        meals.addToList()
        await(meals.ui) { it.meals.all { m -> m.shopping == MealShopping.TO_BUY } }

        // In the shop: the onions (both meals' share) and the mince are bought, the carrots not yet.
        val list = tracked(ListViewModel(c))
        var ui = await(list.ui) { it.loaded && it.row("onion").main == "3 onions" }
        list.setMode(ListMode.SHOP)
        ui = await(list.ui) { it.shopping }
        list.toggle(ui.row("onion"))
        list.toggle(ui.row("beef mince"))
        ui = await(list.ui) { it.doneCount == 2 && it.left == 1 }

        // Chilli comes off the plan mid-shop: nothing bought is undone, only its unbought share would go (there is none).
        meals.remove(await(meals.ui) { it.meals.isNotEmpty() }.meals.first { it.title == "Chilli" })
        await(meals.ui) { it.meals.size == 1 }
        ui = await(list.ui) { it.loaded && it.doneCount == 2 }
        assertEquals(TickState.DONE, ui.row("onion").state)
        assertEquals(TickState.DONE, ui.row("beef mince").state)
        assertEquals(Rational.of(3), covered(c, "cat:onion", BaseKey.ITEMS))
        assertEquals(1, ui.left)
        // Undo puts the meal back, and the list is as it was.
        awaitTrue { said.any { it.text == "Removed Chilli" } }
        runBlocking { said.first { it.text == "Removed Chilli" }.undo!!.invoke() }
        await(meals.ui) { it.meals.size == 2 }
        ui = await(list.ui) { it.doneCount == 2 && it.left == 1 }
        assertEquals(Rational.of(3), covered(c, "cat:onion", BaseKey.ITEMS))

        // Finish: the bought rows go, the carrots stay (the shop goes on for them), and the plan is left alone.
        list.finishShopping()
        ui = await(list.ui) { it.doneCount == 0 && it.aisles.isNotEmpty() }
        assertEquals(listOf("carrot"), ui.aisles.flatMap { it.second }.map { it.name })
        assertEquals(2, await(meals.ui) { it.meals.size == 2 }.meals.size)
    }

    @Test
    fun scenarioE_correctionsOutliveAskClaudeAndAnEditAfterAPurchaseKeepsIt() {
        val claude = MockWebServer().apply { start() }
        try {
            claude.enqueue(MockResponse().setBody(File("../../testdata/claude/synthetic-ok-cake.json").readText()))
            val c = TestContainer.create(claude = claude, key = "sk-ant-test")
            // Read by the rules; the amount corrected by hand; then Claude asked, whose answer is 200 g.
            val review = ReviewViewModel(c, runBlocking { c.readyJob("200g golden caster sugar", title = "Cake") })
            var ui = (await(review.state) { it is ReviewState.Ready } as ReviewState.Ready).ui
            val key = ui.aisles.flatMap { it.second }.single().key
            assertTrue(review.setAmount(review.sheet(key)!!.lines.single().id, "300 g"))
            await(review.state) { it is ReviewState.Ready && it.ui.aisles.flatMap { r -> r.second }.single().quantity == "300g" }
            review.retryWithClaude()
            ui = (await(review.state) { it is ReviewState.Ready && it.ui.badge == Badge.CLAUDE } as ReviewState.Ready).ui
            assertEquals(1, claude.requestCount)
            assertEquals("300g", ui.aisles.flatMap { it.second }.single().quantity)
            review.confirm()
            await(review.state) { it is ReviewState.Added }

            // Bought in the shop.
            val list = tracked(ListViewModel(c))
            var lui = await(list.ui) { it.loaded && it.aisles.isNotEmpty() }
            list.toggle(lui.aisles.flatMap { it.second }.single())
            lui = await(list.ui) { it.doneCount == 1 }
            val sugarKey = runBlocking { c.repository.snapshot() }.liveItems.single().mergeKey
            assertEquals(Rational.of(300), covered(c, sugarKey, BaseKey.MASS))

            // The recipe is edited afterwards to need 400 g: what was bought stays bought, 100 g is still needed.
            val recipes = tracked(RecipesViewModel(c))
            collectors.launch { recipes.ui.collect {} }
            val use = await(recipes.ui) { it.uses.isNotEmpty() }.uses.single()
            recipes.edit(use)
            val edit = ReviewViewModel(c, nextReview(recipes))
            val eui = (await(edit.state) { it is ReviewState.Ready } as ReviewState.Ready).ui
            assertTrue(eui.editing)
            val line = edit.sheet(eui.aisles.flatMap { it.second }.single().key)!!.lines.single()
            assertTrue(edit.setAmount(line.id, "400 g"))
            await(edit.state) { it is ReviewState.Ready && it.ui.aisles.flatMap { r -> r.second }.single().quantity == "400g" }
            edit.confirm()
            await(edit.state) { it is ReviewState.Added }
            lui = await(list.ui) { u -> u.aisles.flatMap { it.second }.any { it.state == TickState.PARTIAL } }
            assertEquals(Rational.of(300), covered(c, sugarKey, BaseKey.MASS))
            assertEquals(1, lui.left)
        } finally {
            claude.shutdown()
        }
    }

    @Test
    fun scenarioF_aPageThatIsNotARecipeIsRecoveredByPastingAndTheListIsUntouchedMeanwhile() {
        val c = TestContainer.create()
        c.add("2 onions")
        val list = tracked(ListViewModel(c))
        await(list.ui) { it.loaded && it.aisles.isNotEmpty() }
        // The fetch found a page but no recipe: the review fails with the page's lines to pick from.
        val id =
            runBlocking {
                val job =
                    c.db.dao().insertJob(
                        com.robert4lyf.hypershop.data.ImportJobEntity(
                            0,
                            com.robert4lyf.hypershop.importing.JobState.RUNNING.name,
                            """{"url":"https://example.com/blog"}""",
                            null,
                            null,
                            c.clock(),
                        ),
                    )
                c.importJobs.finish(
                    job,
                    ImportResult(
                        url = "https://example.com/blog",
                        title = "Gran's scones",
                        servings = null,
                        locale = UnitLocale.METRIC,
                        method = "PAGE_TEXT",
                        pageText = "Gran's scones\n\n225g self-raising flour\n75g butter\n1 onion\n",
                        error = ImportError.NO_RECIPE,
                    ),
                )
                job
            }
        val review = ReviewViewModel(c, id)
        val failed = await(review.state) { it is ReviewState.Failed } as ReviewState.Failed
        assertEquals(ImportError.NO_RECIPE, failed.error)
        // Nothing reached the list from a failed import.
        assertEquals(1, runBlocking { c.repository.snapshot() }.liveItems.size)
        // The lines pasted in: reviewed like any recipe, merged with what is there.
        review.useLines(listOf("225g self-raising flour", "75g butter", "1 onion"))
        val ui = (await(review.state) { it is ReviewState.Ready } as ReviewState.Ready).ui
        assertEquals("Gran's scones", ui.title)
        assertEquals(3, ui.addCount)
        review.confirm()
        await(review.state) { it is ReviewState.Added }
        val after = await(list.ui) { it.row("onion").main == "3 onions" }
        assertEquals(3, after.left)
        assertNull(runBlocking { c.importJobs.ready.first() }.firstOrNull())
    }
}
