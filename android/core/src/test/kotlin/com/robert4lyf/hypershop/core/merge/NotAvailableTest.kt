package com.robert4lyf.hypershop.core.merge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** X.8: "Not available" sets an item aside for this trip; it is back, unbought, on the next one. */
class NotAvailableTest {
    @Test
    fun setAsideIsUndoneByFindingIt() {
        val (l, _) = add(ShoppingList(), listOf("2 onions"))
        val onion = l.itemByKey("cat:onion")!!.id
        val aside = l.markUnavailable(onion, now = 50)!!
        assertTrue(aside.isUnavailable(onion))
        assertEquals(l.listRev + 1, aside.listRev)
        assertEquals(l.items.getValue(onion).rev + 1, aside.items.getValue(onion).rev)
        // Already set aside: nothing to do.
        assertNull(aside.markUnavailable(onion, now = 60))
        val back = aside.markAvailable(onion)!!
        assertFalse(back.isUnavailable(onion))
        assertNull(back.markAvailable(onion))
    }

    @Test
    fun aBoughtItemCannotBeSetAsideAndATickClearsIt() {
        val (l, _) = add(ShoppingList(), listOf("2 onions", "1 carrot"))
        val onion = l.itemByKey("cat:onion")!!.id
        assertNull(l.tick(onion, 5)!!.markUnavailable(onion, now = 6))
        // Found after all: ticking it is the answer, and it is no longer set aside.
        val ticked = l.markUnavailable(onion, now = 6)!!.tick(onion, 7)!!
        assertFalse(ticked.isUnavailable(onion))
        assertTrue(ticked.isDone(onion))
    }

    @Test
    fun finishingKeepsASetAsideItemForNextTime() {
        var (l, _) = add(ShoppingList(), listOf("2 onions", "1 carrot", "1 leek"))
        val onion = l.itemByKey("cat:onion")!!.id
        val carrot = l.itemByKey("cat:carrot")!!.id
        l = l.tick(onion, 5)!!.markUnavailable(carrot, now = 6)!!
        val finished = l.finishShopping(now = 100)
        assertEquals(setOf("cat:carrot", "cat:leek"), finished.liveItems.map { it.mergeKey }.toSet())
        assertFalse(finished.isUnavailable(carrot))
        assertFalse(finished.isDone(carrot))
        assertEquals(l.items.getValue(carrot).rev + 1, finished.items.getValue(carrot).rev)
    }
}
