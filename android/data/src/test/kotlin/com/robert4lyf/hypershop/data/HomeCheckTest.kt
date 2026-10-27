package com.robert4lyf.hypershop.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Release B phase 3: the At home check, one decision per ingredient. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class HomeCheckTest {
    @get:Rule val r = DbRule()

    private val home by lazy { HomeCheck(r.repo) }

    private suspend fun rows() = home.view(r.repo.snapshot())

    @Test
    fun cupboardIngredientsTwoRecipesShareAreOneDecisionAndFreshFoodIsNotAsked() =
        runBlocking {
            r.import("200 g basmati rice", "2 onions", "Salt, to taste")
            r.import("100 g basmati rice", "1 tbsp olive oil", url = "https://example.com/b")
            val rs = rows()
            val rice = rs.single { it.name.contains("rice") }
            assertEquals(false, rice.have)
            assertEquals(listOf("Recipe"), rice.from)
            assertTrue(rs.none { it.mergeKey == "cat:onion" })
            // Salt went at home on review (a staple): shown as had, and usual.
            assertTrue(rs.single { it.mergeKey == "cat:salt" }.let { it.have && it.usual })
        }

    @Test
    fun haveTakesEveryRecipesShareOffAndUndoPutsExactlyThemBack() =
        runBlocking {
            r.import("200 g basmati rice", "2 onions")
            r.import("100 g basmati rice", url = "https://example.com/b")
            val rice = rows().single { it.name.contains("rice") }.mergeKey
            val moved = home.have(listOf(rice))
            assertEquals(2, moved.size)
            assertNull(r.repo.snapshot().itemByKey(rice))
            assertTrue(rows().single { it.mergeKey == rice }.have)
            home.needLines(moved)
            val l = r.repo.snapshot()
            assertEquals(Rational.of(300), l.total(l.itemByKey(rice)!!.id)[BaseKey.MASS])
            // The same rows as before (the rice a new row: it had gone from the list), every line on the list again.
            assertEquals(setOf(rice, "cat:onion"), l.liveItems.map { it.mergeKey }.toSet())
            assertTrue(rows().none { it.have })
        }

    @Test
    fun haveKeepsWhatWasBoughtAndWhatWasAddedByHand() =
        runBlocking {
            r.import("200 g basmati rice")
            val rice = r.repo.snapshot().liveItems.single().mergeKey
            r.repo.tick(r.repo.snapshot().itemByKey(rice)!!.id)
            r.import("100 g basmati rice", url = "https://example.com/b")
            val fifty = QtyBase.of(Rational.of(50), com.robert4lyf.hypershop.core.model.QtyUnit.G, UnitLocale.METRIC)
            r.repo.manualAdd("basmati rice", fifty, null)
            home.have(listOf(rice))
            val l = r.repo.snapshot()
            val item = l.itemByKey(rice)!!.id
            // 200 g bought stays bought; the second recipe's 100 g is at home; the 50 g typed stays to buy.
            assertEquals(Rational.of(250), l.total(item)[BaseKey.MASS])
            assertEquals(Rational.of(200), l.covered(item)[BaseKey.MASS])
        }

    @Test
    fun needPutsAStapleBackOnTheListForThisShopWithoutChangingTheStaple() =
        runBlocking {
            r.import("1 tsp salt", "2 onions")
            val moved = home.need(listOf("cat:salt"))
            assertEquals(1, moved.size)
            assertTrue(r.repo.snapshot().itemByKey("cat:salt") != null)
            assertTrue("cat:salt" in r.repo.staples())
            assertTrue(rows().single { it.mergeKey == "cat:salt" }.let { !it.have && it.usual })
        }

    @Test
    fun aCupboardLineSetAsideAfterTheRestWasBoughtAndFinishedCanBePutBack() =
        runBlocking {
            r.import("2 onions", "500 g spaghetti")
            r.repo.tick(r.repo.snapshot().itemByKey("cat:onion")!!.id)
            r.repo.finish()
            val pasta = rows().single { !it.have }.mergeKey
            val moved = home.have(listOf(pasta))
            // X.13: that was the last thing to buy, so the meal's shopping is done and its cupboard
            // question goes from the screen; the Undo still puts the line back.
            assertTrue(rows().none { it.mergeKey == pasta })
            assertEquals(moved, home.needLines(moved))
            val l = r.repo.snapshot()
            assertEquals(Rational.of(500), l.total(l.itemByKey(pasta)!!.id)[BaseKey.MASS])
        }

    @Test
    fun aLinePartlyBoughtIsNotSetAside() =
        runBlocking {
            val planner = Planner(r.repo)
            planner.plan(planner.currentWeek(), r.save("Pasta", "500 g spaghetti"))
            planner.addWeekToList(planner.currentWeek())
            val pasta = r.repo.snapshot().liveItems.single()
            r.repo.tick(pasta.id)
            planner.setServings(planner.week(planner.currentWeek()).single().id, 8)
            assertTrue(rows().none { it.mergeKey == pasta.mergeKey && !it.have })
            assertTrue(home.have(listOf(pasta.mergeKey)).isEmpty())
            val l = r.repo.snapshot()
            assertEquals(Rational.of(1000), l.total(pasta.id)[BaseKey.MASS])
        }

    @Test
    fun aFinishedShopAsksNothingMoreAboutTheCupboard() =
        runBlocking {
            // X.13: the week bought and finished; "at home" lines were asking about it under an empty list.
            r.import("2 onions", "500 g spaghetti")
            val pasta = rows().single { !it.have }.mergeKey
            home.have(listOf(pasta))
            assertTrue(rows().single { it.mergeKey == pasta }.have)
            r.repo.tick(r.repo.snapshot().itemByKey("cat:onion")!!.id)
            r.repo.finish()
            assertTrue(rows().isEmpty())
            assertTrue(r.repo.inStockOnList(r.repo.snapshot(), stillShopping = true).isEmpty())
            // Still there for Need it and Undo, which read every line.
            assertEquals(listOf(pasta), r.repo.inStockOnList(r.repo.snapshot()).map { it.target.mergeKey })
        }
}
