package com.robert4lyf.hypershop.importing

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.di.ShareOutcome
import com.robert4lyf.hypershop.testutil.TestContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** A share queues an ImportJob row (it survives the process) and tells the UI where to go. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ImportSharedTest {
    @Test
    fun shareQueuesAJobOrSaysNoLink() =
        runBlocking {
            val c = TestContainer.create()
            assertNull(c.importShared("just some words", "Subject"))
            val first = c.shares.value as ShareOutcome.NoLink
            // Two "no link" shares in a row are two notices, not one (a StateFlow drops an equal value).
            assertNull(c.importShared("more words", null))
            c.shareHandled(first)
            val second = c.shares.value as ShareOutcome.NoLink
            assertNotEquals(first, second)
            c.shareHandled(second)
            assertNull(c.shares.value)
            val id = c.importShared("Lasagne https://recipes.example/lasagne?utm_source=ig&serves=4", "Best lasagne")!!
            val job = c.importJobs.get(id)!!
            assertEquals(ImportInput("https://recipes.example/lasagne?serves=4", "Best lasagne"), job.input)
            // Waits in the container (not the UI) until its review opens.
            val next = c.importShared("https://recipes.example/soup", null)!!
            assertEquals(listOf(id, next), c.pendingReviews.value)
            c.reviewOpened(id)
            assertEquals(listOf(next), c.pendingReviews.value)
        }

    @Test
    fun sharedIngredientLinesAreReadyToReviewWithoutAFetch() =
        runBlocking {
            val c = TestContainer.create()
            val id = c.importShared("2 eggs\n100 g plain flour\n300 ml milk", "Pancakes")!!
            assertEquals(listOf(id), c.pendingReviews.value)
            val job = c.importJobs.get(id)!!
            assertEquals(JobState.READY, job.state)
            assertEquals("Pancakes", job.result!!.title)
            assertEquals(listOf("eggs", "plain flour", "milk"), job.result!!.lines.map { it.name })
        }
}
