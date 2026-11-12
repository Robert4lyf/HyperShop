package com.robert4lyf.hypershop.core.parse

import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import com.robert4lyf.hypershop.core.model.ParsedIngredient
import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.model.Rational
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Readings that keep the catalogue item and the arithmetic right but would lose what the line means (November
 * evaluation): a part beside the whole, and a heaped measure. Neighbouring lines that mean exactly what they say
 * stay unflagged.
 */
class MeaningTest {
    private val p = RuleParser(CatalogLoader.default)

    private fun flaggedFor(
        line: String,
        reason: String,
    ): ParsedIngredient {
        val r = p.parse(line)
        assertTrue(r.check, "$line should ask for a look")
        assertEquals(reason, r.checkReason, line)
        return r
    }

    @Test
    fun anEggPartBesideWholeEggsIsCountedToBuyButFlaggedWithThePartKept() {
        val a = flaggedFor("1 large egg plus 1 egg yolk", Meaning.PART)
        assertEquals(Rational.of(2), a.quantity, "a yolk is still an egg to buy")
        assertTrue(a.note!!.contains("egg yolk"), "the part stays with the line: ${a.note}")
        val b = flaggedFor("1 large egg, plus 1 egg yolk", Meaning.PART)
        assertEquals(Rational.of(2), b.quantity)
        val c = flaggedFor("2 eggs plus 2 egg whites", Meaning.PART)
        assertEquals(Rational.of(4), c.quantity)
        assertTrue(c.note!!.contains("whites"))
        val d = flaggedFor("1 egg, plus 1 extra yolk", Meaning.PART)
        assertTrue(d.note!!.contains("yolk"))
        // The other way round: yolks first, then a whole egg.
        flaggedFor("2 egg yolks plus 1 egg", Meaning.PART)
    }

    @Test
    fun wholeEggsOrPartsOnTheirOwnMeanWhatTheySay() {
        for ((line, name) in listOf("1 egg plus 1 egg" to "egg", "2 egg yolks" to "egg yolks", "3 egg whites" to "egg whites")) {
            val r = p.parse(line)
            assertFalse(r.check, "$line: ${r.checkReason}")
            assertEquals(name, r.name, "the part stays in the name")
        }
        assertEquals(Rational.of(2), p.parse("1 egg plus 1 egg").quantity)
        // Same part both sides: plain arithmetic.
        val y = p.parse("2 egg yolks plus 1 egg yolk")
        assertFalse(y.check, y.checkReason)
        assertEquals(Rational.of(3), y.quantity)
    }

    @Test
    fun aWhiteThatIsNotAnEggPartIsLeftAlone() {
        val s = p.parse("100g white sugar plus 50g white sugar")
        assertFalse(s.check, s.checkReason)
        assertEquals(Rational.of(150), s.quantity)
    }

    @Test
    fun aHeapedSpoonOrCupIsFlaggedAtTheLevelAmountWithTheQualifierKept() {
        for (line in listOf(
            "2 heaped tablespoons peanut butter",
            "1 heaped tsp sugar",
            "a heaped tablespoon of flour",
            "2 rounded tbsp cocoa",
            "1 heaping cup flour",
            "1 generous tbsp honey",
            "4 tbsp flour, heaped",
        )) {
            val r = flaggedFor(line, Meaning.HEAPED)
            assertTrue(r.note.orEmpty().split(", ").any { it in Meaning.MORE_THAN_LEVEL }, "$line keeps the qualifier: ${r.note}")
        }
        val pb = p.parse("2 heaped tablespoons peanut butter")
        assertEquals(Rational.of(2), pb.quantity, "no invented conversion")
        assertEquals(QtyUnit.TBSP, pb.unit)
    }

    @Test
    fun levelOrScantMeasuresAndPlainArithmeticAreNotFlagged() {
        for (line in listOf(
            "1 level tsp baking powder",
            "1 scant cup milk",
            "2 tbsp plus 1 tsp oil",
            "1 tbsp plus 1 tsp olive oil",
            "400 g plus 0.1 kg plain flour",
            "250 ml milk plus 1/4 l milk",
            "a generous pinch of salt",
            "200 g rounded grain rice",
        )) {
            val r = p.parse(line)
            assertFalse(r.check && r.checkReason in Meaning.REASONS, "$line: ${r.checkReason}")
        }
    }

    @Test
    fun claudeReadingsKeepTheRuleParsersMeaningFlags() {
        val align = ClaudeAlignment()
        val claude =
            ParsedIngredient(raw = "1 large egg plus 1 egg yolk", name = "egg", quantity = Rational.of(2), unit = null)
        val aligned = align.align(claude)
        assertTrue(aligned.check)
        assertEquals(Meaning.PART, aligned.checkReason)
        val heaped =
            ParsedIngredient(
                raw = "2 heaped tablespoons peanut butter",
                name = "peanut butter",
                quantity = Rational.of(2),
                unit = QtyUnit.TBSP,
            )
        assertEquals(Meaning.HEAPED, align.align(heaped).checkReason)
        // A line with no such meaning is untouched.
        val plain = ParsedIngredient(raw = "2 tbsp peanut butter", name = "peanut butter", quantity = Rational.of(2), unit = QtyUnit.TBSP)
        assertFalse(align.align(plain).check)
    }
}
