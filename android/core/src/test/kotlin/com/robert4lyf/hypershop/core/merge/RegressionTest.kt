package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.model.BaseKey
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
    fun haveSomeInAnotherDimensionIsNotPartial() {
        val p =
            merger.preview(
                ShoppingList(),
                recipe(listOf("200 ml milk")),
                mapOf("cat:milk" to RowDecision(haveSome = g(50))),
            )
        assertEquals(LineStatus.ADDED, p.lineStatus[1L])
        assertEquals(Rational.of(200), p.rows.single().adding[BaseKey.VOLUME])
    }
}
