package com.robert4lyf.hypershop.core.model

import io.kotest.property.Arb
import io.kotest.property.arbitrary.long
import io.kotest.property.checkAll
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class RationalTest {
    @Test
    fun thirdsAddUpToOne() {
        val third = Rational.of(1, 3)
        assertEquals(Rational.ONE, third + third + third)
    }

    @Test
    fun normalises() {
        assertEquals(Rational.of(1, 2), Rational.of(-2, -4))
        assertEquals("-1/2", Rational.of(1, -2).toString())
        assertEquals(Rational.ZERO, Rational.of(0, 7))
    }

    @Test
    fun fromDoublePicksCookeryFractions() {
        assertEquals(Rational.of(1, 3), Rational.fromDouble(0.3333333))
        assertEquals(Rational.of(5, 2), Rational.fromDouble(2.5))
        assertEquals(Rational.of(333, 1000), Rational.fromDouble(0.333))
        assertEquals(Rational.of(1, 8), Rational.fromDouble(0.125))
    }

    @Test
    fun parseAndSerialise() {
        assertEquals(Rational.of(3, 4), Rational.parse("3/4"))
        assertEquals(Rational.of(7), Rational.parse("7"))
        val json = Json.encodeToString(Rational.serializer(), Rational.of(5, 2))
        assertEquals("\"5/2\"", json)
        assertEquals(Rational.of(5, 2), Json.decodeFromString(Rational.serializer(), json))
        assertThrows<IllegalArgumentException> { Rational.of(1, 0) }
    }

    @Test
    fun arithmeticLaws() {
        runBlocking {
            val small = Arb.long(-500L..500L)
            val pos = Arb.long(1L..60L)
            checkAll(small, pos, small, pos) { a, b, c, d ->
                val x = Rational.of(a, b)
                val y = Rational.of(c, d)
                assertEquals(x + y, y + x)
                assertEquals(x, x + y - y)
                assertEquals(x * y, y * x)
                if (!y.isZero) assertEquals(x, x * y / y)
                assertEquals(x.compareTo(y), x.toDouble().compareTo(y.toDouble()).let { if (x == y) 0 else it })
            }
        }
    }
}

class UnitsTest {
    @Test
    fun cupDependsOnLocale() {
        assertEquals(Rational.of(240), QtyUnit.CUP.baseFactor(UnitLocale.US))
        assertEquals(Rational.of(250), QtyUnit.CUP.baseFactor(UnitLocale.METRIC))
        assertEquals(Rational.of(568), QtyUnit.PINT.baseFactor(UnitLocale.UK))
        assertEquals(Rational.of(473), QtyUnit.PINT.baseFactor(UnitLocale.US))
        assertNull(QtyUnit.CLOVE.baseFactor(UnitLocale.US))
    }

    @Test
    fun serialNamesRoundTrip() {
        for (u in QtyUnit.entries) {
            assertEquals(u, QtyUnit.fromSerial(u.serial))
            assertEquals("\"${u.serial}\"", Json.encodeToString(QtyUnit.serializer(), u))
        }
    }

    @Test
    fun aisleOrderIsTheSpecOrder() {
        assertEquals(AisleCode.PRODUCE, AisleCode.entries.first())
        assertEquals(AisleCode.OTHER, AisleCode.entries.last())
        assertEquals(17, AisleCode.entries.size)
    }
}

class QtyBaseTest {
    @Test
    fun convertsToBase() {
        val q = QtyBase.of(Rational.of(5, 2), QtyUnit.CUP, UnitLocale.US)
        assertEquals(Rational.of(600), q[BaseKey.VOLUME])
        val g = QtyBase.of(Rational.of(2), QtyUnit.KG, UnitLocale.METRIC)
        assertEquals(Rational.of(2000), g[BaseKey.MASS])
        val cloves = QtyBase.of(Rational.of(3), QtyUnit.CLOVE, UnitLocale.US)
        assertEquals(Rational.of(3), cloves[BaseKey(Dimension.COUNT, QtyUnit.CLOVE)])
        val onions = QtyBase.of(Rational.of(2), null, UnitLocale.US)
        assertEquals(Rational.of(2), onions[BaseKey.ITEMS])
        assertTrue(QtyBase.of(null, QtyUnit.G, UnitLocale.US).isPresence)
    }

    @Test
    fun addsAndSubtractsPerDimension() {
        val a = QtyBase.of(Rational.of(1), null, UnitLocale.US) + QtyBase.of(Rational.of(200), QtyUnit.G, UnitLocale.US)
        assertEquals(setOf(BaseKey.ITEMS, BaseKey.MASS), a.keys.toSet())
        val b = a.minusClamped(QtyBase.of(Rational.of(500), QtyUnit.G, UnitLocale.US))
        assertEquals(listOf(BaseKey.ITEMS), b.keys)
        assertEquals(Rational.of(1), b[BaseKey.ITEMS])
    }

    @Test
    fun baseKeyRoundTrip() {
        for (k in listOf(BaseKey.MASS, BaseKey.VOLUME, BaseKey.ITEMS, BaseKey(Dimension.COUNT, QtyUnit.TIN))) {
            assertEquals(k, BaseKey.decode(k.encode()))
        }
    }
}

class ParsedIngredientTest {
    @Test
    fun derivedFlags() {
        val salt = ParsedIngredient(raw = "Salt, to taste", name = "salt")
        assertTrue(salt.noScale)
        val parsley = ParsedIngredient(raw = "2 tbsp parsley, to serve", name = "parsley", quantity = Rational.of(2), unit = QtyUnit.TBSP)
        assertTrue(parsley.garnish)
        assertFalse(parsley.noScale)
    }
}

class CoverageTest {
    private fun c(
        id: Long,
        covered: Long? = null,
    ) = ContributionState(id, 1, QtyBase.of(Rational.ONE, null, UnitLocale.US), 1, covered)

    @Test
    fun doneOnlyWhenAllCovered() {
        assertFalse(Coverage.isDone(emptyList()))
        assertFalse(Coverage.isDone(listOf(c(1, 9), c(2))))
        assertTrue(Coverage.isPartial(listOf(c(1, 9), c(2))))
        assertTrue(Coverage.isDone(listOf(c(1, 9), c(2, 9))))
        assertEquals(Rational.ONE, Coverage.uncovered(listOf(c(1, 9), c(2)))[BaseKey.ITEMS])
    }
}
