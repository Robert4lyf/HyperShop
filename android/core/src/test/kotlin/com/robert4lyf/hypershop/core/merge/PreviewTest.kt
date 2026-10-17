package com.robert4lyf.hypershop.core.merge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class PreviewTest {
    @Test
    fun revCheckOnConfirm() {
        val (l, _) = add(ShoppingList(), listOf("2 onions"))
        val p = merger.preview(l, recipe(listOf("1 onion", "1 carrot")), emptyMap())
        val ticked = l.tick(l.itemByKey("cat:onion")!!.id, 9)!!
        assertEquals(ApplyResult.Stale, merger.apply(ticked, p, 10))
        // An item that appeared since the preview is a change too.
        val (withCarrot, _) = add(l, listOf("1 carrot"))
        assertEquals(ApplyResult.Stale, merger.apply(withCarrot, p, 10))
        assertTrue(merger.apply(l, p, 10) is ApplyResult.Applied)
    }
}
