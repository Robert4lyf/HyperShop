package com.robert4lyf.hypershop.data

import androidx.room.testing.MigrationTestHelper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

/** A saved recipe's rating, cooking notes and photo: kept by library entry, through edits, a migration and a backup. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class RecipeNotesTest {
    @get:Rule val r = DbRule()

    @get:Rule
    val helper =
        MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), HyperShopDatabase::class.java)

    private fun marker() =
        EpochMarker(File(ApplicationProvider.getApplicationContext<android.content.Context>().cacheDir, "m-${System.nanoTime()}"))

    private suspend fun note(key: String) = r.repo.observeRecipeNotes().first()[key]

    @Test
    fun ratingNotesAndPhotoAreKeptByLibraryEntryAndOutliveAnEdit() =
        runBlocking {
            val key = r.save("Chilli", "2 onions")
            r.repo.setRating(key, 4)
            r.repo.setCookingNotes(key, "  Simmer 20 min longer; half the chilli flakes.  ")
            r.repo.offerRecipePhoto(key, "https://example.com/chilli.jpg")
            var n = note(key)!!
            assertEquals(4, n.rating)
            assertEquals("Simmer 20 min longer; half the chilli flakes.", n.notes)
            assertEquals("https://example.com/chilli.jpg", n.photoUrl)
            assertNull(n.photo)
            // A photo chosen by hand is not replaced by the page's, offered again on a later import.
            r.repo.setRecipePhoto(key, "0123456789abcdef.jpg", null)
            r.repo.offerRecipePhoto(key, "https://example.com/other.jpg")
            n = note(key)!!
            assertEquals("0123456789abcdef.jpg", n.photo)
            assertNull(n.photoUrl)
            // Saved again (an edit, Add again: a new copy with the same key): the note is the entry's, so it stays.
            r.save("Chilli", "3 onions")
            assertEquals(4, note(key)!!.rating)
            // Out of range clears; blank notes clear.
            r.repo.setRating(key, 9)
            r.repo.setCookingNotes(key, "   ")
            n = note(key)!!
            assertNull(n.rating)
            assertNull(n.notes)
            // Capped, not refused.
            r.repo.setCookingNotes(key, "x".repeat(10_000))
            assertEquals(ListRepository.MAX_COOKING_NOTES, note(key)!!.notes!!.length)
        }

    @Test
    fun removingARecipeFromTheLibraryKeepsItsNotesForTheUndo() =
        runBlocking {
            val key = r.save("Soup", "1 carrot")
            r.repo.setRating(key, 5)
            val removed = r.repo.removeFromLibrary(key)
            assertEquals(5, note(key)!!.rating)
            r.repo.restoreToLibrary(removed)
            assertTrue(r.repo.observeLibrary().first().any { it.key == key })
            assertEquals(5, note(key)!!.rating)
        }

    @Test
    fun aBackupCarriesThemAndAnOlderFileRestoresWithout() =
        runBlocking {
            val key = r.save("Dal", "200 g red lentils")
            r.repo.setRating(key, 3)
            r.repo.setCookingNotes(key, "Add a squeeze of lime at the end")
            r.repo.setRecipePhoto(key, "abcdef0123456789.jpg", "https://example.com/dal.jpg")
            val b = r.repo.exportBackup()
            assertEquals(1, b.recipeNotes.size)
            val fresh = DbRule().also { it.open() }
            try {
                assertEquals(emptyList<String>(), fresh.repo.restoreBackup(b, marker()))
                val n = fresh.repo.recipeNote(key)!!
                assertEquals(3, n.rating)
                assertEquals("Add a squeeze of lime at the end", n.notes)
                assertEquals("https://example.com/dal.jpg", n.photoUrl)
                // A file from before notes (no recipeNotes): restores, and clears what was there.
                assertEquals(emptyList<String>(), fresh.repo.restoreBackup(b.copy(recipeNotes = emptyList()), marker()))
                assertNull(fresh.repo.recipeNote(key))
            } finally {
                fresh.close()
            }
        }

    @Test
    fun aBackupCannotPointAPhotoOutsideTheAppsFolderOrCarryABadRating() =
        runBlocking {
            val key = r.save("Dal", "200 g red lentils")
            r.repo.setRating(key, 3)
            val b = r.repo.exportBackup()
            val bad =
                listOf(
                    RecipeNoteEntity(key, photo = "../../databases/hypershop.db"),
                    RecipeNoteEntity(key, photo = "/sdcard/x.jpg"),
                    RecipeNoteEntity(key, rating = 6),
                    RecipeNoteEntity(key, notes = "x".repeat(ListRepository.MAX_COOKING_NOTES + 1)),
                )
            for (n in bad) assertTrue("$n", BackupValidator.validate(b.copy(recipeNotes = listOf(n))).isNotEmpty())
            assertTrue(BackupValidator.validate(b.copy(recipeNotes = listOf(b.recipeNotes[0], b.recipeNotes[0]))).isNotEmpty())
        }

    @Test
    fun theMigrationAddsTheTableAndLeavesRecipesAlone() {
        val name = "notes-migration.db"
        helper.createDatabase(name, 5).apply {
            execSQL(
                "INSERT INTO Recipe VALUES (1,'https://example.com/chilli','Chilli',4,'METRIC',100,'WEB','RULES',NULL,0,1," +
                    "'https://example.com/chilli',NULL,NULL)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 6, true, HyperShopDatabase.MIGRATION_5_6)
        val c = db.query("SELECT COUNT(*) FROM Recipe")
        c.moveToFirst()
        assertEquals(1, c.getInt(0))
        c.close()
        db.execSQL("INSERT INTO RecipeNote VALUES ('https://example.com/chilli', 4, 'notes', NULL, NULL, 1)")
        db.close()
    }

    @Test
    fun aV6InstallKeepsItsNotesAndGainsTheOrphanMark() {
        helper.createDatabase(name, 6).apply {
            execSQL(
                "INSERT INTO RecipeNote VALUES ('https://example.com/chilli', 4, 'Half the chilli', 'abcdef0123456789abcdef01.jpg', NULL, 1)",
            )
            close()
        }
        val db = helper.runMigrationsAndValidate(name, 7, true, HyperShopDatabase.MIGRATION_6_7)
        val c = db.query("SELECT rating, notes, photo, orphanedAt FROM RecipeNote")
        assertTrue(c.moveToFirst())
        assertEquals(4, c.getInt(0))
        assertEquals("Half the chilli", c.getString(1))
        assertEquals("abcdef0123456789abcdef01.jpg", c.getString(2))
        assertTrue("not marked as orphaned by the upgrade", c.isNull(3))
        c.close()
        db.close()
    }
}
