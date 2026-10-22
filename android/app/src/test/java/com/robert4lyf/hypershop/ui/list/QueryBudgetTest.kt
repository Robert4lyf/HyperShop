package com.robert4lyf.hypershop.ui.list

import android.os.Looper
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.settings.ListMode
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
        mode: ListMode,
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
        runBlocking { c.settings.setListMode(mode) }
        await(vm.ui) { it.mode == mode }
        // Everything the mode change set off has run.
        settle(600)
        val id = vm.ui.value.aisles.first().second.first().id
        log.clear()
        vm.toggle(id)
        await(vm.ui) { u -> u.aisles.flatMap { it.second }.first { it.id == id }.state == TickState.DONE }
        settle(600)
        collecting.cancel()
        val reads = log.filter { it.trimStart().startsWith("SELECT", ignoreCase = true) }
        println("$mode: ${reads.size} reads, ${log.size} statements for one tick\n" + reads.joinToString("\n"))
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
        val shop = statements(ListMode.SHOP, log, c)
        val plan = statements(ListMode.PLAN, log, c)
        assertTrue("$shop reads for a tick in the shop (budget $SHOP_READS)", shop <= SHOP_READS)
        assertTrue("$plan reads for a tick while planning (budget $PLAN_READS)", plan <= PLAN_READS)
    }

    companion object {
        /**
         * Measured after C.6 (before: 28 and 37). A tick reads the list twice by design, once
         * in the write and once when the flow re-reads it; in the shop nothing is read for the
         * plan card, and while planning Buy again no longer loads the list a third time.
         */
        const val SHOP_READS = 24
        const val PLAN_READS = 34
    }
}
