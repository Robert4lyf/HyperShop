package com.robert4lyf.hypershop.core.merge

import io.kotest.property.Arb
import io.kotest.property.arbitrary.element
import io.kotest.property.arbitrary.int
import io.kotest.property.arbitrary.list
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TickingPropertyTest {
    private val recipes = Arb.list(Arb.element(LINES), 1..8)

    @Test
    fun aTickAlwaysMakesTheItemDone() {
        runBlocking {
            checkAll(recipes, recipes, Arb.int(0..20)) { a, b, pick ->
                var (l, _) = add(ShoppingList(), a)
                l = add(l, b).first
                val item = l.liveItems[pick % l.liveItems.size]
                l = l.tick(item.id, 5)!!
                assertTrue(l.isDone(item.id))
                assertNull(l.tick(item.id, 6)) // nothing left to cover
            }
        }
    }

    @Test
    fun aRecipeAddedAfterATickIsUncovered() {
        runBlocking {
            checkAll(recipes, Arb.int(0..20)) { a, pick ->
                var (l, _) = add(ShoppingList(), a)
                val item = l.liveItems[pick % l.liveItems.size]
                l = l.tick(item.id, 5)!!
                val (l2, use2) = add(l, listOf(item.displayName))
                val again = l2.itemByKey(item.mergeKey)!!
                val newOnes = l2.contributions.filter { it.useId == use2 && it.itemId == again.id }
                assertTrue(newOnes.isNotEmpty())
                assertTrue(newOnes.all { it.coveredBy == null })
                assertFalse(l2.isDone(again.id))
            }
        }
    }

    @Test
    fun anUntickRestoresExactlyTheStateBeforeTheTick() {
        runBlocking {
            checkAll(recipes, recipes, Arb.int(0..20)) { a, b, pick ->
                var (l, _) = add(ShoppingList(), a)
                val first = l.liveItems[pick % l.liveItems.size]
                l = l.tick(first.id, 5)!!
                l = add(l, b).first
                val item = l.liveItems[(pick + 1) % l.liveItems.size]
                val before = l.tick(item.id, 7)?.let { l } ?: return@checkAll
                val after = before.tick(item.id, 7)!!.untick(item.id)!!
                assertEquals(before.shape(), after.shape())
            }
        }
    }
}
