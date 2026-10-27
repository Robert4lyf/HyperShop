package com.robert4lyf.hypershop.core.parse

import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import com.robert4lyf.hypershop.core.merge.Merger
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** X.6: names from fresh UK and US recipe pages (catalog/SOURCES.md › X.6), and the "dried" rule. */
class UkNamesTest {
    private val cat = CatalogLoader.default
    private val parser = RuleParser(cat)
    private val merger = Merger(cat)

    private fun key(line: String) = merger.target(parser.parse(line).name).mergeKey

    @Test
    fun driedPastaIsPastaButDriedHerbsAreNotFresh() {
        assertEquals("cat:spaghetti", key("300g dried spaghetti"))
        assertEquals("cat:egg_noodles", key("200g dried egg noodles"))
        // No "dried mint" in the catalogue: it must not become a pack of fresh mint.
        assertEquals(false, key("2 tsp dried mint") == "cat:mint")
        // Nor a tin: dried chickpeas are not tinned chickpeas.
        assertEquals(false, key("250g dried chickpeas") == "cat:chickpeas")
        // A "dried …" the catalogue has is still itself.
        assertEquals("cat:dried_oregano", key("1 tsp dried oregano"))
    }

    @Test
    fun ukLabelsFromFreshPages() {
        assertEquals("cat:potato", key("1kg maris piper potatoes"))
        assertEquals("cat:tinned_potatoes", key("2 x 567g tins potatoes"))
        assertEquals("cat:stock_cube", key("1 chicken stock pot"))
        assertEquals("cat:garlic_puree", key("1 tbsp garlic purée"))
        assertEquals("cat:tipo_00_flour", key("200g 00 flour"))
    }
}
