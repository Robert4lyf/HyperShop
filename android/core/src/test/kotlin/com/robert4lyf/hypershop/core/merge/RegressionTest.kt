package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Bugs found in review, one test each. */
class RegressionTest {
    private fun g(v: Int) = QtyBase.of(Rational.of(v), QtyUnit.G, UnitLocale.METRIC)

    private fun ml(v: Int) = QtyBase.of(Rational.of(v), QtyUnit.ML, UnitLocale.METRIC)

    private fun neverNegative(
        l: ShoppingList,
        itemId: Long,
    ) {
        for (v in l.uncovered(itemId).parts.values) assertTrue(v >= Rational.ZERO, "uncovered $v")
        for (v in l.covered(itemId).parts.values) assertTrue(v >= Rational.ZERO, "covered $v")
    }

    @Test
    fun aBareNumberKeepsTheItemsCountUnit() {
        val cloves = QtyBase.of(Rational.of(2), QtyUnit.CLOVE, UnitLocale.METRIC)
        var (l, id) = ShoppingList().manualAdd(merger.target("garlic"), cloves, QtyUnit.CLOVE, 1)
        l = l.editQuantity(id, QtyBase.of(Rational.of(3), null, UnitLocale.METRIC), 2)
        assertEquals(QtyBase.of(Rational.of(3), QtyUnit.CLOVE, UnitLocale.METRIC), l.total(id))
        neverNegative(l, id)
    }

    @Test
    fun aCatalogueChangeBumpsEveryLiveItem() {
        val (l, id) = ShoppingList().manualAdd(merger.target("spinach"), g(200), QtyUnit.G, 1)
        val after = l.catalogueChanged()
        assertTrue(after.items.getValue(id).rev > l.items.getValue(id).rev)
        assertTrue(after.listRev > l.listRev)
    }

    @Test
    fun loweringADoneItemKeepsItDone() {
        var (l, id) = ShoppingList().manualAdd(merger.target("spinach"), g(200), QtyUnit.G, 1)
        l = l.tick(id, 2)!!
        l = l.editQuantity(id, g(100), 3)
        assertTrue(l.isDone(id))
        assertEquals(g(100), l.total(id))
        assertEquals(QtyBase.PRESENCE, l.uncovered(id))
        neverNegative(l, id)
    }

    @Test
    fun loweringComesOffWhatIsStillToBuyFirst() {
        var (l, id) = ShoppingList().manualAdd(merger.target("spinach"), g(200), QtyUnit.G, 1)
        l = l.tick(id, 2)!!
        l = l.manualAdd(merger.target("spinach"), g(100), QtyUnit.G, 3).first
        assertFalse(l.isDone(id))
        l = l.editQuantity(id, g(200), 4)
        assertTrue(l.isDone(id), "the 100 g still to buy went, the 200 g bought stays")
        assertEquals(g(200), l.total(id))
        l = l.editQuantity(id, g(50), 5)
        assertTrue(l.isDone(id))
        assertEquals(g(50), l.total(id))
        neverNegative(l, id)
    }

    @Test
    fun anIncreaseIsNotCoveredByAStaleWatchTick() {
        var (l, id) = ShoppingList().manualAdd(merger.target("spinach"), g(200), QtyUnit.G, 1)
        val seen = l.listRev
        l = l.editQuantity(id, g(300), 2)
        val (ticked, r) = l.watchTick(id, seen, 3, installId = 1, seq = 1)
        assertEquals(WatchTickResult.PARTIAL, r)
        assertEquals(g(100), ticked!!.uncovered(id))
        assertEquals(g(200), ticked.covered(id))
    }

    @Test
    fun undoFinishShoppingUnarchivesUses() {
        var (l, useId) = add(ShoppingList(), listOf("2 onions"))
        l = l.tick(l.itemByKey("cat:onion")!!.id, 5)!!
        val stamp = 5 + ShoppingList.SEVEN_DAYS
        val finished = l.finishShopping(stamp)
        assertEquals(stamp, finished.uses.getValue(useId).archivedAt)
        val undone = finished.undoDelete(stamp)
        assertNull(undone.uses.getValue(useId).archivedAt)
        assertEquals(l.uses, undone.uses)
    }

    @Test
    fun zeroLowerBoundRangeDoesNotDivideByZero() {
        val p = parser.parse("0-2 cups (500ml) milk")
        assertEquals(Rational.ZERO, p.quantity)
        assertEquals(Rational.of(2), p.quantityMax)
        assertEquals(ml(500), p.shopBase(UnitLocale.US))
        val inc = merger.incoming(1, 4, 4, UnitLocale.US, listOf(1L to p))
        val preview = merger.preview(ShoppingList(), inc, emptyMap())
        assertEquals(ml(500), preview.rows.single().adding)
    }

