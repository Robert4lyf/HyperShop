package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.model.AisleCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class TickingTest {
    @Test
    fun finishShoppingSoftDeletesDoneItemsAndUndoes() {
        var (l, _) = add(ShoppingList(), listOf("2 onions", "1 carrot"))
        l = l.tick(l.itemByKey("cat:onion")!!.id, 5)!!
        val finished = l.finishShopping(now = 100)
        assertEquals(listOf("cat:carrot"), finished.liveItems.map { it.mergeKey })
        assertEquals(l.shape(), finished.undoDelete(100).shape())
    }

    @Test
    fun revsAndListRevBump() {
        val (l, _) = add(ShoppingList(), listOf("2 onions", "1 carrot"))
        val onion = l.itemByKey("cat:onion")!!
        val carrot = l.itemByKey("cat:carrot")!!
        val t = l.tick(onion.id, 5)!!
        assertEquals(l.listRev + 1, t.listRev)
        assertEquals(onion.rev + 1, t.items.getValue(onion.id).rev)
        assertEquals(carrot.rev, t.items.getValue(carrot.id).rev)
        val moved = t.moveAisle(carrot.id, AisleCode.FROZEN)
        assertEquals(carrot.rev + 1, moved.items.getValue(carrot.id).rev)
    }
}
