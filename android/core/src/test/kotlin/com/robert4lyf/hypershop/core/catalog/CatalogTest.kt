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
    fun pankoIsNotRenamedToBreadcrumbs() {
        assertEquals("panko", catalog.lookup("panko breadcrumbs")?.id)
        assertEquals("panko", catalog.lookup("panko")?.id)
        assertEquals("breadcrumbs", catalog.lookup("dried breadcrumbs")?.id)
    }

    @Test
    fun whiteBeansAreDifferentTins() {
        assertEquals("haricot_beans", catalog.lookup("haricot beans")?.id)
        assertEquals("haricot_beans", catalog.lookup("navy beans")?.id)
        assertEquals("butter_beans", catalog.lookup("butter beans")?.id)
        assertEquals("butter_beans", catalog.lookup("lima beans")?.id)
        assertEquals("cannellini_beans", catalog.lookup("cannellini beans")?.id)
        assertEquals("cannellini_beans", catalog.lookup("white beans")?.id)
    }

    @Test
    fun synonymsDoNotRenameOneProductIntoAnother() {
        val own =
            mapOf(
                "pecorino" to "pecorino",
                "pecorino romano" to "pecorino",
                "emmental" to "emmental",
                "swiss cheese" to "emmental",
                "dry vermouth" to "vermouth",
                "vermouth" to "vermouth",
                "marsala" to "marsala",
                "malt vinegar" to "malt_vinegar",
                "orange blossom water" to "orange_blossom_water",
                "orange flower water" to "orange_blossom_water",
                "soda water" to "soda_water",
                "club soda" to "soda_water",
                "sparkling water" to "soda_water",
                "tortilla chips" to "tortilla_chips",
                "corn syrup" to "corn_syrup",
                "light corn syrup" to "corn_syrup",
                "dry mustard" to "mustard_powder",
                "mustard powder" to "mustard_powder",
                "sake" to "sake",
                "chicken stock cube" to "stock_cube",
                "cumin seeds" to "cumin_seed",
                "coriander seeds" to "coriander_seed",
                "ground cardamom" to "cardamom_ground",
                "ground caraway" to "caraway_ground",
                "ham hock" to "ham_hock",
                "smoked mackerel" to "smoked_mackerel",
                "porcini" to "porcini",
                "dried porcini" to "porcini",
                "peppers" to "bell_pepper",
                "sweet peppers" to "bell_pepper",
                "padron peppers" to "padron_pepper",
                "ground red pepper" to "cayenne",
            )
        for ((name, id) in own) assertEquals(id, catalog.lookup(name)?.id, name)
        // Removed: these stay as typed rather than becoming something else.
        assertNull(catalog.lookup("apple cider"))
        assertNull(catalog.lookup("clam juice"))
        assertEquals(AisleCode.MEAT_FISH, catalog.lookup("ham hock")?.aisle)
        assertEquals(AisleCode.CHILLED, catalog.lookup("smoked mackerel")?.aisle)
        assertEquals(AisleCode.SPICES_CONDIMENTS, catalog.lookup("mustard powder")?.aisle)
        // Generic buckets that are fine as they are.
        assertEquals("blue_cheese", catalog.lookup("stilton")?.id)
        assertEquals("parmesan", catalog.lookup("parmigiano reggiano")?.id)
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
