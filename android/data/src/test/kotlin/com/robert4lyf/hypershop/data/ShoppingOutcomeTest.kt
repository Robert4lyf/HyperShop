package com.robert4lyf.hypershop.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import com.robert4lyf.hypershop.core.merge.ApplyResult
import com.robert4lyf.hypershop.core.merge.Display
import com.robert4lyf.hypershop.core.merge.ShoppingList
import com.robert4lyf.hypershop.core.merge.UnitsSetting
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.RuleParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/**
 * The whole way from a recipe's line to what the shopper reads (quality pass Q.3):
 * the line parsed, matched to the catalogue, merged with another recipe's line in
 * other units, scaled, bought, set aside as at home, and shown on the list and on
 * the watch (both are [Display.row]) in metric and imperial. The lines are ones the
 * parser used to get wrong: tinned tomatoes filed as fresh, a Parmigiano in ml.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ShoppingOutcomeTest {
    @get:Rule val r = DbRule()

    private val parser = RuleParser()
    private val catalog = CatalogLoader.default

    private suspend fun add(
        url: String,
        locale: UnitLocale,
        vararg lines: String,
    ): ApplyResult.Applied {
        val stored = r.repo.storeRecipe(url, url, 4, locale, "WEB", ParsedBy.RULES, null, false, lines.map { parser.parse(it) })
        return r.repo.apply(r.repo.preview(stored, 4)) as ApplyResult.Applied
    }

    private fun ShoppingList.row(
        key: String,
        units: UnitsSetting = UnitsSetting.METRIC,
    ): String? {
        val item = itemByKey(key)?.takeIf { it in liveItems } ?: return null
        return Display.row(this, item, catalog.byId[item.catalogId ?: ""], units).main
    }

    @Test
    fun aUkAndAUsRecipeMergeIntoRowsAShopperCanBuyFrom() =
        runBlocking {
            val uk =
                add(
                    "https://example.co.uk/ragu",
                    UnitLocale.METRIC,
                    "400g tin chopped tomatoes",
                    "500g beef mince",
                    "1 cup grated parmesan",
                )
            add(
                "https://example.com/lasagna",
                UnitLocale.US,
                "2 (14.5-oz.) cans whole tomatoes",
                "1 lb ground beef",
                "Parmigiano Reggiano PDO cheese 1 cup",
            )
            var l = r.repo.snapshot()
            // One row per product: the US cans join the UK tin (not fresh tomatoes), the ground beef the mince,
            // the Parmigiano the parmesan, weighed rather than in ml (a 250 ml and a 240 ml cup, 100 g a cup).
            assertEquals(3, l.liveItems.size)
            assertEquals("3 tins chopped tomatoes", l.row("cat:chopped_tomatoes"))
            assertEquals("960g beef mince", l.row("cat:beef_mince"))
            assertEquals("2¼lb beef mince", l.row("cat:beef_mince", UnitsSetting.IMPERIAL))
            assertEquals("210g parmesan", l.row("cat:parmesan"))
            assertEquals("7¼oz parmesan", l.row("cat:parmesan", UnitsSetting.IMPERIAL))

            // The UK recipe for eight: its share doubles, the US one does not.
            r.repo.rescaleUse(uk.useId, 8)
            l = r.repo.snapshot()
            assertEquals("4 tins chopped tomatoes", l.row("cat:chopped_tomatoes"))
            assertEquals("1.5kg beef mince", l.row("cat:beef_mince"))

            // The mince is bought; then the US recipe is doubled too: only the extra is still to buy.
            r.repo.tick(l.itemByKey("cat:beef_mince")!!.id)
            val us = r.repo.snapshot().uses.values.single { it.id != uk.useId }
            r.repo.rescaleUse(us.id, 8)
            l = r.repo.snapshot()
            assertEquals("460g more beef mince", l.row("cat:beef_mince"))

            // The tins are in the cupboard: set aside, and the list and the watch agree.
            HomeCheck(r.repo).have(listOf("cat:chopped_tomatoes"))
            l = r.repo.snapshot()
            assertNull(l.row("cat:chopped_tomatoes"))
            val watch = r.repo.watchView().list
            for (key in listOf("cat:beef_mince", "cat:parmesan")) {
                for (u in UnitsSetting.entries) assertEquals(key, l.row(key, u), watch.row(key, u))
            }
        }
}
