package com.robert4lyf.hypershop.device

import android.os.Debug
import android.os.StrictMode
import android.util.Log
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
        budget("search 300 saved recipes: first search (builds the index)", search("avocado"), 8000.0)
        budget("search 300 saved recipes: a second search", search("cumin"), 2000.0)
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
        val ticks =
            SemanticsMatcher("a tick") { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith("tick-") == true }
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

    private companion object {
        const val TAG = "HyperShopPerf"
    }
}
