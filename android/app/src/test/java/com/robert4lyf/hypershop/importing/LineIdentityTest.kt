package com.robert4lyf.hypershop.importing

import com.robert4lyf.hypershop.core.model.ParsedIngredient
import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.model.Rational
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [LineIdentity.reconcile] on its own: which of Claude's lines the user's corrections stand over, and which they never touch. */
class LineIdentityTest {
    private fun line(
        raw: String,
        grams: Int?,
        section: String? = null,
        name: String = raw.substringAfter("g ").substringAfter(" "),
    ) = ParsedIngredient(raw, name, grams?.let { Rational.of(it.toLong()) }, unit = grams?.let { QtyUnit.G }, section = section)

    private val cakeSugar = line("100 g sugar", 100, "For the cake")
    private val flour = line("200 g flour", 200, "For the cake")
    private val icingSugar = line("100 g sugar", 100, "For the icing")
    private val before = listOf(cakeSugar, flour, icingSugar)
    private val origins = LineIdentity.originsOf(before)

    private fun fixed(vararg c: LineCorrection) = c.associateBy { it.origin }

    private fun grams(r: Reconciled) = r.lines.map { "${it.section?.removePrefix("For the ")}:${it.name}:${it.quantity}" }

    @Test
    fun identicalLinesAreTwoOccurrences() {
        assertEquals(listOf(LineOrigin("100 g sugar", 0), LineOrigin("200 g flour", 0), LineOrigin("100 g sugar", 1)), origins)
        // Case, spacing, bullets and a full stop don't make a different line.
        assertEquals(LineIdentity.normal("100 g sugar"), LineIdentity.normal("  • 100 g  Sugar. "))
    }

    @Test
    fun anEditToTheFirstOccurrenceStaysOnTheFirst() {
        val edited = cakeSugar.copy(quantity = Rational.of(150))
        val r = LineIdentity.reconcile(before, before, origins, fixed(LineCorrection(origins[0], edited)))
        assertEquals(listOf("cake:sugar:150", "cake:flour:200", "icing:sugar:100"), grams(r))
        assertEquals(origins, r.origins)
    }

    @Test
    fun anEditToTheSecondOccurrenceStaysOnTheSecond() {
        val edited = icingSugar.copy(quantity = Rational.of(80))
        val r = LineIdentity.reconcile(before, before, origins, fixed(LineCorrection(origins[2], edited)))
        assertEquals(listOf("cake:sugar:100", "cake:flour:200", "icing:sugar:80"), grams(r))
    }

    @Test
    fun removingOneOccurrenceLeavesTheOther() {
        val shown = listOf(flour, icingSugar)
        val r = LineIdentity.reconcile(before, shown, origins.drop(1), fixed(LineCorrection(origins[0], cakeSugar, removed = true)))
        assertEquals(listOf("cake:flour:200", "icing:sugar:100"), grams(r))
        val r2 =
            LineIdentity.reconcile(
                before,
                listOf(cakeSugar, flour),
                origins.take(2),
                fixed(LineCorrection(origins[2], icingSugar, removed = true)),
            )
        assertEquals(listOf("cake:sugar:100", "cake:flour:200"), grams(r2))
    }

    @Test
    fun bothEditedDifferently() {
        val r =
            LineIdentity.reconcile(
                before,
                before,
                origins,
                fixed(
                    LineCorrection(origins[0], cakeSugar.copy(quantity = Rational.of(150))),
                    LineCorrection(origins[2], icingSugar.copy(quantity = Rational.of(120))),
                ),
            )
        assertEquals(listOf("cake:sugar:150", "cake:flour:200", "icing:sugar:120"), grams(r))
    }

    @Test
    fun claudeReorderingIdenticalLinesFollowsTheirSections() {
        // Claude lists the icing first: the cake's correction still lands on the cake's line.
        val again = listOf(icingSugar.copy(name = "caster sugar"), flour, cakeSugar.copy(name = "caster sugar"))
        val r =
            LineIdentity.reconcile(
                again,
                before,
                origins,
                fixed(LineCorrection(origins[0], cakeSugar.copy(quantity = Rational.of(150)))),
            )
        assertEquals(listOf("icing:caster sugar:100", "cake:flour:200", "cake:sugar:150"), grams(r))
        assertEquals(listOf(origins[2], origins[1], origins[0]), r.origins)
    }

