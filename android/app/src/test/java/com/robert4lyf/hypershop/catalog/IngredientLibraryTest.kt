package com.robert4lyf.hypershop.catalog

import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class IngredientLibraryTest {
    @get:Rule val tmp = TemporaryFolder()

    private val base = CatalogLoader.default

    private fun library(): Pair<IngredientLibrary, com.robert4lyf.hypershop.core.catalog.Catalog> {
        val catalog = base.copy()
        return IngredientLibrary(tmp.root.resolve("library/edits.json"), catalog) to catalog
    }

    @Test
    fun aSynonymIsLookedUpAndSurvivesARestart() =
        runBlocking {
            val (lib, catalog) = library()
            val onion = EntryEdit.of(catalog.byId.getValue("spring_onion"))
            assertTrue(lib.save(onion.copy(synonyms = onion.synonyms + "green shallot")) is SaveResult.Saved)
            assertEquals("spring_onion", catalog.lookup("green shallots")?.id ?: catalog.lookup("green shallot")?.id)
            assertNotEquals(base.version, catalog.version)
            // The bundled catalogue is untouched.
            assertNull(base.lookup("green shallot"))
            val (_, again) = library()
            assertEquals("spring_onion", again.lookup("green shallot")?.id)
            assertEquals(catalog.version, again.version)
        }

    @Test
    fun aNameAnotherIngredientUsesIsRefused() =
        runBlocking {
            val (lib, catalog) = library()
            val r = lib.save(EntryEdit("", "My leek", "My leeks", listOf("onion"), "PRODUCE"))
            assertTrue(r is SaveResult.Taken)
            assertNull(catalog.lookup("my leek"))
            assertEquals(SaveResult.NoName, lib.save(EntryEdit("", " ", "", emptyList(), "PRODUCE")))
        }

    @Test
    fun addThenDeleteAndEditThenReset() =
        runBlocking {
            val (lib, catalog) = library()
            val added = lib.save(EntryEdit("", "Zorbleberry", "Zorbleberries", emptyList(), "PRODUCE")) as SaveResult.Saved
            assertEquals("user_zorbleberry", added.id)
            assertEquals("user_zorbleberry", catalog.lookup("zorbleberries")?.id)
            lib.reset(added.id)
            assertNull(catalog.lookup("zorbleberry"))
            val milk = EntryEdit.of(catalog.byId.getValue("milk"))
            lib.save(milk.copy(aisle = "CHILLED"))
            assertEquals("CHILLED", catalog.byId.getValue("milk").aisle.name)
            lib.reset("milk")
            assertEquals(base.byId.getValue("milk"), catalog.byId.getValue("milk"))
            assertEquals(base.version, catalog.version)
        }
}
