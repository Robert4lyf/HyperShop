package com.robert4lyf.hypershop.core.merge

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** Y.17: salted and unsalted butter are two items; plain "butter" is unsalted (the owner's default). */
class ButterTest {
    @Test
    fun saltedIsItsOwnItemAndPlainButterIsUnsalted() {
        val l = add(ShoppingList(), listOf("100g butter", "2 tbsp unsalted butter", "3 tbsp salted butter")).first
        val rows = l.liveItems.associate { it.mergeKey to it.displayName }
        assertEquals(mapOf("cat:butter" to "unsalted butter", "cat:salted_butter" to "salted butter"), rows)
    }
}
