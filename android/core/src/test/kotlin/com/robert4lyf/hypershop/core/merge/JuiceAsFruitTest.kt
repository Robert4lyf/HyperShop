package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.Rational
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** V.2: lemon and lime juice in a recipe go on the list as the fruit, unless the recipe says bottled. */
class JuiceAsFruitTest {
    private fun row(line: String) = recipe(listOf(line)).lines.single()

    private fun added(lines: List<String>): Map<String, Rational> {
        val (l, _) = add(ShoppingList(), lines)
        return l.liveItems.associate { it.mergeKey to l.total(it.id)[BaseKey.ITEMS] }
    }

    @Test
    fun aSpoonOfJuiceIsOneFruit() {
        val lime = row("2 tbsp lime juice")
        assertEquals("lime", lime.parsed.name)
        assertEquals(Rational.ONE, lime.parsed.quantity)
        assertEquals(null, lime.parsed.unit)
        assertEquals("cat:lime", lime.target.mergeKey)
        assertEquals("for 2 tbsp juice", lime.parsed.note)
        assertEquals("lemon", row("3 tbsp lemon juice").parsed.name)
        assertEquals(Rational.ONE, row("3 tbsp lemon juice").parsed.quantity)
    }

    @Test
    fun moreJuiceIsMoreFruitRoundedUp() {
        // A lemon gives about 3 tbsp, a lime about 2.
        assertEquals(Rational.of(2), row("4 tbsp lemon juice").parsed.quantity)
        assertEquals(Rational.of(4), row("100ml lime juice").parsed.quantity)
        assertEquals(Rational.of(2), row("1/4 cup lime juice").parsed.quantity)
    }

    @Test
    fun juiceWithNoAmountIsOneFruitAndJoinsTheFruitRow() {
        assertEquals(Rational.ONE, row("lemon juice, to taste").parsed.quantity)
        // The juice and a whole lemon from the same recipe are one row.
        assertEquals(Rational.of(2), added(listOf("1 lemon, zested", "2 tbsp lemon juice"))["cat:lemon"])
    }

    @Test
    fun bottledJuiceStaysJuice() {
        assertEquals("cat:lime_juice", row("2 tbsp bottled lime juice").target.mergeKey)
        assertEquals("cat:lemon_juice", row("1 tbsp lemon juice (from a bottle)").target.mergeKey)
    }

    @Test
    fun fruitAlreadyCountedIsLeftAlone() {
        // "Juice of 2 lemons" is already counted as lemons.
        val l = row("juice of 2 lemons")
        assertEquals("cat:lemon", l.target.mergeKey)
        assertEquals(Rational.of(2), l.parsed.quantity)
        // Wedges to serve, no amount: not turned into "1 lemon".
        assertEquals(null, row("lemon wedges, to serve").parsed.quantity)
    }
}
