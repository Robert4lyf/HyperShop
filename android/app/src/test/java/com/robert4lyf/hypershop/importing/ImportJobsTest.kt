package com.robert4lyf.hypershop.importing

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.data.ImportJobEntity
import com.robert4lyf.hypershop.testutil.TestContainer
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ImportJobsTest {
    private val soup = ImportResult("https://example.com/soup", "Soup", 2, UnitLocale.METRIC, "JSON_LD", rawLines = listOf("2 onions"))

    private suspend fun running(c: com.robert4lyf.hypershop.di.AppContainer) =
        c.db.dao().insertJob(ImportJobEntity(0, JobState.RUNNING.name, """{"url":"https://example.com/soup"}""", null, null, c.clock()))

    @Test
    fun aLateFetchResultKeepsOutOfAJobThatMovedOnToPastedLines() =
        runBlocking {
            val c = TestContainer.create()
            val id = running(c)
            // "Paste the ingredients": the job is ready for them; the worker's result, arriving later, is dropped.
            c.importJobs.cancel(id)
            assertEquals(JobState.READY, c.importJobs.get(id)!!.state)
            c.importJobs.finishRun(id, soup)
            assertNull(c.importJobs.get(id)!!.result)
            // A job still running takes the worker's result as before.
            val other = running(c)
            c.importJobs.finishRun(other, soup)
            assertEquals("Soup", c.importJobs.get(other)!!.result!!.title)
        }

    @Test
    fun aDiscardIsOffTheReadyCardUntilUndoPutsItBackAndIsDoneAtStartUpOtherwise() =
        runBlocking {
            val c = TestContainer.create()
            val id = running(c)
            c.importJobs.finish(id, soup)
            assertEquals(listOf(id), c.importJobs.ready.first().map { it.id })
            c.importJobs.discarding(id)
            assertTrue(c.importJobs.ready.first().isEmpty())
            c.importJobs.restore(id)
            assertEquals(listOf(id), c.importJobs.ready.first().map { it.id })
            // The process died with Undo showing: the next start finishes the discard.
            c.importJobs.discarding(id)
            c.importJobs.finishDiscards()
            assertEquals(JobState.DONE, c.importJobs.get(id)!!.state)
        }

    @Test
    fun anEditOrLibraryReAddLeftBehindIsClearedAtStartUpAndAnImportIsNot() =
        runBlocking {
            val c = TestContainer.create()
            val edit = c.importJobs.enqueueReady(ImportInput("", "Soup"), soup.copy(method = "EDIT", editsUse = 7))
            val again = c.importJobs.enqueueReady(ImportInput("", "Soup"), soup.copy(method = ImportResult.LIBRARY))
            val shared = c.importJobs.enqueueReady(ImportInput("", "Soup"), soup)
            assertEquals(setOf(edit, again, shared), c.importJobs.ready.first().map { it.id }.toSet())
            c.importJobs.finishThrowaways()
            assertEquals(listOf(shared), c.importJobs.ready.first().map { it.id })
            assertEquals(JobState.DONE, c.importJobs.get(edit)!!.state)
        }
}
