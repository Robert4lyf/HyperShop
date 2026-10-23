package com.robert4lyf.hypershop.importing

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.data.ListRepository
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.testutil.Photos
import com.robert4lyf.hypershop.testutil.Photos.everyNamedPhotoIsThere
import com.robert4lyf.hypershop.testutil.Photos.photoFiles
import com.robert4lyf.hypershop.testutil.Photos.photoFolder
import com.robert4lyf.hypershop.testutil.Photos.photoOf
import com.robert4lyf.hypershop.testutil.Photos.saved
import com.robert4lyf.hypershop.testutil.TestContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/**
 * T.2: a removed recipe's stars, notes and photo are kept while anything can still bring
 * the recipe back, then a further 30 days, then go; a photo another recipe shows stays.
 * Each "start" is what start-up runs, in its order: the purge of forgotten copies, the
 * retirement of notes nothing names, the photo sweep.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoRetentionTest {
    private val day = 24 * 60 * 60 * 1000L
    private val now = AtomicLong(1_760_000_000_000L)
    private lateinit var c: AppContainer

    @Before
    fun up() {
        c = TestContainer.create(clock = { now.get() })
        c.photoFolder().deleteRecursively()
        File(c.context.noBackupFilesDir, "restore").deleteRecursively()
    }

    private fun later(days: Long) = now.addAndGet(days * day)

    /** What start-up does with notes and photos, in its order. */
    private fun start() =
        runBlocking {
            c.repository.purgeDeleted()
            c.repository.retireOrphanNotes(now.get())
            c.recipePhotos.sweep()
        }

    private fun rated(
        title: String,
        colour: Int,
    ): String {
        val key = c.saved(title)
        assertTrue(runBlocking { c.recipePhotos.pick(key, Photos.pickable(Photos.jpeg(colour = colour))) })
        runBlocking { c.repository.setRating(key, 5) }
        return key
    }

    private fun note(key: String) = runBlocking { c.repository.recipeNote(key) }

    @Test
    fun removedThenUndoneKeepsEverything() {
        val key = rated("Chilli", Color.RED)
        val photo = c.photoOf(key)!!
        val copies = runBlocking { c.repository.removeFromLibrary(key) }
        // The app restarts with the Undo still showing: the copy is kept, so the note and the photo are.
        start()
        assertNotNull(note(key))
        assertNotNull(c.recipePhotos.file(photo))
        runBlocking { c.repository.restoreToLibrary(copies) }
        start()
        assertEquals(5, note(key)!!.rating)
        assertNull(note(key)!!.orphanedAt)
        assertNotNull(c.recipePhotos.file(photo))
    }

    @Test
    fun removedForGoodTheNoteAndPhotoGoAfterThirtyDaysOfNothingNamingThem() {
        val key = rated("Chilli", Color.RED)
        val photo = c.photoOf(key)!!
        runBlocking { c.repository.removeFromLibrary(key) }
        // A week on, the forgotten copy is purged: from then nothing names the note.
        later(8)
        start()
        val marked = note(key)!!.orphanedAt
        assertNotNull("marked when nothing named it", marked)
        assertNotNull(c.recipePhotos.file(photo))
        later(29)
        start()
        assertNotNull("kept for 30 days", note(key))
        assertEquals(marked, note(key)!!.orphanedAt)
        later(2)
        start()
        assertNull(note(key))
        assertNull(c.recipePhotos.file(photo))
        assertTrue(c.photoFiles().isEmpty())
    }

    @Test
    fun savingTheSamePageAgainInTheMonthBringsItAllBack() {
        val key = rated("Chilli", Color.RED)
        runBlocking { c.repository.removeFromLibrary(key) }
        later(8)
        start()
        assertNotNull(note(key)!!.orphanedAt)
        later(10)
        assertEquals(key, c.saved("Chilli"))
        start()
        assertNull(note(key)!!.orphanedAt)
        assertEquals(5, note(key)!!.rating)
        later(60)
        start()
        assertNotNull("named again, so never deleted", note(key))
        assertNotNull(c.recipePhotos.file(note(key)!!.photo))
    }

    @Test
    fun aPhotoAnotherRecipeShowsOutlivesTheOneRemoved() {
        val a = rated("Chilli", Color.RED)
        val b = c.saved("Dal")
        assertTrue(runBlocking { c.recipePhotos.pick(b, Photos.pickable(Photos.jpeg(colour = Color.RED))) })
        val shared = c.photoOf(a)!!
        assertEquals(shared, c.photoOf(b))
        runBlocking { c.repository.removeFromLibrary(a) }
        later(8)
        start()
        later(31)
        start()
        assertNull(note(a))
        assertNotNull("the other recipe still shows it", c.recipePhotos.file(shared))
        assertTrue(c.everyNamedPhotoIsThere())
    }

    @Test
    fun aPlannedMealKeepsItsRecipesPhotoWhileItIsPlanned() {
        val key = rated("Chilli", Color.RED)
        runBlocking {
            c.planner.plan(c.planner.currentWeek(), key)
            c.repository.removeFromLibrary(key)
        }
        later(60)
        start()
        // The meal still names the recipe: its picture is what the plan shows.
        assertNotNull(note(key))
        assertNull(note(key)!!.orphanedAt)
        assertNotNull(c.recipePhotos.file(note(key)!!.photo))
    }

    @Test
    fun aRemovedRecipesMarkTravelsInABackup() {
        val key = rated("Chilli", Color.RED)
        runBlocking { c.repository.removeFromLibrary(key) }
        later(8)
        start()
        val marked = note(key)!!.orphanedAt
        val file = runBlocking { c.backups.snapshot() }
        assertEquals(marked, file.data.recipeNotes.single { it.libraryKey == key }.orphanedAt)
        runBlocking { c.backups.restore(file) }
        assertEquals(marked, note(key)!!.orphanedAt)
    }

    @Test
    fun storageStaysBoundedOverAYearOfRecipesComingAndGoing() {
        // A recipe saved, photographed and removed every week for a year; three kept throughout.
        val kept = listOf(rated("Kept 1", Color.RED), rated("Kept 2", Color.GREEN), rated("Kept 3", Color.BLUE))
        for (w in 0 until 52) {
            val key = rated("Weekly $w", Color.rgb(w * 4, 100, 200 - w * 3))
            runBlocking { c.repository.removeFromLibrary(key) }
            later(7)
            start()
        }
        later(40)
        start()
        // The last one's copy was purged only now: its month has just begun, so its photo is still kept.
        assertEquals(4, c.photoFiles().size)
        later(31)
        start()
        // What is left is what something names: the three kept, and nothing of the 52 removed long ago.
        assertEquals(kept.mapNotNull { c.photoOf(it) }.toSet(), c.photoFiles())
        assertEquals(kept.toSet(), runBlocking { c.repository.observeRecipeNotes().first() }.keys)
        assertTrue(ListRepository.NOTE_KEEP_MS == 30 * day)
    }
}
