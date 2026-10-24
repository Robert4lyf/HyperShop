package com.robert4lyf.hypershop.core.parse

import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import com.robert4lyf.hypershop.core.merge.Merger
import com.robert4lyf.hypershop.core.model.AisleCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The quality pass's adversarial review (docs/reports/quality-pass.md § 8): the Q.3
 * rules for tins, mince and slashes, held to what they may and may not touch.
 */
class ShelfAndSlashTest {
    private val parser = RuleParser()
    private val merger = Merger(CatalogLoader.default, Normaliser())

    private fun key(line: String) = merger.target(parser.parse(line).name).mergeKey

    private fun aisle(line: String) = merger.target(parser.parse(line).name).aisle

    @Test
    fun aTinOfSomethingTheCatalogueOnlyKnowsFreshStaysATin() {
        // Before the review these became fresh salmon, fresh peaches, fresh pineapple, fresh crab.
        for (line in listOf("200g tinned salmon", "400g tinned peaches", "canned pineapple", "200g canned crab meat")) {
            val name = parser.parse(line).name
            assertTrue(name.startsWith("tinned ") || name.startsWith("canned "), "$line → $name")
            assertTrue(key(line).startsWith("name:"), "$line → ${key(line)}")
        }
        // Tinned tomatoes, which the catalogue has, are still found; and chickpeas are already a tin.
        assertEquals("cat:chopped_tomatoes", key("800g / 28 oz  canned crushed tomato"))
        assertEquals("cat:chickpeas", key("400g tin chickpeas"))
    }

    @Test
    fun onlyWhatIsSaidAboutTheNameMovesIt() {
        // An alternative in brackets or a comma's afterthought is not the product.
        assertEquals("cat:tomato", key("4 ripe tomatoes (or 400g tinned)"))
        assertEquals(AisleCode.PRODUCE, aisle("4 ripe tomatoes (or 400g tinned)"))
        assertEquals("cat:lamb", key("800g lamb, cubed and tossed with 1 tsp ground cumin"))
        assertEquals("cat:beef", key("600g beef, sliced, with minced garlic"))
        // What the name's own brackets say still counts.
        assertEquals("cat:beef_mince", key("Beef (minced, coarsely ground and mixed) 10.6 oz"))
        assertEquals("cat:pork_mince", key("500g pork (minced)"))
        assertEquals("cat:garlic", key("2 garlic cloves, minced"))
    }

    @Test
    fun aSlashBetweenTwoProductsIsAChoiceAndFlagged() {
        val choices =
            listOf(
                "100g milk/dark chocolate",
                "2 tbsp lemon/lime juice",
                "salt/pepper",
                "1/2 cup sugar/honey",
                "1 lb ground beef/pork",
            )
        for (line in choices) {
            val p = parser.parse(line)
            assertTrue(p.check, "$line should be flagged: ${p.name}")
            assertTrue(p.note.orEmpty().contains("or "), "$line keeps the other side: ${p.note}")
        }
        // Two names for one thing: the first, nothing to decide.
        val flax = parser.parse("2 dl flax seed/linseeds")
        assertFalse(flax.check)
        assertEquals("cat:flaxseed", merger.target(flax.name).mergeKey)
        // W.3: "cooking salt" is the catalogue's salt now (a UK label, from a fresh page), so this is one thing too.
        assertFalse(parser.parse("1/2 tsp cooking salt / kosher salt").check)
    }

    @Test
    fun onlyFootnoteMarksLeaveTheName() {
        assertEquals("cat:brown_rice", key("1 cup long- or short-grain brown rice*"))
        assertTrue(parser.parse("1 cup 2% milk").name.contains("%"), parser.parse("1 cup 2% milk").name)
        assertTrue(parser.parse("salt & pepper").name.contains("&"), parser.parse("salt & pepper").name)
    }
}
