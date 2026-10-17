package com.robert4lyf.hypershop.core.model

import com.robert4lyf.hypershop.core.parse.RuleParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The amount an ingredient is shopped by: the recipe's own metric figure beats a cup conversion. */
class ShopAmountTest {
    private val rp = RuleParser()

    private fun ml(v: Int) = QtyBase.of(Rational.of(v), QtyUnit.ML, UnitLocale.METRIC)

    @Test
    fun aMetricAlternativeWinsOverCups() {
        val p =
            ParsedIngredient(
                "2 cups (500ml) milk",
                "milk",
                Rational.of(2),
                unit = QtyUnit.CUP,
                altQuantity = Rational.of(500),
                altUnit = QtyUnit.ML,
            )
        assertEquals(ml(500), p.shopBase(UnitLocale.US))
        assertEquals(QtyUnit.ML, p.shopUnit)
    }

    @Test
    fun aTrailingMetricAmountLeftInTheNoteCounts() {
        for (line in listOf("2 cups milk (500ml)", "2 cups tomato passata (500ml) (Note 1)")) {
            val p = rp.parse(line)
            assertEquals(ml(500), p.shopBase(UnitLocale.US), line)
        }
    }

    @Test
    fun rangesScaleTheAlternative() {
        val p =
            ParsedIngredient(
                "1-2 cups (250-500ml) stock",
                "stock",
                Rational.ONE,
                Rational.of(2),
                unit = QtyUnit.CUP,
                altQuantity = Rational.of(250),
                altUnit = QtyUnit.ML,
            )
        assertEquals(ml(500), p.shopBase(UnitLocale.US))
    }

    @Test
    fun containersAndMetricAmountsAreUnchanged() {
        val tin = rp.parse("1 (400g) tin chopped tomatoes")
        assertEquals(QtyBase.of(Rational.ONE, QtyUnit.TIN, UnitLocale.METRIC), tin.shopBase(UnitLocale.METRIC))
        val onion = rp.parse("1 onion (about 200 g)")
        assertEquals(QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), onion.shopBase(UnitLocale.METRIC))
        val cheese = rp.parse("1 cup shredded cheddar cheese")
        assertEquals(QtyBase.of(Rational.ONE, QtyUnit.CUP, UnitLocale.US), cheese.shopBase(UnitLocale.US))
    }
}
