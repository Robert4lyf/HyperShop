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
    ) = PostProcess.ingredient(
        ClaudeIngredient(
            raw = raw,
            name = name,
            quantity = 1.0,
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
}
