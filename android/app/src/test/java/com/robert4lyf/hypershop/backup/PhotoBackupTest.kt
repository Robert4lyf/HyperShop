package com.robert4lyf.hypershop.backup

import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.testutil.Photos
import com.robert4lyf.hypershop.testutil.Photos.everyNamedPhotoIsThere
import com.robert4lyf.hypershop.testutil.Photos.photoFiles
import com.robert4lyf.hypershop.testutil.Photos.photoFolder
import com.robert4lyf.hypershop.testutil.Photos.photoOf
import com.robert4lyf.hypershop.testutil.Photos.saved
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.ui.list.add
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** Stands in for the process dying: an Error, so nothing on the way out catches it as a failure. */
private class Died : Error("process killed")

/**
 * S.3: a backup carries the recipe photos, so one chosen from the phone's own photos
 * survives a move to another phone. The archive is read as hostile input, the photos
 * join the journalled restore, and the database never names a photo that is not a file.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoBackupTest {
    private val server = MockWebServer()

    @Before
    fun up() {
        server.start()
        val c = TestContainer.create()
        c.photoFolder().deleteRecursively()
        File(c.context.noBackupFilesDir, "restore").deleteRecursively()
        File(c.context.cacheDir, "restore-photos").deleteRecursively()
    }

    @After
    fun down() = server.shutdown()

    private fun phone() = TestContainer.create(pages = server)

    /** Another phone: a new install, its photo folder empty (the folder is shared by every test container). */
    private fun anotherPhone(): AppContainer = phone().also { it.photoFolder().deleteRecursively() }

    private fun AppContainer.pick(
        key: String,
        bytes: ByteArray,
    ) = assertTrue(runBlocking { recipePhotos.pick(key, Photos.pickable(bytes)) })

    private fun AppContainer.backUp(): Pair<File, BackupSummary> {
        val f = File(context.cacheDir, "backup-${System.nanoTime()}.zip")
        val r = runBlocking { backups.writeTo(context, Uri.fromFile(f)) }
        assertTrue(r.toString(), r.isSuccess)
        return f to r.getOrThrow()
    }

    private fun AppContainer.inspect(f: File): Inspection {
        val uri = Uri.fromFile(f)
        shadowOf(context.contentResolver).registerInputStreamSupplier(uri) { f.inputStream() }
        return runBlocking { backups.inspect(context, uri) }
    }

    private fun AppContainer.restoreFrom(f: File): Inspection.Ok {
        val i = inspect(f)
        assertTrue(i.toString(), i is Inspection.Ok)
        val ok = i as Inspection.Ok
        assertTrue(runBlocking { backups.restore(ok.file, ok.photos) } is RestoreOutcome.Restored)
        return ok
    }

    private fun entries(f: File): List<String> =
        ZipInputStream(f.inputStream()).use { z -> generateSequence { z.nextEntry }.map { it.name }.toList() }

    /** A hand-made archive, for the files a hostile or damaged backup would be. */
    private fun archive(vararg entries: Pair<String, ByteArray>): File {
        val f = File.createTempFile("evil", ".zip")
        ZipOutputStream(f.outputStream()).use { z ->
            for ((name, bytes) in entries) {
                z.putNextEntry(ZipEntry(name))
                z.write(bytes)
                z.closeEntry()
            }
        }
        return f
    }

    /**
     * The same, written header by header (stored, no central directory): the only way to put one name in
     * twice, which `ZipOutputStream` refuses to do and a hostile file is free to.
     */
    private fun rawArchive(vararg entries: Pair<String, ByteArray>): File {
        val out = ByteArrayOutputStream()

        fun le(
            v: Long,
            n: Int,
        ) = repeat(n) { out.write(((v shr (8 * it)) and 0xFF).toInt()) }
        for ((name, bytes) in entries) {
            val crc = java.util.zip.CRC32().apply { update(bytes) }.value
            le(0x04034b50, 4)
            le(20, 2)
            le(0, 2)
            le(0, 2)
            le(0, 4)
            le(crc, 4)
            le(bytes.size.toLong(), 4)
            le(bytes.size.toLong(), 4)
            le(name.length.toLong(), 2)
            le(0, 2)
            out.write(name.toByteArray())
            out.write(bytes)
        }
        return File.createTempFile("raw", ".zip").apply { writeBytes(out.toByteArray()) }
    }

    /** A real archive's parts, to rebuild with one thing changed. */
    private fun parts(f: File): List<Pair<String, ByteArray>> =
        ZipInputStream(f.inputStream()).use { z -> generateSequence { z.nextEntry }.map { it.name to z.readBytes() }.toList() }

    private fun AppContainer.assertRefusedAndUnchanged(
        f: File,
        before: Set<String>,
    ) {
        val i = inspect(f)
        assertTrue(i.toString(), i is Inspection.Broken || i is Inspection.NotABackup)
        assertEquals("nothing reached the photo folder", before, photoFiles())
        assertTrue("nothing left staged", File(context.cacheDir, "restore-photos").listFiles().orEmpty().isEmpty())
    }

    @Test
    fun aChosenPhotoMovesToAnotherPhoneWithItsRatingAndNotes() {
        val a = phone()
        val key = a.saved("Chilli")
        a.pick(key, Photos.jpeg())
        runBlocking {
            a.repository.setRating(key, 4)
            a.repository.setCookingNotes(key, "Half the chilli")
        }
        val photo = a.photoOf(key)!!
        val bytes = a.recipePhotos.file(photo)!!.readBytes()
        val (file, written) = a.backUp()
        assertEquals(1, written.photos)
        assertEquals(0, written.missingPhotos)
        assertEquals(listOf("manifest.json", "backup.json", "photos/$photo"), entries(file))

        val b = anotherPhone()
        assertNull(b.recipePhotos.file(photo))
        val ok = b.restoreFrom(file)
        assertEquals(1, ok.summary.photos)
        val note = runBlocking { b.repository.recipeNote(key) }!!
        assertEquals(photo, note.photo)
        assertEquals(4, note.rating)
        assertEquals("Half the chilli", note.notes)
        assertArrayEquals(bytes, b.recipePhotos.file(photo)!!.readBytes())
        // Nothing is left waiting in the cache, and the next start's sweep keeps it.
        assertFalse(File(b.context.cacheDir, "restore-photos").exists())
        runBlocking { b.recipePhotos.sweep() }
        assertTrue(b.everyNamedPhotoIsThere())
    }

    @Test
    fun severalPhotosAndASharedOneTravelAsOneFileEach() {
        val a = phone()
        val (k1, k2, k3) = listOf("Chilli", "Dal", "Soup").map { a.saved(it) }
        val shared = Photos.jpeg(colour = Color.RED)
        a.pick(k1, shared)
        a.pick(k2, shared)
        a.pick(k3, Photos.jpeg(colour = Color.BLUE))
        val (file, written) = a.backUp()
        assertEquals(2, written.photos)
        assertEquals("the shared picture is in the archive once", 2, entries(file).count { it.startsWith("photos/") })
        val b = anotherPhone()
        b.restoreFrom(file)
        assertEquals(b.photoOf(k1), b.photoOf(k2))
        assertTrue(b.photoOf(k1) != b.photoOf(k3))
        assertEquals(2, b.photoFiles().size)
        assertTrue(b.everyNamedPhotoIsThere())
        // Removing it from one recipe on the new phone leaves the other's.
        runBlocking { b.recipePhotos.remove(k1) }
        assertNotNull(b.recipePhotos.file(b.photoOf(k2)))
    }

    @Test
    fun aPhotoFromTheRecipesPageTravelsTooAndIsNotFetchedAgain() {
        val a = phone()
        val key = a.saved("Pie")
        server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(Photos.jpeg(800, 600))))
        val url = server.url("/pie.jpg").toString()
        assertTrue(runBlocking { a.recipePhotos.offer(key, url) })
        val (file, _) = a.backUp()
        val b = anotherPhone()
        b.restoreFrom(file)
        val note = runBlocking { b.repository.recipeNote(key) }!!
        assertEquals(url, note.photoUrl)
        assertNotNull(b.recipePhotos.file(note.photo))
        assertEquals(1, server.requestCount)
    }

    @Test
    fun ratingsAndNotesWithNoPhotosMakeAnArchiveWithNone() {
        val a = phone()
        val key = a.saved("Chilli")
        runBlocking { a.repository.setRating(key, 5) }
        val (file, written) = a.backUp()
        assertEquals(0, written.photos)
        assertEquals(listOf("manifest.json", "backup.json"), entries(file))
        val b = anotherPhone()
        b.restoreFrom(file)
        assertEquals(5, runBlocking { b.repository.recipeNote(key) }!!.rating)
    }

    @Test
    fun anOlderJsonBackupStillRestoresAndSaysWhichPhotosItCannotBring() {
        val a = phone()
        val chosen = a.saved("Chilli")
        val fromPage = a.saved("Dal")
        a.pick(chosen, Photos.jpeg(colour = Color.RED))
        a.pick(fromPage, Photos.jpeg(colour = Color.BLUE))
        runBlocking { a.repository.setRecipePhoto(fromPage, a.photoOf(fromPage), server.url("/dal.jpg").toString()) }
        // As the version before archives wrote it: the JSON alone.
        val json =
            File(a.context.cacheDir, "old-${System.nanoTime()}.json").apply {
                writeText(a.backups.jsonOf(runBlocking { a.backups.snapshot() }))
            }

        // On the phone it came from, the same file finds its photos where they are (the same name is the same picture).
        val here = a.inspect(json) as Inspection.Ok
        assertEquals(0, here.summary.missingPhotos)
        assertTrue(runBlocking { a.backups.restore(here.file) } is RestoreOutcome.Restored)
        assertNotNull(a.recipePhotos.file(a.photoOf(chosen)))

        val b = anotherPhone()
        val i = b.inspect(json) as Inspection.Ok
        assertNull(i.photos)
        assertEquals("the chosen photo cannot be brought back", 1, i.summary.missingPhotos)
        assertTrue(runBlocking { b.backups.restore(i.file, i.photos) } is RestoreOutcome.Restored)
        // Nothing names a file that is not there; the page's photo keeps its address, to be fetched again.
        assertNull(b.photoOf(chosen))
        assertNull(b.photoOf(fromPage))
        assertNotNull(runBlocking { b.repository.recipeNote(fromPage) }!!.photoUrl)
        assertTrue(b.everyNamedPhotoIsThere())
    }

    @Test
    fun aMissingPhotoIsSaidWhenBackingUp() {
        val a = phone()
        val key = a.saved("Chilli")
        a.pick(key, Photos.jpeg())
        a.recipePhotos.file(a.photoOf(key))!!.delete()
        val (file, written) = a.backUp()
        assertEquals(0, written.photos)
        assertEquals(1, written.missingPhotos)
        assertEquals(1, (anotherPhone().inspect(file) as Inspection.Ok).summary.missingPhotos)
    }

    @Test
    fun restoringOverAPhoneWithPhotosKeepsThemForUndoUntilTheNextSweep() {
        val a = phone()
        val mine = a.saved("Mine")
        a.pick(mine, Photos.jpeg(colour = Color.GREEN))
        val myPhoto = a.photoOf(mine)!!
        val theirs = phone()
        val their = theirs.saved("Theirs")
        theirs.pick(their, Photos.jpeg(colour = Color.MAGENTA))
        val (file, _) = theirs.backUp()

        val i = a.inspect(file) as Inspection.Ok
        val done = runBlocking { a.backups.restore(i.file, i.photos) } as RestoreOutcome.Restored
        assertNull(a.photoOf(mine))
        assertNotNull(a.recipePhotos.file(a.photoOf(their)))
        assertNotNull("kept for Undo", a.recipePhotos.file(myPhoto))
        // Undo: everything as it was, the photo included.
        assertTrue(runBlocking { a.backups.restore(done.before) } is RestoreOutcome.Restored)
        assertEquals(myPhoto, a.photoOf(mine))
        assertTrue(a.everyNamedPhotoIsThere())
        // The next start tidies what nothing names any more.
        runBlocking { a.recipePhotos.sweep() }
        assertEquals(setOf(myPhoto), a.photoFiles())
    }

    @Test
    fun killedPartWayTheNextStartFinishesTheRestoreWithItsPhotos() {
        for (stage in BackupManager.Stage.entries) {
            val a = phone()
            val key = a.saved("Chilli")
            a.pick(key, Photos.jpeg(colour = Color.RED))
            val (file, _) = a.backUp()
            val b = anotherPhone()
            val old = b.saved("Old")
            b.pick(old, Photos.jpeg(colour = Color.CYAN))
            val i = b.inspect(file) as Inspection.Ok
            b.backups.beforeStage = { if (it == stage) throw Died() }
            runCatching { runBlocking { b.backups.restore(i.file, i.photos) } }.let { assertTrue("$stage", it.exceptionOrNull() is Died) }
            // The next process: recovery first, then the sweep, as start-up runs them.
            val next = BackupManager(b, "test")
            assertEquals("$stage", BackupManager.Recovery.FINISHED, runBlocking { next.recover() })
            runBlocking { b.recipePhotos.sweep() }
            assertNotNull("$stage", b.recipePhotos.file(b.photoOf(key)))
            assertTrue("$stage", b.everyNamedPhotoIsThere())
            File(b.context.noBackupFilesDir, "restore").deleteRecursively()
        }
    }

    @Test
    fun aDamagedJournalCopyRollsBackAndTheOldPhotosAreStillThere() {
        val a = phone()
        val key = a.saved("Chilli")
        a.pick(key, Photos.jpeg(colour = Color.RED))
        val (file, _) = a.backUp()
        val b = anotherPhone()
        val old = b.saved("Old")
        b.pick(old, Photos.jpeg(colour = Color.CYAN))
        val oldPhoto = b.photoOf(old)!!
        val i = b.inspect(file) as Inspection.Ok
        b.backups.beforeStage = { if (it == BackupManager.Stage.SETTINGS) throw Died() }
        runCatching { runBlocking { b.backups.restore(i.file, i.photos) } }
        File(b.context.noBackupFilesDir, "restore/target.json").writeText("{ half")
        assertEquals(BackupManager.Recovery.ROLLED_BACK, runBlocking { BackupManager(b, "test").recover() })
        runBlocking { b.recipePhotos.sweep() }
        assertEquals(oldPhoto, b.photoOf(old))
        assertNotNull(b.recipePhotos.file(oldPhoto))
        assertTrue(b.everyNamedPhotoIsThere())
    }

    @Test
    fun aFailedRestoreLeavesThePhoneAsItWas() {
        val a = phone()
        a.pick(a.saved("Chilli"), Photos.jpeg(colour = Color.RED))
        val (file, _) = a.backUp()
        val b = anotherPhone()
        val old = b.saved("Old")
        b.pick(old, Photos.jpeg(colour = Color.CYAN))
        val oldPhoto = b.photoOf(old)!!
        val i = b.inspect(file) as Inspection.Ok
        var once = 0
        b.backups.beforeStage = { if (it == BackupManager.Stage.LIBRARY && once++ == 0) throw IllegalStateException("disk") }
        assertEquals(RestoreOutcome.Failed(rolledBack = true), runBlocking { b.backups.restore(i.file, i.photos) })
        assertEquals(oldPhoto, b.photoOf(old))
        runBlocking { b.recipePhotos.sweep() }
        assertEquals(setOf(oldPhoto), b.photoFiles())
    }

    @Test
    fun aDamagedOrHostileArchiveIsRefusedBeforeAnythingChanges() {
        val a = phone()
        a.add("2 onions")
        val key = a.saved("Chilli")
        a.pick(key, Photos.jpeg())
        val photo = a.photoOf(key)!!
        val (good, _) = a.backUp()
        val p = parts(good)
        val manifest = p[0]
        val backup = p[1]
        val image = p[2]
        val b = anotherPhone()
        val nothing = b.photoFiles()
        val jpegBytes = image.second
        val corrupt = jpegBytes.copyOf().also { it[it.size / 2] = (it[it.size / 2] + 1).toByte() }

        // Damaged image data (its hash no longer matches), and a photo that is not an image at all.
        b.assertRefusedAndUnchanged(archive(manifest, backup, image.first to corrupt), nothing)
        // A photo the manifest lists is not there.
        b.assertRefusedAndUnchanged(archive(manifest, backup), nothing)
        // No backup in it, or the manifest not first.
        b.assertRefusedAndUnchanged(archive(manifest, image), nothing)
        b.assertRefusedAndUnchanged(archive(backup, manifest, image), nothing)
        // An entry twice.
        b.assertRefusedAndUnchanged(rawArchive(manifest, backup, image, image), nothing)
        b.assertRefusedAndUnchanged(rawArchive(manifest, backup, backup, image), nothing)
        b.assertRefusedAndUnchanged(rawArchive(manifest, manifest, backup, image), nothing)
        // The raw form itself is read: the same entries once each are a good archive.
        assertTrue(b.inspect(rawArchive(manifest, backup, image)) is Inspection.Ok)
        b.backups.discardStaged()
        // Names that would reach outside the folder, or are not a photo's.
        for (evil in listOf("photos/../../databases/hypershop.db", "../$photo", "/data/$photo", "photos/sub/$photo", "photos/", "x.txt")) {
            b.assertRefusedAndUnchanged(archive(manifest, backup, image, evil to jpegBytes), nothing)
        }
        assertFalse(File(b.context.filesDir, "databases/hypershop.db.jpg").exists())
        // Cut short, as an interrupted copy would be.
        val bytes = good.readBytes()
        b.assertRefusedAndUnchanged(File.createTempFile("cut", ".zip").apply { writeBytes(bytes.copyOf(bytes.size * 2 / 3)) }, nothing)
        // Still restorable whole.
        b.restoreFrom(good)
        assertNotNull(b.recipePhotos.file(photo))
    }

    @Test
    fun anOversizedOrBombedPhotoIsRefused() {
        val a = phone()
        a.pick(a.saved("Chilli"), Photos.jpeg())
        val (good, _) = a.backUp()
        val (manifest, backup, image) = parts(good)
        val b = anotherPhone()
        val nothing = b.photoFiles()
        // A photo far bigger than its manifest says: zeros compress to almost nothing, inflate to 64 MB.
        b.assertRefusedAndUnchanged(archive(manifest, backup, image.first to ByteArray(64 * 1024 * 1024)), nothing)
        // A manifest that claims an enormous photo.
        val huge = String(manifest.second).replace(Regex("\"bytes\":\\d+"), "\"bytes\":999999999")
        b.assertRefusedAndUnchanged(archive(manifest.first to huge.toByteArray(), backup, image), nothing)
        // A real JPEG, honestly listed, but larger than any photo this app makes.
        val big = Photos.jpeg(3000, 2000)
        val name = BackupArchive.sha256(File.createTempFile("big", ".jpg").apply { writeBytes(big) })
        val bigManifest =
            """{"format":"hypershop-archive","version":1,"photos":[{"name":"${name.take(24)}.jpg","sha256":"$name","bytes":${big.size}}]}"""
        b.assertRefusedAndUnchanged(archive(MANIFEST to bigManifest.toByteArray(), backup, "photos/${name.take(24)}.jpg" to big), nothing)
    }

    @Test
    fun anArchiveFromANewerVersionIsSaidToBeNewer() {
        val b = anotherPhone()
        val f = archive(MANIFEST to """{"format":"hypershop-archive","version":9}""".toByteArray())
        assertEquals(Inspection.Newer(9), b.inspect(f))
        assertTrue(b.inspect(archive("random.bin" to ByteArray(10))) is Inspection.Broken)
    }

    @Test
    fun theBackupIsWrittenThroughTheDocumentProviderAsOneStream() {
        // A provider that hands back a stream and nothing else (no file path): what SAF gives.
        val a = phone()
        a.pick(a.saved("Chilli"), Photos.jpeg())
        val sink = ByteArrayOutputStream()
        val uri = Uri.parse("content://com.example.documents/document/backup")
        shadowOf(a.context.contentResolver).registerOutputStream(uri, sink)
        assertTrue(runBlocking { a.backups.writeTo(a.context, uri) }.isSuccess)
        val b = anotherPhone()
        shadowOf(b.context.contentResolver).registerInputStream(uri, sink.toByteArray().inputStream())
        val i = runBlocking { b.backups.inspect(b.context, uri) }
        assertTrue(i.toString(), i is Inspection.Ok)
        assertEquals(1, (i as Inspection.Ok).summary.photos)
    }

    private companion object {
        const val MANIFEST = "manifest.json"
    }
}
