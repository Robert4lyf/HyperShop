package com.robert4lyf.hypershop.backup

import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.ui.list.add
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.io.File

/** The file side of a backup: written, read back, and every refusal in words. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class BackupManagerTest {
    @Test
    fun writeThenInspectIsOk() {
        val c = TestContainer.create()
        c.add("2 onions", "1 carrot")
        val m = BackupManager(c, "1.0-test")
        val file = File(c.context.cacheDir, "b-${System.nanoTime()}.json")
        val uri = Uri.fromFile(file)
        val written = runBlocking { m.writeTo(c.context, uri) }
        assertTrue(written.toString(), written.isSuccess)
        assertEquals(2, written.getOrThrow().itemsOnList)
        shadowOf(c.context.contentResolver).registerInputStreamSupplier(uri) { file.inputStream() }
        val i = runBlocking { m.inspect(c.context, uri) }
        assertTrue(i.toString(), i is Inspection.Ok)
        val ok = i as Inspection.Ok
        assertEquals("1.0-test", ok.summary.appVersion)
        assertEquals(1, ok.summary.recipes)
        // The text form, without any resolver.
        assertTrue(m.inspectText(file.readText()) is Inspection.Ok)
        assertEquals(Inspection.NotABackup, m.inspectText("milk, eggs"))
        assertEquals(Inspection.NotABackup, m.inspectText("""{"format":"something-else","version":1}"""))
        assertEquals(Inspection.Newer(2), m.inspectText("""{"format":"hypershop-backup","version":2}"""))
        assertTrue(m.inspectText("""{"format":"hypershop-backup","version":1}""") is Inspection.Broken)
        // The key never travels.
        assertTrue(!file.readText().contains("sk-ant"))
    }
}
