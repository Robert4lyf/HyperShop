package com.robert4lyf.hypershop.claude

import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.parse.RuleParser
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** T.5d: Claude's answers are shelved by the rule parser's own tin and mince rule (KNOWN_ISSUES #29). */
class PostProcessTest {
    private fun read(
        name: String,
        unit: String?,
        raw: String = name,
        quantity: Double? = 1.0,
    ) = PostProcess.ingredient(
        ClaudeIngredient(
            raw = raw,
            name = name,
            quantity = quantity,
            quantityMax = null,
            quantityText = null,
            unit = unit,
            altQuantity = null,
            altUnit = null,
            note = null,
            section = null,
            optional = false,
            estimated = false,
            aisleSuggestion = null,
        ),
    )

    @Test
    fun aTinOfSomethingKnownFreshIsItsTinnedItem() {
        assertEquals("tinned tomatoes", read("tomatoes", "tin").name)
        assertEquals(QtyUnit.TIN, read("tomatoes", "tin").unit)
        assertEquals("tomatoes", read("tomatoes", "g").name, "fresh, by weight")
    }

    @Test
    fun cannedOrMincedWordsOnTheNameShelveIt() {
        assertEquals(RuleParser().parse("400g canned tomatoes").name, read("canned tomatoes", "g").name)
        assertEquals(RuleParser().parse("500g minced lamb").name, read("minced lamb", "g").name)
    }

    @Test
    fun aNameTheCatalogueAlreadyFilesIsLeftAsWritten() {
        assertEquals("chopped tomatoes", read("chopped tomatoes", "tin").name)
        assertEquals("beef mince", read("beef mince", "g").name)
        assertEquals("coconut milk", read("coconut milk", "tin").name)
        // Nothing better to file it as: Claude's name stands.
        assertEquals("canned salmon", read("canned salmon", "tin").name)
        assertEquals("salmon", read("salmon", "tin").name)
    }

    @Test
    fun aMetricAmountBeforeATinIsTheTinsSize() {
        // U.1: R3.4, as the rule parser reads it — 1 tin of 400 g, not 400 g.
        val t = read("chopped tomatoes", "g", raw = "400g tin chopped tomatoes", quantity = 400.0)
        assertEquals(QtyUnit.TIN, t.unit)
        assertEquals(1.0, t.quantity!!.toDouble())
        assertEquals(400.0, t.altQuantity!!.toDouble())
        assertEquals(QtyUnit.G, t.altUnit)
        assertEquals(false, t.check)
        val p = read("lamb mince", "g", raw = "500g pack lamb mince", quantity = 500.0)
        assertEquals(QtyUnit.PACKET, p.unit)
    }

    @Test
    fun aPinchIsOneAndJuiceIsJuice() {
        assertEquals(1.0, read("nutmeg", "pinch", raw = "Pinch of freshly ground nutmeg", quantity = null).quantity!!.toDouble())
        val j = read("lemon", "tbsp", raw = "2 tablespoons fresh lemon juice, (from 1 lemon)", quantity = 2.0)
        assertEquals("lemon juice", j.name)
        // Juice of a counted lemon is lemons: the amounts differ, so the name stands.
        assertEquals("lemon", read("lemon", null, raw = "juice of 1 lemon", quantity = 1.0).name)
    }

    @Test
    fun anAmountTheRuleParserReadsDifferentlyIsFlagged() {
        // "2 tbsp butter plus 1 tbsp melted": Claude said 2, the rules 3.
        val b = read("butter", "tbsp", raw = "2 tbsp butter plus 1 tbsp melted", quantity = 2.0)
        assertEquals(true, b.check)
        assertEquals(com.robert4lyf.hypershop.core.parse.ClaudeAlignment.DISAGREE, b.checkReason)
        // The same amount, however named: no flag.
        assertEquals(false, read("butter", "tbsp", raw = "3 tbsp butter", quantity = 3.0).check)
    }
}
