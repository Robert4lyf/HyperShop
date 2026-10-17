package com.robert4lyf.hypershop.importing

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.di.ShareOutcome
import com.robert4lyf.hypershop.testutil.TestContainer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
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
            assertEquals(ShareOutcome.NoLink, c.shares.value)
            val id = c.importShared("Lasagne https://recipes.example/lasagne?utm_source=ig&serves=4", "Best lasagne")!!
            assertEquals(ShareOutcome.Review(id), c.shares.value)
            val job = c.importJobs.get(id)!!
            assertEquals(ImportInput("https://recipes.example/lasagne?serves=4", "Best lasagne"), job.input)
        }
}
