package com.robert4lyf.hypershop.core.parse

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test

class NormaliserTest {
    private val n = Normaliser()

    @Test
    fun synonymsMapToOneCatalogueId() {
        assertEquals("cat:aubergine", n.mergeKey("eggplant"))
        assertEquals("cat:aubergine", n.mergeKey("Aubergines"))
        assertEquals("cat:double_cream", n.mergeKey("heavy cream"))
        assertEquals("cat:spring_onion", n.mergeKey("scallions"))
        assertEquals("cat:salt", n.mergeKey("kosher salt"))
        assertEquals("cat:black_pepper", n.mergeKey("freshly ground black pepper"))
    }

    @Test
    fun singularOnlyWhenCatalogued() {
        assertEquals("tomato", n.normName("tomatoes"))
        assertEquals("egg", n.normName("eggs"))
        assertEquals("cat:egg", n.mergeKey("eggs"))
        // Not in the catalogue: kept as written, keyed by name.
        assertEquals("name:dragon fruits", n.mergeKey("Dragon Fruits"))
    }

    @Test
    fun identityWordsKeepItemsApart() {
        assertNotEquals(n.mergeKey("onion"), n.mergeKey("red onion"))
        assertNotEquals(n.mergeKey("butter"), n.mergeKey("peanut butter"))
        assertNotEquals(n.mergeKey("paprika"), n.mergeKey("smoked paprika"))
    }

    @Test
    fun ruleParserOutputGetsTheSameKeyAsClaudeWould() {
        val p = RuleParser()
        assertEquals(n.mergeKey("aubergine"), n.mergeKey(p.parse("1 eggplant, cubed").name))
        assertEquals(n.mergeKey("garlic"), n.mergeKey(p.parse("2 garlic cloves, crushed").name))
    }

    @Test
    fun vesPluralsOnlyBecomeFWhenTheyShould() {
        assertEquals("garlic clove", Normaliser.singularKey("garlic cloves"))
        assertEquals("olive", Normaliser.singularKey("olives"))
        assertEquals("chive", Normaliser.singularKey("chives"))
        assertEquals("loaf", Normaliser.singularKey("loaves"))
        assertEquals("half", Normaliser.singularKey("halves"))
        assertEquals("cat:garlic", n.mergeKey("garlic cloves"))
        assertEquals("cat:olive", n.mergeKey("olives"))
        assertEquals("cat:chives", n.mergeKey("chives"))
    }

    @Test
    fun blankNamesDoNotShareAKey() {
        assertNotEquals(n.mergeKey("", "1/0 cup"), n.mergeKey("", "2 x"))
        assertNotEquals("name:", n.mergeKey(""))
        assertNotEquals(n.mergeKey("", "(((("), n.mergeKey("", "))))"))
        assertEquals("name:dragon fruit", n.mergeKey("dragon fruit", "2 dragon fruit"))
    }

    @Test
    fun singularKey() {
        assertEquals("cherry", Normaliser.singularKey("cherries"))
        assertEquals("potato", Normaliser.singularKey("potatoes"))
        assertEquals("leaf", Normaliser.singularKey("leaves"))
        assertEquals("glass", Normaliser.singularKey("glass"))
        assertEquals("couscous", Normaliser.singularKey("couscous"))
    }
}
