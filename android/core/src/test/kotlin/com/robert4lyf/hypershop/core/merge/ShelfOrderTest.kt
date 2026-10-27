package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.model.AisleCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/** X.9: what the shopper arranges within an aisle, kept for the next trip. */
class ShelfOrderTest {
    private val p = AisleCode.PRODUCE
    private val catalogue = listOf("Vegetables", "Salad", "Fruit", "Herbs", null)

    private fun rank(g: String?) = catalogue.indexOf(g).let { if (it < 0) catalogue.size else it }

    private fun slot(
        key: String,
        group: String? = "Herbs",
        position: Int = 0,
    ) = ShelfOrder.Slot(p, group, key, position)

    private fun ShelfOrder.sortItems(rows: List<ShelfOrder.Slot>) =
        rows.sortedWith(compareBy({ groupSort(it.aisle, it.group, rank(it.group)) }, { itemSort(it) }, { it.position })).map { it.mergeKey }

    @Test
    fun groupsKeepTheCatalogueOrderUntilArranged() {
        val o = ShelfOrder()
        val rows = listOf(slot("basil", position = 0), slot("lettuce", "Salad", 1), slot("dill", position = 2), slot("rocket", "Salad", 3))
        assertEquals(listOf("lettuce", "rocket", "basil", "dill"), o.sortItems(rows))
    }

    @Test
    fun movingAGroupUpPassesTheGroupShownAboveIt() {
        val present = listOf("Salad", "Herbs")
        val o = ShelfOrder().movedGroup(p, present, catalogue, "Herbs", -1, ::rank)!!
        val rows = listOf(slot("lettuce", "Salad", 0), slot("basil", position = 1), slot("carrot", "Vegetables", 2))
        // Herbs went above Salad; Vegetables, not on the list then, kept its place at the top.
        assertEquals(listOf("carrot", "basil", "lettuce"), o.sortItems(rows))
        assertNull(o.movedGroup(p, listOf("Herbs", "Salad"), catalogue, "Herbs", -1, ::rank))
    }

    @Test
    fun anItemGoesToAnEndInOneMove() {
        // UX review 17: "Move to top" / "Move to bottom" across a long group.
        val today = listOf(slot("basil", position = 0), slot("dill", position = 1), slot("mint", position = 2), slot("sage", position = 3))
        val o = ShelfOrder().movedItemToEnd(today, today[3], top = true)!!
        assertEquals(listOf("sage", "basil", "dill", "mint"), o.sortItems(today))
        val shown = listOf(today[3], today[0], today[1], today[2])
        assertEquals(listOf("basil", "dill", "mint", "sage"), o.movedItemToEnd(shown, today[3], top = false)!!.sortItems(today))
        assertEquals(null, o.movedItemToEnd(shown, today[3], top = true))
    }

    @Test
    fun anArrangedItemKeepsItsPlaceOnTheNextTrip() {
        val today = listOf(slot("basil", position = 0), slot("dill", position = 1), slot("mint", position = 2))
        val o = ShelfOrder().movedItem(today, today[2], -1)!!.let { it.movedItem(listOf(today[0], today[2], today[1]), today[2], -1)!! }
        assertEquals(listOf("mint", "basil", "dill"), o.sortItems(today))
        // Next week: added in another order, and coriander is new: it follows the arranged ones.
        val next = listOf(slot("coriander", position = 10), slot("dill", position = 11), slot("mint", position = 12))
        assertEquals(listOf("mint", "dill", "coriander"), o.sortItems(next))
    }

    @Test
    fun anItemNotOnTodaysListKeepsItsPlaceAmongTheRest() {
        val first = listOf(slot("basil", position = 0), slot("dill", position = 1), slot("mint", position = 2))
        var o = ShelfOrder().movedItem(first, first[0], +1)!! // dill, basil, mint
        val today = listOf(slot("basil", position = 5), slot("mint", position = 6))
        o = o.movedItem(today, today[1], -1)!! // mint above basil; dill stays first
        val all = listOf(slot("basil", position = 0), slot("dill", position = 1), slot("mint", position = 2))
        assertEquals(listOf("dill", "mint", "basil"), o.sortItems(all))
    }

    @Test
    fun theEndsHaveNowhereToGo() {
        val rows = listOf(slot("basil"), slot("dill", position = 1))
        assertNull(ShelfOrder().movedItem(rows, rows[0], -1))
        assertNull(ShelfOrder().movedItem(rows, rows[1], +1))
    }
}
