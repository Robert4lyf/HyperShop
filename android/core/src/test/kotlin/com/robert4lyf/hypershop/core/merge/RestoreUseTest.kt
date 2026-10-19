package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** [ShoppingList.removeUseKeeping] and [ShoppingList.restoreUse]: "Remove from list" and its Undo. */
class RestoreUseTest {
    private fun count(n: Int) = QtyBase.of(Rational.of(n), null, UnitLocale.METRIC)

    @Test
    fun aPlainRestoreBringsBackTheUseItsItemsAndTheirTicks() {
        val (l0, useId) = add(ShoppingList(), listOf("2 onions", "1 carrot"))
        val l1 = l0.tick(l0.itemByKey("cat:carrot")!!.id, 5)!!
        val (gone, removed) = l1.removeUseKeeping(useId)
        assertTrue(gone.liveItems.isEmpty())
        assertTrue(gone.ticks.isEmpty())
        assertEquals(2, removed!!.items.size)
        val back = gone.restoreUse(removed)!!
        assertEquals(l1.shape(), back.shape())
        assertTrue(back.isDone(back.itemByKey("cat:carrot")!!.id))
        // Already back: nothing to do.
        assertNull(back.restoreUse(removed))
    }

    @Test
    fun aRestoredItemJoinsTheItemNowLiveAtItsKey() {
        val (l0, useId) = add(ShoppingList(), listOf("2 onions"))
        val (gone, removed) = l0.removeUseKeeping(useId)
        val (again, _) = add(gone, listOf("1 onion"))
        val onion = again.itemByKey("cat:onion")!!
        val back = again.restoreUse(removed!!)!!
        assertEquals(1, back.liveItems.size)
        assertEquals(onion.id, back.liveItems.single().id)
        assertEquals(Rational.of(3), back.total(onion.id)[BaseKey.ITEMS])
        assertEquals(2, back.contributionsOf(onion.id).size)
        assertTrue(back.liveItems.single().rev > onion.rev)
    }

    @Test
    fun aRestoredItemCanBeSwappedOntoAnotherAsACatalogueMoveWould() {
        // Restored under its old key, then merged into the item at the key the catalogue now gives it.
        val (l0, useId) = add(ShoppingList(), listOf("1 courgette"))
        val (gone, removed) = l0.removeUseKeeping(useId)
        val (withAubergine, _) = gone.manualAdd(merger.target("aubergine"), count(2), null, 7)
        val back = withAubergine.restoreUse(removed!!)!!
        val courgette = back.itemByKey("cat:courgette")!!
        val swapped = back.swapItem(courgette.id, merger.target("aubergine"))!!
        val aubergine = swapped.liveItems.single()
        assertEquals("cat:aubergine", aubergine.mergeKey)
        assertEquals(Rational.of(3), swapped.total(aubergine.id)[BaseKey.ITEMS])
        assertEquals(aubergine.id, swapped.resolve(courgette.id)?.id)
    }
}
