package com.robert4lyf.hypershop.catalog

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.parse.QuantityInput
import com.robert4lyf.hypershop.data.PendingLibraryEditEntity
import com.robert4lyf.hypershop.testutil.TestContainer
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class LibraryOutboxTest {
    @Test
    fun aFailedLibraryWriteKeepsTheOriginalAndTheCommittedEditForRetry() =
        runBlocking {
            val c = TestContainer.create()
            val quantity = QuantityInput.parse("2")!!
            val id = c.repository.manualAdd("retry test ingredient", quantity, null)
            val item = c.repository.snapshot().items.getValue(id)
            val edit = EntryEdit("user_retry", "retry test ingredient", "retry test ingredients", aisle = item.aisle.name, custom = true)
            assertTrue(c.library.save(edit) is SaveResult.Saved)
            val file = File(c.context.filesDir, "library/edits.json")
            val original = file.readText()
            val pending = PendingLibraryEditEntity("failed-save", Json.encodeToString(EntryEdit.serializer(), edit.copy(group = "Usual")))
            c.repository.editItem(id, item.rev, null, quantity, null, false, pending)
            // A directory at the temporary-file path deterministically refuses the write, even as root.
            val obstruction = File(file.parentFile, file.name + ".tmp")
            assertTrue(obstruction.mkdir())
            try {
                var failed = false
                try {
                    c.flushLibraryEdits()
                } catch (_: IOException) {
                    failed = true
                }
                assertTrue(failed)
                assertEquals(original, file.readText())
                assertEquals(null, c.catalog.lookup(edit.name)?.group)
                assertEquals(listOf(pending), c.repository.pendingLibraryEdits())
                assertEquals(quantity, c.repository.snapshot().total(id))
            } finally {
                assertTrue(obstruction.delete())
            }
            c.flushLibraryEdits()
            assertTrue(c.repository.pendingLibraryEdits().isEmpty())
            assertEquals("Usual", c.catalog.lookup(edit.name)?.group)
            assertEquals(quantity, c.repository.snapshot().total(id))
        }

    @Test
    fun aCommittedPreferenceCanBeReplayedIdempotentlyBeforeSuccess() =
        runBlocking {
            val c = TestContainer.create()
            val quantity = QuantityInput.parse("2")!!
            val id = c.repository.manualAdd("test ingredient", quantity, null)
            val item = c.repository.snapshot().items.getValue(id)
            val edit =
                EntryEdit(
                    "user_outbox",
                    "test ingredient",
                    "test ingredients",
                    aisle = item.aisle.name,
                    group = "Usual",
                    custom = true,
                )
            val pending = PendingLibraryEditEntity("interrupted-save", Json.encodeToString(EntryEdit.serializer(), edit))
            c.repository.editItem(
                id, item.rev, null, quantity, null, false, pending,
                packChanged = true, pack = QuantityInput.parse("400 g"), storeId = 1,
            )
            // This is the interrupted boundary: DB committed, library file not yet written.
            assertEquals(listOf(pending), c.repository.pendingLibraryEdits())
            c.flushLibraryEdits()
            c.flushLibraryEdits()
            assertTrue(c.repository.pendingLibraryEdits().isEmpty())
            assertEquals("Usual", c.catalog.lookup("test ingredient")?.group)
            val after = c.repository.snapshot()
            assertEquals(quantity, after.total(id))
            assertEquals(QuantityInput.parse("400 g"), c.repository.packPreference(1, after.items.getValue(id).mergeKey))
        }
}
