package com.robert4lyf.hypershop.ui.review

import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.importing.ImportResult
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.await
import com.robert4lyf.hypershop.testutil.awaitTrue
import com.robert4lyf.hypershop.testutil.readyJob
import com.robert4lyf.hypershop.ui.common.UiMessage
import com.robert4lyf.hypershop.ui.list.ListViewModel
import com.robert4lyf.hypershop.ui.recipes.RecipesViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.addJsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * A recipe that says "100 g sugar" twice, once for the cake and once for the icing:
 * each occurrence is edited, removed, restored and read again by Claude on its own,
 * through the real review view model, the job store and the repository.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DuplicateLinesReviewTest {
    private val recipe = arrayOf("For the cake", "100 g sugar", "200 g plain flour", "For the icing", "100 g sugar")
    private lateinit var claude: MockWebServer
    private lateinit var c: AppContainer
    private val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val models = CopyOnWriteArrayList<ViewModel>()

    @Before
    fun start() {
        claude = MockWebServer().apply { start() }
        c = TestContainer.create(claude = claude, key = "sk-ant-test")
    }

    @After
    fun stop() {
        collectors.cancel()
        for (vm in models) vm.viewModelScope.cancel()
        Thread.sleep(200)
        claude.shutdown()
    }

    private fun <T : ViewModel> tracked(vm: T): T = vm.also { models += it }

    /** Claude's answer: each line as (raw, grams, section), named as the rules name it so the rows stay comparable. */
    private fun answer(vararg lines: Triple<String, Int, String>) {
        val inner =
            buildJsonObject {
                put("status", "ok")
                put("title", JsonNull)
                put("servings", JsonNull)
                putJsonArray("ingredients") {
                    for ((raw, g, section) in lines) {
                        addJsonObject {
                            put("raw", raw)
                            put("name", raw.substringAfter("g "))
                            put("quantity", g)
                            put("quantity_max", JsonNull)
                            put("quantity_text", "$g g")
                            put("unit", "g")
                            put("alt_quantity", JsonNull)
                            put("alt_unit", JsonNull)
                            put("note", JsonNull)
                            put("section", section)
                            put("optional", false)
                            put("estimated", false)
                            put("aisle_suggestion", "BAKING")
                        }
                    }
                }
                putJsonArray("source_urls") {}
            }
        val body =
            buildJsonObject {
                put("model", "claude-haiku-5-5")
                put("stop_reason", "end_turn")
                putJsonObject("usage") {
                    put("input_tokens", 900)
                    put("output_tokens", 300)
                }
                putJsonArray("content") {
                    addJsonObject {
                        put("type", "text")
                        put("text", JsonPrimitive(Json.encodeToString(kotlinx.serialization.json.JsonObject.serializer(), inner)))
                    }
                }
            }
        claude.enqueue(MockResponse().setBody(body.toString()))
    }

    private val cake = Triple("100 g sugar", 100, "For the cake")
    private val flour = Triple("200 g plain flour", 200, "For the cake")
    private val icing = Triple("100 g sugar", 100, "For the icing")

    private fun ready(vm: ReviewViewModel) = (await(vm.state) { it is ReviewState.Ready && !it.ui.busy } as ReviewState.Ready).ui

    private fun open(job: Long) = tracked(ReviewViewModel(c, job)).also { ready(it) }

    private fun sugarKey(vm: ReviewViewModel) = ready(vm).aisles.flatMap { it.second }.first { it.name.contains("sugar") }.key

    private fun sugarLines(vm: ReviewViewModel) = vm.sheet(sugarKey(vm))!!.lines

    /** The lines as kept with the job: what reopening, Add and Ask Claude all start from. */
    private fun kept(job: Long): ImportResult = runBlocking { c.importJobs.get(job)!!.result!! }

    private fun sugars(job: Long) = kept(job).lines.filter { it.name.contains("sugar") }.map { "${it.section}:${it.quantity}" }

    private fun sugarRow(vm: ReviewViewModel) = ready(vm).aisles.flatMap { it.second }.first { it.name.contains("sugar") }.quantity

    private fun settle(
        vm: ReviewViewModel,
        quantity: String,
    ) = await(vm.state) { s -> s is ReviewState.Ready && !s.ui.busy && s.ui.aisles.flatMap { it.second }.any { it.quantity == quantity } }

    private fun askClaude(vm: ReviewViewModel) {
        val asked = claude.requestCount
        vm.retryWithClaude()
        awaitTrue { claude.requestCount == asked + 1 }
        await(vm.state) { it is ReviewState.Ready && !it.ui.busy && it.ui.badge == Badge.CLAUDE }
    }

    private fun job() = runBlocking { c.readyJob(*recipe, title = "Cake") }

    private fun messages(vm: ReviewViewModel): MutableList<UiMessage> {
        val got = CopyOnWriteArrayList<UiMessage>()
        collectors.launch { vm.events.collect { got += it } }
        return got
    }

    @Test
    fun editingTheFirstOccurrenceSurvivesAskClaudeAndAddsTheRightTotal() {
        val job = job()
        val vm = open(job)
        val (first, second) = sugarLines(vm)
        assertTrue(vm.setAmount(first.id, "150 g"))
        settle(vm, "250g")
        assertEquals(listOf("For the cake:150", "For the icing:100"), sugars(job))
        answer(cake, flour, icing)
        askClaude(vm)
        assertEquals(listOf("For the cake:150", "For the icing:100"), sugars(job))
        assertEquals("250g", sugarRow(vm))
        assertTrue(second.id != first.id)
        vm.confirm()
        await(vm.state) { it is ReviewState.Added }
        val l = runBlocking { c.repository.snapshot() }
        val sugar = l.liveItems.single { it.displayName.contains("sugar") }
        assertEquals(Rational.of(250), l.total(sugar.id)[BaseKey.MASS])
    }

    @Test
    fun editingTheSecondOccurrenceLeavesTheFirst() {
        val job = job()
        val vm = open(job)
        assertTrue(vm.setAmount(sugarLines(vm)[1].id, "80 g"))
        settle(vm, "180g")
        answer(cake, flour, icing)
        askClaude(vm)
        assertEquals(listOf("For the cake:100", "For the icing:80"), sugars(job))
        assertEquals("180g", sugarRow(vm))
    }

    @Test
    fun bothOccurrencesEditedDifferently() {
        val job = job()
        val vm = open(job)
        val (first, _) = sugarLines(vm)
        assertTrue(vm.setAmount(first.id, "150 g"))
        settle(vm, "250g")
        // Stored again: the second occurrence's line id is new, so it is looked up again.
        assertTrue(vm.setAmount(sugarLines(vm)[1].id, "120 g"))
        settle(vm, "270g")
        answer(cake, flour, icing)
        askClaude(vm)
        assertEquals(listOf("For the cake:150", "For the icing:120"), sugars(job))
    }

    @Test
    fun removingTheFirstOccurrenceKeepsTheSecondThroughAskClaude() {
        val job = job()
        val vm = open(job)
        vm.removeLine(sugarLines(vm)[0].id)
        settle(vm, "100g")
        assertEquals(listOf("For the icing:100"), sugars(job))
        answer(cake, flour, icing)
        askClaude(vm)
        assertEquals(listOf("For the icing:100"), sugars(job))
        assertEquals(2, kept(job).lines.size)
    }

    @Test
    fun removingTheSecondOccurrenceKeepsTheFirstThroughAskClaude() {
        val job = job()
        val vm = open(job)
        vm.removeLine(sugarLines(vm)[1].id)
        settle(vm, "100g")
        answer(cake, flour, icing)
        askClaude(vm)
        assertEquals(listOf("For the cake:100"), sugars(job))
    }

    @Test
    fun undoPutsBackTheOccurrenceThatWasRemovedWhereItWas() {
        val job = job()
        val vm = open(job)
        val said = messages(vm)
        assertTrue(vm.setAmount(sugarLines(vm)[0].id, "150 g"))
        settle(vm, "250g")
        vm.removeLine(sugarLines(vm)[0].id)
        settle(vm, "100g")
        awaitTrue { said.any { it.undo != null && it.text.startsWith("Removed") } }
        runBlocking { said.first { it.text.startsWith("Removed") }.undo!!.invoke() }
        settle(vm, "250g")
        // Back first, in the cake, as edited: the correction it carried comes back with it.
        assertEquals(listOf("For the cake:150", "For the icing:100"), sugars(job))
        assertEquals("100 g sugar", kept(job).lines.first().raw)
        answer(cake, flour, icing)
        askClaude(vm)
        assertEquals(listOf("For the cake:150", "For the icing:100"), sugars(job))
    }

    @Test
    fun claudeListingTheIcingFirstStillLeavesTheCakesEditOnTheCake() {
        val job = job()
        val vm = open(job)
        assertTrue(vm.setAmount(sugarLines(vm)[0].id, "150 g"))
        settle(vm, "250g")
        answer(icing, cake, flour)
        askClaude(vm)
        assertEquals(listOf("For the icing:100", "For the cake:150"), sugars(job))
    }

    @Test
    fun correctionsSurviveLeavingAndReopeningTheReview() {
        val job = job()
        val vm = open(job)
        assertTrue(vm.setAmount(sugarLines(vm)[0].id, "150 g"))
        settle(vm, "250g")
        vm.removeLine(vm.sheet(flourKey(vm))!!.lines.single().id)
        await(vm.state) { s -> s is ReviewState.Ready && s.ui.aisles.flatMap { it.second }.none { it.name.contains("flour") } }
        // Back, and opened again from the card (a new view model, as after process death): Claude asked from there.
        val again = open(job)
        assertEquals("250g", sugarRow(again))
        answer(cake, flour, icing)
        askClaude(again)
        assertEquals(listOf("For the cake:150", "For the icing:100"), sugars(job))
        assertTrue(kept(job).lines.none { it.raw.contains("flour") })
    }

    private fun flourKey(vm: ReviewViewModel) = ready(vm).aisles.flatMap { it.second }.first { it.name.contains("flour") }.key

    @Test
    fun cancelledAndRestoredTheCorrectionsAreStillThere() {
        val job = job()
        val vm = open(job)
        assertTrue(vm.setAmount(sugarLines(vm)[1].id, "80 g"))
        settle(vm, "180g")
        vm.cancel()
        await(vm.state) { it == ReviewState.Cancelled }
        // Undo on "Import discarded".
        runBlocking { c.importJobs.restore(job) }
        val back = open(job)
        assertEquals("180g", sugarRow(back))
        answer(cake, flour, icing)
        askClaude(back)
        assertEquals(listOf("For the cake:100", "For the icing:80"), sugars(job))
    }

    @Test
    fun editingTheRecipeAfterBuyingOneOccurrenceKeepsThePurchase() {
        val job = job()
        val vm = open(job)
        vm.confirm()
        await(vm.state) { it is ReviewState.Added }
        // Bought in the shop: 200 g of sugar.
        val list = tracked(ListViewModel(c))
        collectors.launch { list.ui.collect {} }
        val row = await(list.ui) { it.loaded && it.aisles.isNotEmpty() }.aisles.flatMap { it.second }.first { it.name.contains("sugar") }
        list.toggle(row)
        await(list.ui) { it.doneCount == 1 }
        // Then the icing's sugar is edited to 150 g: the 200 g bought stays bought and 50 g more is needed.
        val recipes = tracked(RecipesViewModel(c))
        collectors.launch { recipes.ui.collect {} }
        recipes.edit(await(recipes.ui) { it.uses.isNotEmpty() }.uses.single())
        val edit = tracked(ReviewViewModel(c, nextReview(recipes)))
        ready(edit)
        val lines = sugarLines(edit)
        assertEquals(2, lines.size)
        assertTrue(edit.setAmount(lines[1].id, "150 g"))
        settle(edit, "250g")
        edit.confirm()
        await(edit.state) { it is ReviewState.Added }
        val l = runBlocking { c.repository.snapshot() }
        val sugar = l.liveItems.single { it.displayName.contains("sugar") }
        assertEquals(Rational.of(250), l.total(sugar.id)[BaseKey.MASS])
        assertEquals(Rational.of(200), l.covered(sugar.id)[BaseKey.MASS])
    }

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
}
