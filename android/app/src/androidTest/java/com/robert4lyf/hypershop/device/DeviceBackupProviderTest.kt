package com.robert4lyf.hypershop.device

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.Color
import android.net.Uri
import android.provider.MediaStore
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.backup.BackupFile
import com.robert4lyf.hypershop.backup.BackupManager
import com.robert4lyf.hypershop.backup.Inspection
import com.robert4lyf.hypershop.backup.RestoreOutcome
import com.robert4lyf.hypershop.catalog.EntryEdit
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.di.AppContainer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Backups through a real content provider (MediaStore's Downloads, the one the
 * system's own "Downloads" folder in the file picker is), not a file: Uri. The
 * picker itself and cloud providers are not automated; docs/HANDOFF.md has
 * the manual check for Drive.
 */
@RunWith(AndroidJUnit4::class)
class DeviceBackupProviderTest {
    private val made = ArrayList<Uri>()
    private val resolver get() = DeviceContainer.context.contentResolver
    private val json = Json { encodeDefaults = true }

    private fun newDocument(zip: Boolean = false): Uri {
        val values =
            ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, "hypershop-test-${System.nanoTime()}.${if (zip) "zip" else "json"}")
                put(MediaStore.Downloads.MIME_TYPE, if (zip) "application/zip" else "application/json")
            }
        return requireNotNull(resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)).also { made += it }
    }

    private fun write(
        uri: Uri,
        text: String,
    ) = requireNotNull(resolver.openOutputStream(uri, "wt")).use { it.write(text.toByteArray()) }

    @After
    fun tidy() {
        for (u in made) runCatching { resolver.delete(u, null, null) }
    }

    private fun AppContainer.items() = runBlocking { repository.snapshot() }.liveItems.map { it.mergeKey }.toSet()

    private fun AppContainer.add(name: String) =
        runBlocking { repository.manualAdd(name, QtyBase.of(Rational.ONE, null, UnitLocale.METRIC), null) }

    @Test
    fun writeReadRestoreAndUndoThroughAProvider() {
        val c = DeviceContainer.create(key = "sk-ant-device-test")
        val m = BackupManager(c, "device")
        c.add("bananas")
        val uri = newDocument()
        assertTrue(runBlocking { m.writeTo(c.context, uri) }.isSuccess)
        // Written over a longer file: "wt" truncates, so what is read back is exactly the backup.
        assertTrue(runBlocking { m.writeTo(c.context, uri) }.isSuccess)
        c.add("milk")
        runBlocking { c.repository.clearAll() }
        val read = runBlocking { m.inspect(c.context, uri) }
        assertTrue(read.toString(), read is Inspection.Ok)
        assertEquals(1, (read as Inspection.Ok).summary.itemsOnList)
        val done = runBlocking { m.restore(read.file) } as RestoreOutcome.Restored
        assertEquals(setOf("cat:banana"), c.items())
        assertEquals("sk-ant-device-test", runBlocking { c.apiKeyStore.get() })
        assertTrue(runBlocking { m.restore(done.before) } is RestoreOutcome.Restored)
        assertTrue(c.items().isEmpty())
    }

    /**
     * A photo chosen from the phone's photos goes into the backup through the provider and comes back from it.
     * The photo's file is deleted between the two, as on another phone; only that one file, never the folder,
     * so the test cannot touch anything else in it.
     */
    @Test
    fun aChosenPhotoTravelsThroughAProvider() {
        val c = DeviceContainer.create()
        val m = BackupManager(c, "device")
        val id = c.saveRecipe("Weeknight chilli", "2 onions")
        val key = runBlocking { c.repository.libraryKeyOf(id) }!!
        // A colour of its own, so the photo's name (its content's hash) is not one a real recipe could have.
        val b =
            Bitmap.createBitmap(
                320,
                240,
                Bitmap.Config.ARGB_8888,
            ).apply { eraseColor(Color.rgb(17, (System.nanoTime() % 255).toInt(), 99)) }
        val picked =
            File(c.context.cacheDir, "pick-${System.nanoTime()}.jpg").apply {
                outputStream().use { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }
        assertTrue(runBlocking { c.recipePhotos.pick(key, Uri.fromFile(picked)) })
        val photo = runBlocking { c.repository.recipeNote(key) }!!.photo!!
        val bytes = c.recipePhotos.file(photo)!!.readBytes()
        val uri = newDocument(zip = true)
        val written = runBlocking { m.writeTo(c.context, uri) }.getOrThrow()
        assertEquals(1, written.photos)
        assertTrue(c.recipePhotos.file(photo)!!.delete())
        val read = runBlocking { m.inspect(c.context, uri) }
        assertTrue(read.toString(), read is Inspection.Ok)
        val ok = read as Inspection.Ok
        assertEquals(setOf(photo), ok.photos!!.names)
        assertTrue(runBlocking { m.restore(ok.file, ok.photos) } is RestoreOutcome.Restored)
        assertEquals(photo, runBlocking { c.repository.recipeNote(key) }!!.photo)
        assertTrue(bytes.contentEquals(c.recipePhotos.file(photo)!!.readBytes()))
        picked.delete()
    }

    @Test
    fun theFirstFormatStillRestores() {
        val c = DeviceContainer.create()
        val m = BackupManager(c, "device")
        c.add("milk")
        val uri = newDocument()
        write(
            uri,
            """{"format":"hypershop-backup","version":1,"createdAt":1760000000000,"data":{"listRev":1,"nextId":1,"catalogVersion":"x"}}""",
        )
        val read = runBlocking { m.inspect(c.context, uri) }
        assertTrue(read.toString(), read is Inspection.Ok)
        assertTrue(runBlocking { m.restore((read as Inspection.Ok).file) } is RestoreOutcome.Restored)
        assertTrue(c.items().isEmpty())
    }

    @Test
    fun damagedAndInvalidFilesAreRefusedAndChangeNothing() {
        val c = DeviceContainer.create()
        val m = BackupManager(c, "device")
        c.add("bananas")
        val good = runBlocking { m.snapshot() }
        val text = json.encodeToString(BackupFile.serializer(), good)
        val cases =
            mapOf(
                "not JSON" to "milk, eggs",
                "cut short" to text.take(text.length / 2),
                "a bad theme" to json.encodeToString(BackupFile.serializer(), good.copy(settings = good.settings.copy(theme = "PURPLE"))),
                "a bad ingredient" to
                    json.encodeToString(
                        BackupFile.serializer(),
                        good.copy(libraryEdits = listOf(EntryEdit("../etc", "x", "x", aisle = "MOON"))),
                    ),
                "a newer version" to """{"format":"hypershop-backup","version":99}""",
            )
        for ((what, body) in cases) {
            val uri = newDocument()
            write(uri, body)
            val read = runBlocking { m.inspect(c.context, uri) }
            assertTrue("$what: $read", read !is Inspection.Ok)
            assertEquals(what, setOf("cat:banana"), c.items())
        }
        // And restore() refuses an invalid file handed to it directly, before writing anything.
        val bad = good.copy(settings = good.settings.copy(theme = "PURPLE"))
        c.add("milk")
        assertTrue(runBlocking { m.restore(bad) } is RestoreOutcome.Refused)
        assertEquals(setOf("cat:banana", "cat:milk"), c.items())
    }
}
