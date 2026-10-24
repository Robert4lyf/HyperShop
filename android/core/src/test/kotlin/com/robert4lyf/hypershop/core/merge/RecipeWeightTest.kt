package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.model.Rational
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * V.4: a page's own weight for a counted line ("2 (560g) tomatoes", from its unit switch) is kept with the
 * line, but the list still counts what the recipe counts: tomatoes are bought by the each as often as by
 * the gram.
 */
class RecipeWeightTest {
    @Test
    fun countedProduceKeepsItsCountAndCarriesItsWeight() {
        val t = recipe(listOf("2 (560g) tomatoes, diced")).lines.single()
        assertEquals("cat:tomato", t.target.mergeKey)
        assertEquals(Rational.of(2), t.parsed.quantity)
        assertEquals(null, t.parsed.unit)
        assertEquals(Rational.of(560), t.parsed.altQuantity)
        assertEquals(QtyUnit.G, t.parsed.altUnit)
    }
}
