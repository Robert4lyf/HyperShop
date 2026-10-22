package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.Rational
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WithdrawTest {
    @Test
    fun anUnboughtShareGoesAndARowLeftEmptyGoesWithIt() {
        val (l1, a) = add(ShoppingList(), listOf("2 onions", "1 lemon"))
        val (l2, _) = add(l1, listOf("1 onion"))
        val onion = l2.itemByKey("cat:onion")!!.id
        val lemon = l2.itemByKey("cat:lemon")!!.id
        val ids = l2.contributionsOfUse(a).map { it.id }.toSet()
        val l3 = l2.withdraw(ids)!!
        assertEquals(Rational.ONE, l3.total(onion)[BaseKey.ITEMS])
        assertNull(l3.items[lemon])
        assertTrue(l3.items[onion]!!.rev > l2.items[onion]!!.rev)
        assertTrue(l3.listRev > l2.listRev)
    }

    @Test
    fun aBoughtShareNeverGoes() {
        val (l1, a) = add(ShoppingList(), listOf("2 onions"))
        val onion = l1.itemByKey("cat:onion")!!.id
        val bought = l1.tick(onion, 5, TickSource.PHONE)!!
        assertNull(bought.withdraw(bought.contributionsOfUse(a).map { it.id }.toSet()))
        assertNull(bought.withdraw(setOf(999L)))
    }

    @Test
    fun undoOfTwoDeletionsInOneMillisecondBringsBackOneRowPerKey() {
        val (l1, _) = add(ShoppingList(), listOf("2 onions"))
        val first = l1.itemByKey("cat:onion")!!.id
        val l2 = l1.clearAll(7)
        val (l3, _) = add(l2, listOf("1 onion"))
        val l4 = l3.clearAll(7)
        val back = l4.undoDelete(7)
        assertEquals(1, back.liveItems.count { it.mergeKey == "cat:onion" })
        assertTrue(back.liveItems.single().id != first)
    }
}