    @Test
    fun claudeReorderingDifferentLinesKeepsEachCorrectionOnItsOwn() {
        val again = listOf(flour.copy(name = "plain flour"), cakeSugar, icingSugar)
        val r = LineIdentity.reconcile(again, before, origins, fixed(LineCorrection(origins[1], flour.copy(quantity = Rational.of(250)))))
        assertEquals(listOf("cake:flour:250", "cake:sugar:100", "icing:sugar:100"), grams(r))
    }

    @Test
    fun anExtraLineFromClaudeIsANewLine() {
        val salt = line("pinch of salt", null, "For the cake", name = "salt")
        val again = listOf(cakeSugar, flour, salt, icingSugar)
        val r =
            LineIdentity.reconcile(
                again,
                before,
                origins,
                fixed(LineCorrection(origins[0], cakeSugar.copy(quantity = Rational.of(150)))),
            )
        assertEquals(listOf("cake:sugar:150", "cake:flour:200", "cake:salt:null", "icing:sugar:100"), grams(r))
        assertEquals(LineOrigin("pinch of salt", 0), r.origins[2])
        assertFalse(r.lines[2].check)
    }

    @Test
    fun anOmittedCorrectedLineStaysWhereItWasAndAnOmittedUntouchedOneGoes() {
        // Claude leaves out the flour (corrected) and the icing sugar (not).
        val r =
            LineIdentity.reconcile(
                listOf(cakeSugar),
                before,
                origins,
                fixed(LineCorrection(origins[1], flour.copy(quantity = Rational.of(250)))),
            )
        assertEquals(listOf("cake:sugar:100", "cake:flour:250"), grams(r))
    }

    @Test
    fun claudeCombiningTwoCorrectedDuplicatesChangesNothingAndMarksTheRest() {
        val three = before + line("100 g sugar", 100, "For the topping")
        val o3 = LineIdentity.originsOf(three)
        // Claude returns "100 g sugar" twice where the recipe has it three times: which two? Not guessed.
        val again = listOf(line("100 g sugar", 200, "For the cake"), flour, line("100 g sugar", 100, "For the icing"))
        val r = LineIdentity.reconcile(again, three, o3, fixed(LineCorrection(o3[0], cakeSugar.copy(quantity = Rational.of(150)))))
        assertEquals(listOf("cake:sugar:150", "icing:sugar:100", "topping:sugar:100", "cake:flour:200"), grams(r))
        assertFalse(r.lines[0].check)
        assertTrue(r.lines[1].check && r.lines[2].check)
        assertEquals(LineIdentity.CHECK_ALIGN, r.lines[1].checkReason)
    }

    @Test
    fun claudeReturningTwoIdenticalLinesForOneIsASplit() {
        val one = listOf(line("salt and pepper", null, name = "salt and pepper"))
        val o1 = LineIdentity.originsOf(one)
        val split = listOf(line("salt and pepper", null, name = "salt"), line("salt and pepper", null, name = "black pepper"))
        // Untouched: Claude's split stands, the second part a line of its own.
        val r = LineIdentity.reconcile(split, one, o1, emptyMap())
        assertEquals(listOf("salt", "black pepper"), r.lines.map { it.name })
        assertEquals(listOf(LineOrigin("salt and pepper", 0), LineOrigin("salt and pepper", 1)), r.origins)
        // Corrected: the user's line, once. Removed: neither part.
        val mine = one[0].copy(name = "sea salt")
        assertEquals(listOf("sea salt"), LineIdentity.reconcile(split, one, o1, fixed(LineCorrection(o1[0], mine))).lines.map { it.name })
        assertTrue(LineIdentity.reconcile(split, one, o1, fixed(LineCorrection(o1[0], one[0], removed = true))).lines.isEmpty())
    }

