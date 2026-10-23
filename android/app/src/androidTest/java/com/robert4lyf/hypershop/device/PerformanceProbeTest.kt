package com.robert4lyf.hypershop.device

import android.os.Debug
import android.os.StrictMode
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToIndex
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.search.RecipeSearch
import com.robert4lyf.hypershop.core.sync.WatchCodec
import com.robert4lyf.hypershop.core.sync.WatchMessage
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.settings.ListMode
import com.robert4lyf.hypershop.ui.HyperShopApp
import com.robert4lyf.hypershop.ui.list.ListViewModel
import com.robert4lyf.hypershop.ui.recipes.RecipesViewModel
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import com.robert4lyf.hypershop.watch.FakeWatchLink
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Release A's performance baseline: how long the things a shopper waits for
 * take, on whatever device runs it, at 20, 60 and 150 items. Run on request
 * (`-e perf true`), from tools/device_perf.sh, which prints the lines tagged
 * HyperShopPerf. The budgets asserted are several times the first emulator
 * baseline (docs/reports/performance.md): they catch a regression of that
 * order, not noise.
 */
@RunWith(AndroidJUnit4::class)
class PerformanceProbeTest {
    @get:Rule val compose = createComposeRule()

    private val enabled = InstrumentationRegistry.getArguments().getString("perf") == "true"

    private fun report(
        what: String,
        value: Double,
        unit: String = "ms",
    ) = Log.i(TAG, "%-50s %9.1f %s".format(what, value, unit))

    /**
     * A budget: about three times the first CI emulator baseline (API 34, software
     * rendering, docs/reports/performance.md), so it fails on a regression of that
     * order and not on a slow runner.
     */
    private fun budget(
        what: String,
        ms: Double,
        max: Double,
    ) {
        report(what, ms)
        assertTrue("$what took ${"%.0f".format(ms)} ms; the budget is ${"%.0f".format(max)} ms", ms <= max)
    }

    private inline fun <T> timed(block: () -> T): Pair<T, Double> {
        val t0 = System.nanoTime()
        val r = block()
        return r to (System.nanoTime() - t0) / 1e6
    }

    private val names =
        listOf(
            "milk", "bread", "eggs", "butter", "cheddar", "yoghurt", "bananas", "apples", "lemons", "onions",
            "carrots", "potatoes", "garlic", "ginger", "rice", "pasta", "flour", "sugar", "coffee", "tea",
            "chicken thighs", "beef mince", "salmon", "tofu", "spinach", "rocket", "tomatoes", "peppers", "courgettes", "mushrooms",
        )

