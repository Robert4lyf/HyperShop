package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/** The recipe search's cheap lookup files every name exactly where the full [Merger.target] does. */
class CatalogEntryTest {
    @Test
    fun theSearchLookupAgreesWithTheFullTarget() {
        val m = Merger()
        val catalog = CatalogLoader.default
        val names =
            catalog.byId.values.flatMap { listOf(it.name, it.plural) + it.synonyms } +
                listOf("2 onions", "fresh coriander", "ripe avocados", "smoked paprika", "unicorn tears", "", "Crème fraîche")
        for (n in names) assertEquals(m.target(n).catalogId, m.catalogEntry(n)?.id, n)
    }

    @Test
    fun alwaysUseInsteadIsFollowedTheSameWay() {
        val catalog = CatalogLoader.default.copy()
        catalog.replace("test", catalog.entries.map { if (it.id == "agave_syrup") it.copy(replaceWith = "maple_syrup") else it })
        val m = Merger(catalog)
        assertEquals("maple_syrup", m.catalogEntry("agave nectar")?.id)
        assertEquals(m.target("agave nectar").catalogId, m.catalogEntry("agave nectar")?.id)
    }
}