    @Test
    fun aRemovedLineClaudeReturnsAgainStaysRemovedAndALineTypedWithTheSameTextIsKept() {
        // The cake's sugar removed, then "100 g sugar" typed in again at the end: Claude reads all three source lines.
        val typed = line("100 g sugar", 100)
        val shown = listOf(flour, icingSugar, typed)
        val typedOrigin = LineIdentity.next("100 g sugar", origins)
        assertEquals(LineOrigin("100 g sugar", 2), typedOrigin)
        val r =
            LineIdentity.reconcile(
                before + typed,
                shown,
                origins.drop(1) + typedOrigin,
                fixed(LineCorrection(origins[0], cakeSugar, removed = true)),
            )
        assertEquals(listOf("cake:flour:200", "icing:sugar:100", "null:sugar:100"), grams(r))
    }

    @Test
    fun translatedLinesArePairedByPositionOnlyWhenTheCountsAgree() {
        val fr = listOf(line("100 g sucre", 100), line("200 g farine", 200))
        val ofr = LineIdentity.originsOf(fr)
        val en = listOf(line("100 g sugar", 100, name = "sugar"), line("200 g plain flour", 200, name = "plain flour"))
        val mine = fr[1].copy(quantity = Rational.of(250))
        val r = LineIdentity.reconcile(en, fr, ofr, fixed(LineCorrection(ofr[1], mine)))
        assertEquals(listOf("100 g sugar", "200 g farine"), r.lines.map { it.raw })
        assertEquals(Rational.of(250), r.lines[1].quantity)
        // One fewer line back: no pairing is safe, so the review's lines stand (the untouched one marked to
        // check) and Claude's are not added beside them, which could buy the corrected one twice.
        val short = LineIdentity.reconcile(en.take(1), fr, ofr, fixed(LineCorrection(ofr[1], mine)))
        assertEquals(listOf("100 g sucre", "200 g farine"), short.lines.map { it.raw })
        assertTrue(short.lines[0].check)
        assertFalse(short.lines[1].check)
    }

    @Test
    fun withNoCorrectionsClaudesReadingIsTakenWhole() {
        val again = listOf(cakeSugar.copy(name = "caster sugar"), icingSugar.copy(name = "icing sugar"))
        val r = LineIdentity.reconcile(again, before, origins, emptyMap())
        assertEquals(again, r.lines)
    }

    @Test
    fun aTranslationThatCannotBeLinedUpKeepsTheReviewNotBoth() {
        // Review fix: a French recipe, the flour corrected; Claude translates and splits "sel et poivre".
        val fr = listOf(line("200 g farine", 200, name = "farine"), line("sel et poivre", null, name = "sel et poivre"))
        val o = LineIdentity.originsOf(fr)
        val mine = fr[0].copy(name = "plain flour")
        val en =
            listOf(
                line("200 g flour", 200, name = "flour"),
                line("salt and pepper", null, name = "salt"),
                line("salt and pepper", null, name = "pepper"),
            )
        val r = LineIdentity.reconcile(en, fr, o, fixed(LineCorrection(o[0], mine)))
        // The corrected flour once (not 400 g of it), the other line as it was, marked to check.
        assertEquals(listOf("plain flour", "sel et poivre"), r.lines.map { it.name })
        assertEquals(o, r.origins)
        assertFalse(r.lines[0].check)
        assertTrue(r.lines[1].check)
    }

    @Test
    fun aRemovedLineDoesNotComeBackThroughATranslation() {
        val de = listOf(line("200 g Zucker", 200, name = "zucker"), line("2 Eier", null, name = "eier"))
        val o = LineIdentity.originsOf(de)
        val en = listOf(line("200 g sugar", 200, name = "sugar"), line("2 eggs", null, name = "eggs"))
        val r = LineIdentity.reconcile(en, de.take(1), o.take(1), fixed(LineCorrection(o[1], de[1], removed = true)))
        assertEquals(listOf("zucker"), r.lines.map { it.name })
        assertTrue(r.origins.none { it == o[1] })
    }

    @Test
    fun aSplitLineIsSentToClaudeOnce() {
        val split = listOf(line("salt and pepper", null, name = "salt"), line("salt and pepper", null, name = "pepper"), flour)
        // The flour sits under a heading: it goes before it, as the recipe had it.
        assertEquals(listOf("salt and pepper", "${flour.section}:", "200 g flour"), LineIdentity.sourceLines(split))
        // Two genuine lines with the same text in different places are both sent, each under its heading.
        assertEquals(
            listOf("For the cake:", "100 g sugar", "200 g flour", "For the icing:", "100 g sugar"),
            LineIdentity.sourceLines(before),
        )
    }

