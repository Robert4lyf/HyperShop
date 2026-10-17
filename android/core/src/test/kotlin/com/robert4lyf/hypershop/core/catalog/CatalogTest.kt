package com.robert4lyf.hypershop.core.catalog

import com.robert4lyf.hypershop.core.model.AisleCode
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/** The catalogue validator, on the Kotlin side (the Python one runs in verify.sh). */
class CatalogTest {
    private val catalog = CatalogLoader.default

    @Test
    fun loadsAndHasAboutFourHundredRows() {
        assertTrue(catalog.entries.size in 350..500, "rows: ${catalog.entries.size}")
        assertEquals(12, catalog.version.length)
    }

    @Test
    fun idsAndKeysAreUnique() {
        assertEquals(catalog.entries.size, catalog.byId.size)
        val owners = HashMap<String, String>()
        for (e in catalog.entries) {
            for (n in listOf(e.name, e.plural) + e.synonyms) {
                val k = CatalogKey.of(n)
                val prev = owners.put(k, e.id)
                assertTrue(prev == null || prev == e.id, "key '$k' in both $prev and ${e.id}")
            }
        }
    }

    @Test
    fun staplesAreTheSeededFive() {
        assertEquals(
            setOf("salt", "black_pepper", "olive_oil", "vegetable_oil", "water"),
            catalog.entries.filter { it.staple }.map { it.id }.toSet(),
        )
    }

    @Test
    fun packNotesOnAboutTwenty() {
        assertTrue(catalog.entries.count { it.packNote != null } in 15..30)
    }

    @Test
    fun lookupBySynonymPluralAndAccent() {
        assertEquals("aubergine", catalog.lookup("Eggplant")?.id)
        assertEquals("courgette", catalog.lookup("zucchini")?.id)
        assertEquals("onion", catalog.lookup("onions")?.id)
        assertEquals("creme_fraiche", catalog.lookup("crème fraîche")?.id)
        assertEquals("creme_fraiche", catalog.lookup("creme fraiche")?.id)
        assertEquals("salt", catalog.lookup("kosher salt")?.id)
        assertEquals(AisleCode.PRODUCE, catalog.lookup("coriander")?.aisle)
        assertNull(catalog.lookup("unobtainium"))
    }

    @Test
    fun keyMatchesThePythonNormaliser() {
        assertEquals("confectioners' sugar", CatalogKey.of("Confectioners’ Sugar"))
        assertEquals("jalapeno", CatalogKey.of("Jalapeño"))
        assertEquals("extra-virgin olive oil", CatalogKey.of("  Extra-Virgin   Olive Oil, "))
    }

    @Test
    fun bundledResourceMatchesTheSourceCsv() {
        // The generated TSV is checked for staleness by build_catalog.py --check in
        // verify.sh; this guards that the resource on the classpath is that file.
        val repoTsv = File("src/main/resources/catalog/catalog.tsv")
        assertNotNull(catalog)
        if (repoTsv.exists()) assertTrue(repoTsv.readText().contains("#version\t${catalog.version}"))
    }
}