    @Test
    fun applyIsStaleWhenTheItemWasDeletedAndReAddedWithTheSameRev() {
        val (l, _) = add(ShoppingList(), listOf("2 onions"))
        val onion = l.itemByKey("cat:onion")!!
        val p = merger.preview(l, recipe(listOf("1 onion")), emptyMap())
        // Delete it and add 5 by hand: a new item, whose rev happens to match.
        var l2 = l.softDelete(onion.id, 9)
        l2 = l2.manualAdd(merger.target("onion"), QtyBase.of(Rational.of(5), null, UnitLocale.METRIC), null, 10).first
        val again = l2.itemByKey("cat:onion")!!
        l2 = l2.copy(items = l2.items + (again.id to again.copy(rev = onion.rev)))
        assertTrue(again.id != onion.id)
        assertEquals(ApplyResult.Stale, merger.apply(l2, p, 11))
    }

    @Test
    fun swappingRenamesOrMergesAndKeepsTheAmounts() {
        val tbsp = QtyBase.of(Rational.ONE, QtyUnit.TBSP, UnitLocale.METRIC)
        var (l, agave) = ShoppingList().manualAdd(merger.target("agave syrup"), tbsp, QtyUnit.TBSP, 1)
        // Not on the list yet: renamed in place, amount kept, rev moved on.
        val swapped = l.swapItem(agave, merger.target("maple syrup"))!!
        assertEquals("cat:maple_syrup", swapped.items.getValue(agave).mergeKey)
        assertEquals(tbsp, swapped.total(agave))
        assertTrue(swapped.items.getValue(agave).rev > l.items.getValue(agave).rev)
        // Already on the list: merged into it, amounts added.
        val (l2, maple) = l.manualAdd(merger.target("maple syrup"), tbsp, QtyUnit.TBSP, 2)
        l = l2.swapItem(agave, merger.target("maple syrup"))!!
        assertNull(l.itemByKey("cat:agave_syrup"))
        assertEquals(tbsp + tbsp, l.total(maple))
        assertEquals(maple, l.aliases[agave])
        assertNull(l.swapItem(maple, merger.target("maple syrup")))
    }

    @Test
    fun alwaysUseInsteadSendsNewLinesToTheReplacement() {
        val catalog = com.robert4lyf.hypershop.core.catalog.CatalogLoader.default.copy()
        val agave = catalog.byId.getValue("agave_syrup")
        catalog.replace("test", catalog.entries.map { if (it.id == agave.id) it.copy(replaceWith = "maple_syrup") else it })
        val m = Merger(catalog)
        assertEquals("cat:maple_syrup", m.target("agave nectar").mergeKey)
        assertEquals("cat:honey", m.target("honey").mergeKey)
    }

    @Test
    fun pastaShapesMushroomsAndFishAreTheirOwnItems() {
        val m = Merger(com.robert4lyf.hypershop.core.catalog.CatalogLoader.default)
        val keys =
            listOf(
                "oyster mushrooms",
                "chestnut mushrooms",
                "wild mushrooms",
                "rigatoni",
                "penne",
                "linguine",
                "spaghetti",
                "haddock",
                "white fish",
            ).map { m.target(it).mergeKey }
        assertEquals(
            listOf(
                "cat:oyster_mushroom",
                "cat:chestnut_mushroom",
                "cat:wild_mushroom",
                "cat:rigatoni",
                "cat:penne",
                "cat:linguine",
                "cat:spaghetti",
                "cat:haddock",
                "cat:white_fish",
            ),
            keys,
        )
    }

    @Test
    fun groupsSortInCatalogueOrderWithinTheirAisle() {
        val c = com.robert4lyf.hypershop.core.catalog.CatalogLoader.default
        val produce = com.robert4lyf.hypershop.core.model.AisleCode.PRODUCE
        val ranks = listOf("Vegetables", "Salad", "Herbs", "Fruit", "Mine", null).map { c.groupRank(produce, it) }
        assertEquals(ranks.sorted(), ranks)
        assertEquals("Herbs", c.byId.getValue("basil").group)
    }

    @Test
    fun lentilsOfDifferentColoursAreDifferentItems() {
        val m = Merger(com.robert4lyf.hypershop.core.catalog.CatalogLoader.default)
        assertEquals("cat:red_lentils", m.target("split red lentils").mergeKey)
        assertEquals("cat:green_lentils", m.target("green lentils").mergeKey)
        assertEquals("cat:puy_lentils", m.target("french lentils").mergeKey)
        assertEquals("cat:black_lentils", m.target("beluga lentils").mergeKey)
        assertEquals("cat:lentils", m.target("lentils").mergeKey)
    }

    @Test
    fun defaultSwapsNameRealIngredients() {
        val ids = com.robert4lyf.hypershop.core.catalog.CatalogLoader.default.byId.keys
        assertEquals(emptySet<String>(), com.robert4lyf.hypershop.core.catalog.DefaultSwaps.ids - ids)
        assertTrue("maple_syrup" in com.robert4lyf.hypershop.core.catalog.CatalogLoader.default.byId.getValue("agave_syrup").swaps)
    }
}
