package com.robert4lyf.hypershop.ui.list

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.RuleParser
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.await
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** "Plan this week's shop": meals, Buy again and the At home check, each through the data layer, each with Undo. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BuyAgainViewModelTest {
    private val parser = RuleParser()

    /** Collectors and containers made by a test, stopped after it: nothing may read a database Robolectric has reset. */
    private val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val made = ArrayList<AppContainer>()

    private fun container() = TestContainer.create().also { made += it }

    private val models = ArrayList<BuyAgainViewModel>()

    @After
    fun stop() {
        collectors.cancel()
        // The view model's own flows outlive their last collector by a few seconds (WhileSubscribed):
        // stop them, and let a read already in flight finish, before the database goes.
        for (vm in models) vm.viewModelScope.cancel()
        Thread.sleep(300)
        for (c in made) {
            c.applicationScope.cancel()
            c.db.close()
        }
    }

    private fun BuyAgainViewModel.shown(): BuyAgainViewModel {
        models += this
        collectors.launch { ui.collect {} }
        return this
    }

    /** A saved recipe in "Your recipes", not on the list and not planned. Its library key. */
    private fun save(
        c: AppContainer,
        title: String,
        vararg lines: String,
        servings: Int? = 4,
    ): String =
        runBlocking {
            val url = "https://example.com/${title.lowercase().replace(' ', '-')}"
            val parsed = lines.map { parser.parse(it) }
            val stored = c.repository.storeRecipe(url, title, servings, UnitLocale.METRIC, "WEB", ParsedBy.RULES, null, false, parsed)
            c.repository.saveToLibrary(stored.recipe.id, null)
            url
        }

    private fun onions(c: AppContainer): Rational? =
        runBlocking { c.repository.snapshot() }.let { l -> l.itemByKey("cat:onion")?.let { l.total(it.id)[BaseKey.ITEMS] } }

    @Test
    fun buyAgainAddsAllInOneGoAndNeverDoublesWhatIsThere() {
        val c = container()
        runBlocking {
            repeat(2) {
                for (n in listOf(
                    "milk",
                    "bread",
                    "eggs",
                )) c.repository.manualAdd(n, QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), null)
                for (i in c.repository.snapshot().liveItems) c.repository.tick(i.id)
                c.repository.finish()
            }
            c.repository.manualAdd("bread", QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), null)
        }
        val vm = BuyAgainViewModel(c).shown()
        val ui = await(vm.ui) { it.buyAgain.regulars.size == 3 }
        assertEquals(2, ui.toAdd.size)
        assertTrue(ui.buyAgain.regulars.single { it.name == "bread" }.onList)
        vm.addAllRegulars()
        await(vm.ui) { it.toAdd.isEmpty() }
        val l = runBlocking { c.repository.snapshot() }
        assertEquals(Rational.ONE, l.total(l.itemByKey("cat:bread")!!.id)[BaseKey.ITEMS])
        assertTrue(l.itemByKey("cat:milk") != null)
    }
}
