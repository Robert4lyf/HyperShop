package com.robert4lyf.hypershop.ui.review

import android.os.Looper
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.ParsedIngredient
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.RuleParser
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.await
import com.robert4lyf.hypershop.testutil.awaitTrue
import com.robert4lyf.hypershop.testutil.readyJob
import com.robert4lyf.hypershop.ui.common.UiMessage
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
import kotlinx.serialization.json.JsonObject
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
import java.util.concurrent.TimeUnit

/**
 * S.4: what Claude is sent for a recipe already read is one line per line of the recipe, decided
 * by where each line came from ([ParsedIngredient.sourceLine]), never by its text. Two lines that
 * read the same ("100 g sugar" twice, side by side) are both sent and stay two; a line Claude read
 * as several ("salt and pepper") is sent once. Through the real Recipes and review view models,
 * the job store and the repository, out to the shopping list.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SourceLinesReviewTest {
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

    /** One line of Claude's answer. */
    private data class Said(
        val raw: String,
        val name: String,
        val grams: Int? = null,
        val section: String? = null,
    )

    private val sugar = Said("100 g sugar", "sugar", 100)
    private val flour = Said("200 g plain flour", "plain flour", 200)

    private fun answer(vararg lines: Said) {
        val inner =
            buildJsonObject {
                put("status", "ok")
                put("title", JsonNull)
                put("servings", JsonNull)
                putJsonArray("ingredients") {
                    for (l in lines) {
                        addJsonObject {
                            put("raw", l.raw)
                            put("name", l.name)
                            put("quantity", l.grams?.let { JsonPrimitive(it) } ?: JsonNull)
                            put("quantity_max", JsonNull)
                            put("quantity_text", l.grams?.let { JsonPrimitive("$it g") } ?: JsonNull)
                            put("unit", if (l.grams != null) JsonPrimitive("g") else JsonNull)
                            put("alt_quantity", JsonNull)
                            put("alt_unit", JsonNull)
                            put("note", JsonNull)
                            put("section", l.section?.let { JsonPrimitive(it) } ?: JsonNull)
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
                        put("text", JsonPrimitive(Json.encodeToString(JsonObject.serializer(), inner)))
                    }
                }
            }
        claude.enqueue(MockResponse().setBody(body.toString()))
    }

    private val sent = CopyOnWriteArrayList<String>()

    /** The lines the last call to Claude was asked to read. */
    private fun lastAsked(): String {
        while (true) sent += claude.takeRequest(100, TimeUnit.MILLISECONDS)?.body?.readUtf8() ?: break
        // The recipe's lines only: the instructions around them have their own examples.
        return sent.last().substringAfterLast("<untrusted_content>").substringBefore("</untrusted_content>")
    }

    private fun String.times(line: String) = Regex(Regex.escape(line)).findAll(this).count()

    private fun ready(vm: ReviewViewModel) = (await(vm.state) { it is ReviewState.Ready && !it.ui.busy } as ReviewState.Ready).ui

    private fun open(job: Long) = tracked(ReviewViewModel(c, job)).also { ready(it) }

    private fun askClaude(vm: ReviewViewModel) {
        val asked = claude.requestCount
        vm.retryWithClaude()
        awaitTrue { claude.requestCount == asked + 1 }
        await(vm.state) { it is ReviewState.Ready && !it.ui.busy && it.ui.badge == Badge.CLAUDE }
    }

    private fun rows(vm: ReviewViewModel) = ready(vm).aisles.flatMap { it.second }

    private fun sugarRow(vm: ReviewViewModel) = rows(vm).first { it.name.contains("sugar") }

    private fun sugarLines(vm: ReviewViewModel) = vm.sheet(sugarRow(vm).key)!!.lines

    private fun settle(
        vm: ReviewViewModel,
        quantity: String,
    ) = await(vm.state) { s -> s is ReviewState.Ready && !s.ui.busy && s.ui.aisles.flatMap { it.second }.any { it.quantity == quantity } }

    private fun recipes() = tracked(RecipesViewModel(c)).also { r -> collectors.launch { r.ui.collect {} } }

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

    /** Imported from [raw] (read by Claude as [claudeSays], when given), added to the list and so saved. */
    private fun added(
        vararg raw: String,
        claudeSays: List<Said>? = null,
    ) {
        val vm = open(runBlocking { c.readyJob(*raw, title = "Cake") })
        if (claudeSays != null) {
            answer(*claudeSays.toTypedArray())
            askClaude(vm)
        }
        vm.confirm()
        await(vm.state) { it is ReviewState.Added }
    }

    /** "Edit" on the recipe's card on the list: its lines read back from the database, the review opened on them. */
    private fun editOnTheList(): ReviewViewModel {
        val r = recipes()
        r.edit(await(r.ui) { it.uses.isNotEmpty() }.uses.single())
        return tracked(ReviewViewModel(c, nextReview(r))).also { ready(it) }
    }

    /** "Edit" on the saved recipe in Saved. */
    private fun editSaved(): ReviewViewModel {
        val r = recipes()
        r.editSaved(await(r.ui) { it.library.isNotEmpty() }.library.single())
        return tracked(ReviewViewModel(c, nextReview(r))).also { ready(it) }
    }

    private fun listSugar(): Rational? {
        val l = runBlocking { c.repository.snapshot() }
        return l.total(l.liveItems.single { it.displayName.contains("sugar") }.id)[BaseKey.MASS]
    }

    @Test
    fun twoAdjacentIdenticalLinesBothReachClaudeAndBothAreBought() {
        added("100 g sugar", "100 g sugar", "200 g plain flour")
        assertEquals(Rational.of(200), listSugar())
        val vm = editOnTheList()
        answer(sugar, sugar, flour)
        askClaude(vm)
        assertEquals("both lines are read, not one", 2, lastAsked().times("100 g sugar"))
        assertEquals("200g", sugarRow(vm).quantity)
        vm.confirm()
        await(vm.state) { it is ReviewState.Added }
        assertEquals(Rational.of(200), listSugar())
    }

    @Test
    fun aSplitLineReachesClaudeOnceAndTwoIdenticalSplitLinesOnceEach() {
        val pepper = "salt and pepper"
        // Side by side, and each read by Claude as two ingredients.
        added(
            pepper,
            pepper,
            "200 g plain flour",
            claudeSays =
                listOf(
                    Said(pepper, "salt"),
                    Said(pepper, "black pepper"),
                    Said(pepper, "salt"),
                    Said(pepper, "black pepper"),
                    flour,
                ),
        )
        val row = await(recipes().ui) { it.library.isNotEmpty() }.library.single()
        val stored = runBlocking { c.repository.librarySource(row.recipeId) }!!.second
        assertEquals("each part knows its line", listOf(0, 0, 1, 1, 2), stored.map { it.sourceLine })
        val vm = editSaved()
        answer(Said(pepper, "salt"), Said(pepper, "black pepper"), Said(pepper, "salt"), Said(pepper, "black pepper"), flour)
        askClaude(vm)
        assertEquals("one per line of the recipe, not per part", 2, lastAsked().times(pepper))
    }

    @Test
    fun sectionsGoToClaudeWithTheirLines() {
        added(
            "For the cake",
            "100 g sugar",
            "For the icing",
            "100 g sugar",
            claudeSays = listOf(sugar.copy(section = "For the cake"), sugar.copy(section = "For the icing")),
        )
        val vm = editSaved()
        answer(sugar.copy(section = "For the cake"), sugar.copy(section = "For the icing"))
        askClaude(vm)
        val asked = lastAsked()
        assertEquals(2, asked.times("100 g sugar"))
        assertTrue(asked, asked.contains("For the icing:"))
        assertEquals("200g", sugarRow(vm).quantity)
    }

    @Test
    fun editingOneOfTwoAdjacentLinesLeavesTheOtherAndTheListAddsUp() {
        added("100 g sugar", "100 g sugar", "200 g plain flour")
        val vm = editOnTheList()
        val (first, second) = sugarLines(vm)
        assertTrue(vm.setAmount(second.id, "150 g"))
        settle(vm, "250g")
        answer(sugar, sugar, flour)
        askClaude(vm)
        assertEquals("250g", sugarRow(vm).quantity)
        assertEquals(listOf(100, 150), sugarLines(vm).map { it.amount.orEmpty().filter(Char::isDigit).toInt() }.sorted())
        assertTrue(first.id != second.id)
        vm.confirm()
        await(vm.state) { it is ReviewState.Added }
        assertEquals(Rational.of(250), listSugar())
    }

    @Test
    fun removingOneOfTwoAdjacentLinesStaysRemovedWhateverClaudeSays() {
        added("100 g sugar", "100 g sugar", "200 g plain flour")
        val vm = editOnTheList()
        vm.removeLine(sugarLines(vm)[0].id)
        settle(vm, "100g")
        // Claude reads both, as the recipe has them: the removal stands over its answer.
        answer(sugar, sugar, flour)
        askClaude(vm)
        assertEquals("100g", sugarRow(vm).quantity)
        vm.confirm()
        await(vm.state) { it is ReviewState.Added }
        assertEquals(Rational.of(100), listSugar())
    }

    @Test
    fun undoPutsBackTheAdjacentOccurrenceThatWasRemoved() {
        added("100 g sugar", "100 g sugar", "200 g plain flour")
        val vm = editOnTheList()
        val said = CopyOnWriteArrayList<UiMessage>()
        collectors.launch { vm.events.collect { said += it } }
        assertTrue(vm.setAmount(sugarLines(vm)[1].id, "150 g"))
        settle(vm, "250g")
        vm.removeLine(sugarLines(vm).single { it.amount.orEmpty().contains("150") }.id)
        settle(vm, "100g")
        awaitTrue { said.any { it.undo != null && it.text.startsWith("Removed") } }
        runBlocking { said.first { it.text.startsWith("Removed") }.undo!!.invoke() }
        settle(vm, "250g")
        answer(sugar, sugar, flour)
        askClaude(vm)
        assertEquals("the edit came back with the line it was on", "250g", sugarRow(vm).quantity)
    }

    @Test
    fun aRecipeStoredBeforeLinesKnewTheirSourceIsStillReadRight() {
        // Lines as an older version stored them: no source line on any. A split's parts name different
        // ingredients from one text; two lines that only read alike name the same one.
        val rules = RuleParser()
        val legacy: List<ParsedIngredient> =
            listOf(
                rules.parse("salt and pepper").copy(name = "salt"),
                rules.parse("salt and pepper").copy(name = "black pepper"),
                rules.parse("100 g sugar"),
                rules.parse("100 g sugar"),
            )
        assertTrue(legacy.all { it.sourceLine == null })
        runBlocking {
            val s = c.repository.storeRecipe(null, "Cake", 4, UnitLocale.METRIC, "WEB", ParsedBy.CLAUDE, null, false, legacy)
            c.repository.saveToLibrary(s.recipe.id, null)
        }
        val vm = editSaved()
        answer(Said("salt and pepper", "salt"), Said("salt and pepper", "black pepper"), sugar, sugar)
        askClaude(vm)
        val asked = lastAsked()
        assertEquals(1, asked.times("salt and pepper"))
        assertEquals(2, asked.times("100 g sugar"))
        assertEquals("200g", sugarRow(vm).quantity)
    }
}