    private val sugar = line("100 g sugar", 100)
    private val salt = line("salt and pepper", null, name = "salt")
    private val pepper = line("salt and pepper", null, name = "black pepper")

    private fun numbered(vararg l: Pair<ParsedIngredient, Int>) = l.map { (p, n) -> p.copy(sourceLine = n) }

    @Test
    fun twoAdjacentIdenticalLinesAreTwoLinesWhereverTheyComeFrom() {
        // Numbered (read since lines carried their source): two numbers, two lines.
        assertEquals(listOf("100 g sugar", "100 g sugar"), LineIdentity.sourceLines(numbered(sugar to 0, sugar to 1)))
        // Stored before: same text, same ingredient, so two lines (a split's parts name different ones).
        assertEquals(listOf("100 g sugar", "100 g sugar"), LineIdentity.sourceLines(listOf(sugar, sugar)))
        // Different headings, side by side once the headings are gone.
        val sections = listOf(sugar.copy(section = "For the cake"), sugar.copy(section = "For the icing"))
        assertEquals(listOf("For the cake:", "100 g sugar", "For the icing:", "100 g sugar"), LineIdentity.sourceLines(sections))
    }

    @Test
    fun aSplitIsOneLineAndTwoIdenticalSplitsAreTwo() {
        assertEquals(listOf("salt and pepper"), LineIdentity.sourceLines(numbered(salt to 3, pepper to 3)))
        assertEquals(
            listOf("salt and pepper", "salt and pepper"),
            LineIdentity.sourceLines(numbered(salt to 0, pepper to 0, salt to 1, pepper to 1)),
        )
        // Stored before lines were numbered: a name coming round again is the next line.
        assertEquals(listOf("salt and pepper", "salt and pepper"), LineIdentity.sourceLines(listOf(salt, pepper, salt, pepper)))
        assertEquals(listOf("salt and pepper"), LineIdentity.sourceLines(listOf(salt, pepper)))
    }

    @Test
    fun aRuleParsedLineAndATypedOneKeepTheirOwnNumber() {
        val lines = com.robert4lyf.hypershop.core.parse.RuleParser().parseLines(listOf("For the cake:", "100 g sugar", "100 g sugar"))
        assertEquals(listOf(1, 2), lines.map { it.sourceLine })
        // Rereading numbers them by the list it makes, headings included.
        val (again, raw) = LineIdentity.reread(lines)
        assertEquals(listOf("For the cake:", "100 g sugar", "100 g sugar"), raw)
        assertEquals(listOf(1, 2), again.map { it.sourceLine })
    }

    @Test
    fun claudesReadingIsNumberedByTheLinesItWasSent() {
        val raw = listOf("For the cake:", "salt and pepper", "100 g sugar", "100 g sugar", "200 g flour")
        // Split, duplicated, and in another order.
        val said = listOf(flour, salt, pepper, sugar, sugar)
        assertEquals(listOf(4, 1, 1, 2, 3), LineIdentity.attach(said, raw).map { it.sourceLine })
        // Claude combined the two sugars into one: one source line, the other left unclaimed.
        assertEquals(listOf(1, 1, 2, 4), LineIdentity.attach(listOf(salt, pepper, sugar, flour), raw).map { it.sourceLine })
        // A line it rephrased matches none: no number, rather than a guess.
        assertEquals(listOf(null), LineIdentity.attach(listOf(line("caster sugar, 100g", 100)), raw).map { it.sourceLine })
        // Each of two identical lines split: two groups of two.
        val twice = listOf("salt and pepper", "salt and pepper")
        assertEquals(listOf(0, 0, 1, 1), LineIdentity.attach(listOf(salt, pepper, salt, pepper), twice).map { it.sourceLine })
        // And what it numbered goes back to Claude one line per line.
        assertEquals(twice, LineIdentity.sourceLines(LineIdentity.attach(listOf(salt, pepper, salt, pepper), twice)))
    }
}
