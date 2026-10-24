package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.model.Rational
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** V.4: produce a recipe counts and weighs is bought by the weight; anything else keeps its count. */
class ProduceByWeightTest {
    private fun row(line: String) = recipe(listOf(line)).lines.single().parsed

    @Test
    fun countedProduceWithGramsIsBoughtByTheGram() {
        val t = row("2 (560g) tomatoes, diced")
        assertEquals(Rational.of(560), t.quantity)
        assertEquals(QtyUnit.G, t.unit)
        assertEquals("2 in the recipe, diced", t.note)
        assertEquals(Rational.of(300), row("2 (300g) cucumbers").quantity)
    }

    @Test
    fun eggsAndMeatKeepTheirCount() {
        assertEquals(Rational.of(2), row("2 (100g) eggs").quantity)
        assertEquals(null, row("2 (100g) eggs").unit)
        assertEquals(Rational.of(4), row("4 (800g) chicken breasts").quantity)
    }

    @Test
    fun produceWithNoGramsKeepsItsCount() {
        assertEquals(Rational.of(2), row("2 tomatoes, diced").quantity)
        assertEquals(null, row("2 tomatoes, diced").unit)
    }
}
