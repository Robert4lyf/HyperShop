package com.robert4lyf.hypershop.core.parse

import com.robert4lyf.hypershop.core.model.Rational
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class TokeniserTest {
    private fun kinds(s: String) = Tokeniser.tokenise(s).map { it.kind }

    private fun nums(s: String) = Tokeniser.tokenise(s).filter { it.kind == Kind.NUM }.map { it.value }

    @Test
    fun numbers() {
        assertEquals(listOf(Rational.of(5, 2)), nums("2½ cups"))
        assertEquals(listOf(Rational.of(2), Rational.of(1, 2)), nums("2 ½ cups"))
        assertEquals(listOf(Rational.of(1, 2)), nums("1/2 tsp"))
        assertEquals(listOf(Rational.of(1, 2)), nums("1 / 2 tsp"))
        assertEquals(listOf(Rational.of(3, 2)), nums("1.5 litres"))
        assertEquals(listOf(Rational.of(3, 2)), nums("1,5 l"))
        assertEquals(listOf(Rational.of(1000), Rational.of(500)), nums("1,000g or 500g"))
        assertEquals(listOf(Rational.of(1), Rational.of(1, 4)), nums("1-1/4 cups"))
    }

    @Test
    fun lettersSplitFromDigits() {
        val t = Tokeniser.tokenise("400g tin")
        assertEquals(listOf("400", "g", "tin"), t.map { it.text })
        assertEquals(listOf(Kind.NUM, Kind.WORD, Kind.WORD), t.map { it.kind })
    }

    @Test
    fun punctuationAndWords() {
        assertEquals(
            listOf(Kind.NUM, Kind.WORD, Kind.LPAREN, Kind.NUM, Kind.WORD, Kind.RPAREN, Kind.WORD, Kind.WORD, Kind.COMMA, Kind.WORD),
            kinds("2 cups (300g) plain flour, sifted"),
        )
        assertEquals(listOf("extra-virgin", "olive", "oil"), Tokeniser.tokenise("extra-virgin olive oil").map { it.text })
        assertEquals(listOf("confectioners'", "sugar"), Tokeniser.tokenise("confectioners' sugar").map { it.text })
        assertEquals(listOf(Kind.NUM, Kind.DASH, Kind.NUM), kinds("2–3"))
    }

    @Test
    fun spansIndexTheSource() {
        val s = "1 tbsp olive oil"
        for (t in Tokeniser.tokenise(s)) assertEquals(t.text, s.substring(t.start, t.end))
    }

    @Test
    fun hugeDigitRunsAreNotNumbers() {
        assertEquals(listOf(Kind.OTHER, Kind.WORD), kinds("12345678901234567890 g"))
    }

    @Test
    fun pathological10kLineIsLinear() {
        val lines =
            listOf(
                "1/".repeat(5000),
                "((((".repeat(2500),
                "a".repeat(10_000),
                "1 ".repeat(5000),
                "½-".repeat(5000),
                "a-".repeat(5000),
                "1.".repeat(5000),
            )
        repeat(3) { lines.forEach { Tokeniser.tokenise(it) } } // warm up
        for (line in lines) {
            val t0 = System.nanoTime()
            Tokeniser.tokenise(line)
            val ms = (System.nanoTime() - t0) / 1e6
            assertTrue(ms < 50, "tokenising a ${line.length}-char line took $ms ms")
        }
    }
}