    /** [n] distinct items: catalogue names first, then numbered extras ("item 31") as a long list would have. */
    private fun AppContainer.fill(n: Int) =
        runBlocking {
            for (i in 0 until n) {
                val name = names.getOrNull(i) ?: "item ${i + 1}"
                repository.manualAdd(name, QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), null)
            }
        }

    @Test
    fun listLoadTickAndWatchAtThreeSizes() {
        assumeTrue("run on request only", enabled)
        for (n in listOf(20, 60, 150)) {
            val c = DeviceContainer.create()
            c.fill(n)
            val vm = ListViewModel(c)
            val (_, load) =
                timed {
                    runBlocking {
                        withTimeout(30_000) {
                            vm.ui.first {
                                it.loaded && it.aisles.sumOf {
                                        a ->
                                    a.second.size
                                } == n
                            }
                        }
                    }
                }
            budget("list: first full state, $n items", load, 250.0)
            // A tick, from the call to the state that shows it: the median of 15.
            val rows = vm.ui.value.aisles.flatMap { it.second }.take(15)
            val each =
                rows.map { row ->
                    timed {
                        vm.toggle(row)
                        runBlocking {
                            withTimeout(10_000) {
                                vm.ui.first {
                                        u ->
                                    u.aisles.flatMap { it.second }.first { it.id == row.id }.state.name == "DONE"
                                }
                            }
                        }
                    }.second
                }.sorted()
            budget("tick to state, $n items (median of 15)", each[each.size / 2], 200.0)
            report("tick to state, $n items (worst of 15)", each.last())
            val (_, watch) =
                timed {
                    runBlocking {
                        c.watchSync.handle(WatchCodec.encode(WatchMessage.Hello(c.repository.listEpoch().toInt(), 7, 0, 1_760_000_000, 0)))
                    }
                }
            budget("watch: hello to snapshot sent, $n items", watch, 300.0)
            report("watch: pages for $n items", (c.watchLink as FakeWatchLink).sent.size.toDouble(), "pages")
        }
    }

    @Test
    fun savedRecipesAndLibrarySearch() {
        assumeTrue("run on request only", enabled)
        val c = DeviceContainer.create()
        val lines =
            listOf(
                "2 onions", "500g beef mince", "1 tin chopped tomatoes", "2 garlic cloves", "1 tsp cumin", "1 red pepper", "200g rice",
                "1 lime", "coriander", "soured cream", "cheddar", "tortillas", "1 avocado", "1 tsp paprika", "salt",
            )
        val ids = (1..300).map { c.saveRecipe("Recipe $it", *lines.toTypedArray()) }
        val (_, add) = timed { runBlocking { c.repository.addSavedRecipe(ids.first()) } }
        budget("a 15-line saved recipe onto the list", add, 150.0)
        val vm = RecipesViewModel(c)
        runBlocking { withTimeout(30_000) { vm.ui.first { it.library.size == 300 } } }

        // The search box is Compose state; with no frame running here, apply it as a frame would.
        fun search(text: String) =
            timed {
                vm.onSearchText(text)
                Snapshot.sendApplyNotifications()
                runBlocking { withTimeout(30_000) { vm.ui.first { it.terms == listOf(text) } } }
            }.second
        // The first search builds the index (every line of every recipe); later ones reuse it.
        budget("search 300 saved recipes: first search (builds the index)", search("avocado"), 4000.0)
        budget("search 300 saved recipes: a second search", search("cumin"), 250.0)
        // Where the time goes (KNOWN_ISSUES #16): what the first search's index is built from, and the match alone.
        val (lines300, read) = timed { runBlocking { c.repository.ingredientsOf(ids) } }
        report("search: read and decode 300 recipes' lines", read)
        val (_, keyed) = timed { lines300.values.flatten().forEach { c.repository.catalogEntry(it.name) } }
        report("search: catalogue lookup for each line", keyed)
        val index =
            lines300.map { (id, ls) ->
                RecipeSearch.Recipe(id, "Recipe $id", ls.map { RecipeSearch.Ingredient(it.name, listOf(it.name)) })
            }
        report("search: the match alone, first (words made)", timed { RecipeSearch.search(index, listOf("avocado")) }.second)
        report("search: the match alone, again", timed { RecipeSearch.search(index, listOf("cumin")) }.second)
    }

    /**
     * Release B at scale: 300 saved recipes, twelve weeks of plans, the full 26
     * trips of 60 items each. What a shopper waits for: the week's preview, the
     * week onto the list, another week, Buy again and the At home check.
     */
    @Test
    fun planningAndHistoryAtScale() {
        assumeTrue("run on request only", enabled)
        val c = DeviceContainer.create()
        val lines =
            listOf(
                "2 onions", "500g beef mince", "1 tin chopped tomatoes", "2 garlic cloves", "1 tsp cumin", "1 red pepper", "200g rice",
                "1 lime", "coriander", "soured cream", "cheddar", "tortillas", "1 avocado", "1 tsp paprika", "salt",
            )
        val keys =
            (1..300).map { i ->
                val id = c.saveRecipe("Recipe $i", *lines.map { l -> if (i % 3 == 0) "$l $i" else l }.toTypedArray())
                runBlocking { c.db.dao().recipe(id)!!.libraryKey!! }
            }
        val week = c.planner.currentWeek()
        runBlocking {
            for (w in 1..12) for (k in keys.shuffled(java.util.Random(w.toLong())).take(5)) c.planner.plan(week - w, k)
            for (t in 1..com.robert4lyf.hypershop.data.ListRepository.MAX_TRIPS) {
                for (n in 1..60) {
                    c.repository.manualAdd("thing ${(n * 7 + t) % 90}", QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), null)
                }
                for (i in c.repository.snapshot().liveItems) c.repository.tick(i.id)
                c.repository.finish()
            }
            for (k in keys.take(6)) c.planner.plan(week, k)
        }
        val (_, preview) = timed { runBlocking { c.planner.previewWeek(week) } }
        budget("plan: preview of 6 meals to add", preview, 300.0)
        val (_, add) = timed { runBlocking { c.planner.addWeekToList(week) } }
        budget("plan: 6 meals onto the list (one transaction)", add, 1300.0)
        val (_, other) = timed { runBlocking { c.planner.week(week - 3) } }
        budget("plan: another week's meals", other, 150.0)
        val (_, buy) = timed { runBlocking { c.buyAgain.view() } }
        budget("buy again: suggestions from 26 trips", buy, 150.0)
        val (_, home) = timed { runBlocking { c.homeCheck.view(c.repository.snapshot()) } }
        budget("at home check over the week's list", home, 150.0)
    }

    @Test
    fun theShopScreenAt150ItemsOnTheMainThread() {
        assumeTrue("run on request only", enabled)
        val c = DeviceContainer.create()
        c.fill(150)
        runBlocking { c.settings.setListMode(ListMode.SHOP) }
        // Disk reads and writes on the main thread, while the screen is used.
        val violations = CopyOnWriteArrayList<String>()
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            StrictMode.setThreadPolicy(
                StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites()
                    .penaltyListener(Runnable::run) { v -> violations += v.stackTrace.take(6).joinToString(" < ") }
                    .build(),
            )
        }
        val (_, first) =
            timed {
                compose.setContent { HyperShopTheme { HyperShopApp(c) } }
                compose.waitUntil(30_000) { compose.onAllNodesWithTag("shop").fetchSemanticsNodes().isNotEmpty() }
            }
        budget("shop screen: first frame with 150 items", first, 4000.0)
        // The ticks still to buy: a row just ticked stays in place for a moment (Release C).
        val ticks =
            SemanticsMatcher("an open tick") {
                it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("tick-") == true &&
                    it.config.getOrNull(SemanticsProperties.StateDescription) == "Still needed"
            }
        val each =
            (1..10).map { i ->
                timed {
                    compose.onAllNodes(ticks)[0].performClick()
                    compose.waitUntil(
                        10_000,
                    ) { compose.onAllNodesWithText("$i of 150 bought", substring = true).fetchSemanticsNodes().isNotEmpty() }
                }.second
            }.sorted()
        budget("shop: tap to \"N of 150 bought\" (median of 10)", each[each.size / 2], 750.0)
        report("shop: tap to \"N of 150 bought\" (worst of 10)", each.last())
        val (_, scroll) =
            timed {
                compose.onNodeWithTag(
                    "list",
                ).performScrollToIndex(compose.onNodeWithTag("list").fetchSemanticsNode().children.size.coerceAtLeast(1) - 1)
                compose.waitForIdle()
            }
        report("shop: scroll to the end", scroll)
        InstrumentationRegistry.getInstrumentation().runOnMainSync { StrictMode.setThreadPolicy(StrictMode.ThreadPolicy.LAX) }
        val mem = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        report("memory: total PSS after the shop session", mem.totalPss / 1024.0, "MB")
        report("main-thread disk violations during the shop session", violations.size.toDouble(), "")
        violations.distinct().take(5).forEach { Log.i(TAG, "  violation: $it") }
        assertTrue("database or file work on the main thread: ${violations.distinct().take(3)}", violations.isEmpty())
    }

    /** [n] saved recipes, each with its own photo, written as RecipePhotos writes them (a 1280-pixel JPEG named by its hash). */
    private fun AppContainer.photographed(n: Int): List<Long> {
        val dir = java.io.File(context.filesDir, com.robert4lyf.hypershop.importing.RecipePhotos.DIR).apply { mkdirs() }
        val rnd = java.util.Random(n.toLong())
        return (1..n).map { i ->
            val id = saveRecipe("Recipe $i", "2 onions", "500g beef mince")
            // A photo-like JPEG: blocks of colour with grain, about the size a real 1280-pixel photo compresses to.
            val bmp = android.graphics.Bitmap.createBitmap(1280, 960, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bmp)
            val paint = android.graphics.Paint()
            for (b in 0 until 600) {
                paint.color = android.graphics.Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
                val x = rnd.nextInt(1280).toFloat()
                val y = rnd.nextInt(960).toFloat()
                canvas.drawRect(x, y, x + 20 + rnd.nextInt(200), y + 20 + rnd.nextInt(200), paint)
            }
            val bytes =
                java.io.ByteArrayOutputStream().also {
                    bmp.compress(
                        android.graphics.Bitmap.CompressFormat.JPEG,
                        85,
                        it,
                    )
                }.toByteArray()
            bmp.recycle()
            val hash = java.security.MessageDigest.getInstance("SHA-256").digest(bytes).take(12).joinToString("") { "%02x".format(it) }
            val name = "$hash.jpg"
            java.io.File(dir, name).writeBytes(bytes)
            runBlocking { repository.setRecipePhoto(repository.libraryKeyOf(id)!!, name, null) }
            id
        }
    }

    /** Waits until no photo has been decoded for [quietMs]: the screen has drawn what it is going to. */
    private fun settled(quietMs: Long = 400): Int {
        var last = -1
        var since = System.currentTimeMillis()
        val end = since + 30_000
        while (System.currentTimeMillis() < end) {
            compose.waitForIdle()
            val now = com.robert4lyf.hypershop.ui.recipes.PhotoDecodes.count.get()
            if (now != last) {
                last = now
                since = System.currentTimeMillis()
            } else if (System.currentTimeMillis() - since >= quietMs) {
                break
            }
            Thread.sleep(50)
        }
        return last
    }

    /**
     * T.4: Saved with [n] photographed recipes. How long the first screen and a scroll to the end and back
     * take, how many photos are decoded for each step and how many bitmap bytes that makes, leaving and
     * coming back, opening a recipe, and the memory after.
     */
    private fun recipePhotos(n: Int) {
        assumeTrue("run on request only", enabled)
        val c = DeviceContainer.create()
        c.photographed(n)
        val decodes = com.robert4lyf.hypershop.ui.recipes.PhotoDecodes
        decodes.count.set(0)
        decodes.bytes.set(0)
        var shown by androidx.compose.runtime.mutableStateOf(true)
        // Newest first: the card at the top of Saved.
        val newest = "lib-" + runBlocking { c.repository.observeLibrary().first() }.maxBy { it.lastAddedAt }.key

        fun step(
            what: String,
            block: () -> Unit,
        ) {
            val c0 = decodes.count.get()
            val b0 = decodes.bytes.get()
            val (_, ms) = timed(block)
            settled()
            report("photos x$n: $what", ms)
            report("photos x$n: $what, photos decoded", (decodes.count.get() - c0).toDouble(), "")
            report("photos x$n: $what, bitmap bytes made", (decodes.bytes.get() - b0) / 1048576.0, "MB")
        }
        step("Saved, first screen") {
            compose.setContent {
                HyperShopTheme {
                    if (shown) {
                        com.robert4lyf.hypershop.ui.recipes.RecipesRoute(
                            c,
                            androidx.compose.material3.SnackbarHostState(),
                            onReview = {},
                        )
                    }
                }
            }
            compose.onNodeWithTag("view-library").performClick()
            compose.waitUntil(30_000) { compose.onAllNodesWithTag(newest).fetchSemanticsNodes().isNotEmpty() }
        }
        val last = n + 1
        step("scroll to the end") {
            compose.onNodeWithTag("recipes").performScrollToIndex(last)
            compose.waitForIdle()
        }
        step("scroll back to the top") {
            compose.onNodeWithTag("recipes").performScrollToIndex(0)
            compose.waitForIdle()
        }
        step("leave the tab and come back") {
            shown = false
            compose.waitForIdle()
            shown = true
            compose.waitForIdle()
            compose.onNodeWithTag("view-library").performClick()
            compose.waitForIdle()
        }
        step("open a recipe and close it") {
            compose.onNodeWithTag(newest).performClick()
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("recipe-photo").fetchSemanticsNodes().isNotEmpty() }
            InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)
            compose.waitUntil(10_000) { compose.onAllNodesWithTag("recipe-photo").fetchSemanticsNodes().isEmpty() }
        }
        val mem = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        report("photos x$n: memory, total PSS", mem.totalPss / 1024.0, "MB")
        report("photos x$n: memory, graphics", (mem.getMemoryStat("summary.graphics")?.toDoubleOrNull() ?: 0.0) / 1024.0, "MB")
        val rt = Runtime.getRuntime()
        report("photos x$n: memory, Java heap used", (rt.totalMemory() - rt.freeMemory()) / 1048576.0, "MB")
    }

    @Test
    fun recipePhotosAt20() = recipePhotos(20)

    @Test
    fun recipePhotosAt100() = recipePhotos(100)

    @Test
    fun recipePhotosAt300() = recipePhotos(300)

    private companion object {
        const val TAG = "HyperShopPerf"
    }
}
