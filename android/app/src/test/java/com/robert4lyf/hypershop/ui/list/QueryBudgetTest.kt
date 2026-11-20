package com.robert4lyf.hypershop.ui.list

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.util.concurrent.CopyOnWriteArrayList

/**
 * What one tick costs in database statements, with the list screen's two flows
 * collected as ListRoute collects them (the rows, and the week's plan card).
 * Measured before the Release C changes (docs/reports/release-c-performance.md)
 * and held there, so a later change that reads more for a tick is noticed.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class QueryBudgetTest {
    private fun statements(
        log: MutableList<String>,
        c: com.robert4lyf.hypershop.di.AppContainer,
    ): Int {
        val vm = ListViewModel(c)
        val collecting =
            CoroutineScope(Dispatchers.Main).launch {
                launch { vm.ui.collect {} }
                launch { vm.prep.collect {} }
            }
        await(vm.ui) { it.loaded && it.aisles.isNotEmpty() }
        // Everything the first read set off has run.
        settle(600)
        val id = vm.ui.value.aisles.first().second.first().id
        log.clear()
        vm.toggle(id)
        await(vm.ui) { u -> u.aisles.flatMap { it.second }.first { it.id == id }.state == TickState.DONE }
        settle(600)
        collecting.cancel()
        val reads = log.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) }
        println("${reads.size} reads, ${log.size} statements for one tick\n" + reads.joinToString("\n"))
        return reads.size
    }

    /** Runs the main looper for [ms] of wall time, so every flow set off by the last step has emitted. */
    private fun settle(ms: Long) {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(100))
            Thread.sleep(5)
        }
    }

    @Test
    fun aTickStaysWithinItsReadBudget() {
        val log = CopyOnWriteArrayList<String>()
        val c = TestContainer.create(onQuery = { log += it })
        runBlocking {
            for (l in ListViewModel.SAMPLE) {
                val p = c.ruleParser.parse(l)
                c.repository.manualAdd(p.name, p.shopBase(UnitLocale.METRIC), p.shopUnit)
            }
        }
        val reads = statements(log, c)
        assertTrue("$reads reads for a tick (budget $READS)", reads <= READS)
    }

    companion object {
        /**
         * Measured after C.6 (before: 37). A tick reads the list twice by design, once in the write
         * and once when the flow re-reads it; Buy again no longer loads the list a third time.
         * Step Z: there is one list screen (no shop screen with its own, smaller budget of 24), so
         * this is the planning screen's budget, unchanged.
         */
        const val READS = 34
    }
}
