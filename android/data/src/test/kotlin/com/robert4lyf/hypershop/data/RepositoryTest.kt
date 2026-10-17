package com.robert4lyf.hypershop.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.merge.ApplyResult
import com.robert4lyf.hypershop.core.merge.LineStatus
import com.robert4lyf.hypershop.core.merge.RowDecision
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.RuleParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

private val parser = RuleParser()

internal suspend fun DbRule.import(
    vararg lines: String,
    url: String = "https://example.com/r",
    servings: Int = 4,
    decisions: Map<String, RowDecision>? = null,
): ApplyResult.Applied {
    val stored = repo.storeRecipe(url, "Recipe", 4, UnitLocale.METRIC, "WEB", ParsedBy.RULES, null, false, lines.map { parser.parse(it) })
    return repo.apply(repo.preview(stored, servings, decisions)) as ApplyResult.Applied
}

/** Step 5.2b: apply preview, tick and untick, manual add, edit quantity. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RepositoryTest {
    @get:Rule val r = DbRule()

    @Test
    fun applyPreviewWritesItemsContributionsAndDecisions() =
        runBlocking {
            val applied = r.import("2 onions", "Salt, to taste", "200 ml milk")
            val l = r.repo.snapshot()
            // Salt is a staple: pre-ticked in stock, so not added.
            assertEquals(setOf("cat:onion", "cat:milk"), l.liveItems.map { it.mergeKey }.toSet())
            assertEquals(Rational.of(2), l.total(l.itemByKey("cat:onion")!!.id)[BaseKey.ITEMS])
            val statuses = r.repo.lineStatuses(applied.useId)
            assertEquals(listOf(LineStatus.ADDED, LineStatus.IN_STOCK, LineStatus.ADDED), statuses.toSortedMap().values.toList())
            assertEquals(l.listRev, r.db.dao().meta()!!.listRev)
        }

    @Test
    fun confirmRechecksRevs() =
        runBlocking {
            r.import("2 onions")
            val stored =
                r.repo.storeRecipe(
                    null, null, 4, UnitLocale.METRIC, "WEB", ParsedBy.RULES, null, false,
                    listOf(
                        parser.parse("1 onion"),
                    ),
                )
            val p = r.repo.preview(stored, 4)
            r.repo.tick(r.repo.snapshot().itemByKey("cat:onion")!!.id)
            assertEquals(ApplyResult.Stale, r.repo.apply(p))
            assertTrue(r.repo.apply(r.repo.preview(stored, 4)) is ApplyResult.Applied)
        }

    @Test
    fun tickAndUntick() =
        runBlocking {
            r.import("2 onions")
            val id = r.repo.snapshot().itemByKey("cat:onion")!!.id
            assertTrue(r.repo.tick(id))
            assertTrue(r.repo.snapshot().isDone(id))
            assertFalse(r.repo.tick(id))
            assertTrue(r.repo.untick(id))
            assertFalse(r.repo.snapshot().isDone(id))
            assertTrue(r.db.dao().ticks().isEmpty())
        }

    @Test
    fun manualAddAndEditQuantity() =
        runBlocking {
            val id = r.repo.manualAdd("Eggs", QtyBase.of(Rational.of(6), null, UnitLocale.METRIC), null)
            assertEquals(id, r.repo.manualAdd("egg", QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), null))
            assertEquals(Rational.of(7), r.repo.snapshot().total(id)[BaseKey.ITEMS])
            r.repo.editQuantity(id, QtyBase.of(Rational.of(4), null, UnitLocale.METRIC))
            assertEquals(Rational.of(4), r.repo.snapshot().total(id)[BaseKey.ITEMS])
            assertEquals(AisleCode.DAIRY_EGGS, r.repo.snapshot().items.getValue(id).aisle)
        }

    @Test
    fun moveAisleAlwaysWritesAnOverride() =
        runBlocking {
            r.import("2 onions")
            val id = r.repo.snapshot().itemByKey("cat:onion")!!.id
            r.repo.moveAisle(id, AisleCode.WORLD_FOODS, always = false)
            assertTrue(r.repo.overrides().isEmpty())
            r.repo.moveAisle(id, AisleCode.FROZEN, always = true)
            assertEquals(mapOf("cat:onion" to AisleCode.FROZEN), r.repo.overrides())
            r.import("1 onion", url = "https://example.com/2")
            assertEquals(AisleCode.FROZEN, r.repo.snapshot().itemByKey("cat:onion")!!.aisle)
        }

    @Test
    fun alreadyOnList() =
        runBlocking {
            assertFalse(r.repo.alreadyOnList("https://example.com/r"))
            r.import("2 onions")
            assertTrue(r.repo.alreadyOnList("https://example.com/r"))
            assertNull(r.repo.snapshot().itemByKey("cat:salt"))
        }
}
