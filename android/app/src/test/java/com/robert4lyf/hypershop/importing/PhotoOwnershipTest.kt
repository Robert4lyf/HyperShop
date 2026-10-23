package com.robert4lyf.hypershop.importing

import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.backup.BackupManager
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.testutil.Photos
import com.robert4lyf.hypershop.testutil.Photos.everyNamedPhotoIsThere
import com.robert4lyf.hypershop.testutil.Photos.photoFiles
import com.robert4lyf.hypershop.testutil.Photos.photoFolder
import com.robert4lyf.hypershop.testutil.Photos.photoOf
import com.robert4lyf.hypershop.testutil.Photos.saved
import com.robert4lyf.hypershop.testutil.TestContainer
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/**
 * Photos are named by their content, so two recipes with the same picture share one file
 * (S.2). Removing or replacing one recipe's photo never takes another's, a file goes only
 * when nothing names it, and what a failed change leaves behind is swept, never kept forever.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoOwnershipTest {
    private lateinit var c: AppContainer

    @Before
    fun up() {
        c = TestContainer.create()
        c.photoFolder().deleteRecursively()
        File(c.context.noBackupFilesDir, "restore").deleteRecursively()
    }

    private fun pick(
        key: String,
        bytes: ByteArray,
    ) = assertTrue(runBlocking { c.recipePhotos.pick(key, Photos.pickable(bytes)) })

    private fun twoRecipesOnePicture(): Triple<String, String, String> {
        val a = c.saved("Chilli")
        val b = c.saved("Dal")
        val same = Photos.jpeg()
        pick(a, same)
        pick(b, same)
        val shared = c.photoOf(a)!!
        assertEquals("one picture is one file", shared, c.photoOf(b))
        assertEquals(setOf(shared), c.photoFiles())
        return Triple(a, b, shared)
    }

    @Test
    fun removingOneRecipesPhotoLeavesTheOtherRecipesCopy() {
        val (a, b, shared) = twoRecipesOnePicture()
        runBlocking { c.recipePhotos.remove(a) }
        assertNull(c.photoOf(a))
        assertEquals(shared, c.photoOf(b))
        assertNotNull("the other recipe still shows it", c.recipePhotos.file(shared))
        // The last one to let go of it takes the file with it.
        runBlocking { c.recipePhotos.remove(b) }
        assertNull(c.recipePhotos.file(shared))
        assertTrue(c.photoFiles().isEmpty())
    }

    @Test
    fun replacingOneRecipesPhotoLeavesTheOtherRecipesCopy() {
        val (a, b, shared) = twoRecipesOnePicture()
        pick(a, Photos.jpeg(colour = Color.BLUE))
        val replaced = c.photoOf(a)!!
        assertTrue(replaced != shared)
        assertEquals(shared, c.photoOf(b))
        assertEquals(setOf(shared, replaced), c.photoFiles())
        assertTrue(c.everyNamedPhotoIsThere())
    }

    @Test
    fun concurrentReplacementsNeverLeaveARecipeNamingAMissingFile() {
        val a = c.saved("Chilli")
        val b = c.saved("Dal")
        val colours = listOf(Color.RED, Color.GREEN, Color.BLUE, Color.YELLOW)
        runBlocking {
            coroutineScope {
                (0 until 12).map { i ->
                    async {
                        val key = if (i % 2 == 0) a else b
                        when (i % 3) {
                            0 -> c.recipePhotos.remove(key)
                            else -> c.recipePhotos.pick(key, Photos.pickable(Photos.jpeg(colour = colours[i % colours.size])))
                        }
                    }
                }.awaitAll()
            }
        }
        assertTrue(c.everyNamedPhotoIsThere())
        // Whatever was left behind is only what the two recipes show.
        runBlocking { c.recipePhotos.sweep() }
        assertEquals(setOfNotNull(c.photoOf(a), c.photoOf(b)), c.photoFiles())
    }

    @Test
    fun aPhotoStoredButNeverRecordedIsSweptAndTheOldOneKept() {
        val a = c.saved("Chilli")
        pick(a, Photos.jpeg())
        val kept = c.photoOf(a)!!
        // The new file is written, the database write that would name it never happens (no such recipe
        // key can be written: the same path a failed or interrupted write takes).
        assertTrue(runBlocking { c.recipePhotos.pick("", Photos.pickable(Photos.jpeg(colour = Color.BLUE))) })
        assertEquals("nothing was taken from the recipe that has a photo", kept, c.photoOf(a))
        assertEquals(2, c.photoFiles().size)
        // A half-written file from a process that died mid-write.
        File(c.photoFolder(), "$kept.part").writeText("half")
        assertEquals(2, runBlocking { c.recipePhotos.sweep() })
        assertEquals(setOf(kept), c.photoFiles())
        assertTrue(c.everyNamedPhotoIsThere())
    }

    @Test
    fun aSweepWaitsForARestoreInProgressAndKeepsWhatItBrought() {
        val a = c.saved("Chilli")
        pick(a, Photos.jpeg())
        val photo = c.photoOf(a)!!
        val file = runBlocking { c.backups.snapshot() }
        runBlocking { c.recipePhotos.remove(a) }
        // The photo is gone from the phone; the backup names it. Put it back as a restore would find it.
        assertNull(c.recipePhotos.file(photo))
        pick(c.saved("Spare"), Photos.jpeg())
        val paused = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val m = BackupManager(c, "test")
        m.beforeStage = {
            if (it == BackupManager.Stage.SETTINGS) {
                paused.complete(Unit)
                release.await()
            }
        }
        runBlocking {
            coroutineScope {
                val restoring = async { m.restore(file) }
                paused.await()
                var swept = false
                val sweeping = launch { c.recipePhotos.sweep().also { swept = true } }
                delay(200)
                assertFalse("no sweep while a restore holds the folder", swept)
                release.complete(Unit)
                restoring.await()
                sweeping.join()
            }
        }
        assertTrue(c.everyNamedPhotoIsThere())
    }
}
