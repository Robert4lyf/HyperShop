package com.robert4lyf.hypershop.importing

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.awaitJob
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File

/** Step 5.3 worker tests: Claude is called only while the review screen is in the foreground. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34, 36])
@SQLiteMode(SQLiteMode.Mode.LEGACY)
class ImportWorkerTest {
    private lateinit var pages: MockWebServer
    private lateinit var claude: MockWebServer
    private val page = File("../../testdata/pages/bbcgoodfood-chocolate-cake.html").readText()

    @Before
    fun up() {
        pages = MockWebServer().apply { start() }
        claude = MockWebServer().apply { start() }
    }

    @After
    fun down() {
        pages.shutdown()
        claude.shutdown()
    }

    private fun servePage() = pages.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody(page))

    @Test
    fun inTheBackgroundItParsesWithRulesAndNeverCallsClaude() =
        runBlocking {
            val c = TestContainer.create(pages, claude, key = "sk-ant-test")
            servePage()
            val id = c.importJobs.enqueue(ImportInput(pages.url("/recipes/easy-chocolate-cake").toString(), "Cake"))
            val job = c.awaitJob(id)
            assertEquals(JobState.READY, job.state)
            val r = job.result!!
            assertEquals(ParsedBy.RULES, r.parsedBy)
            assertTrue("marked for Claude when the review opens", r.claudePending)
            assertEquals(16, r.lines.size)
            assertEquals("Easy chocolate cake", r.title)
            assertEquals(0, claude.requestCount)
        }

    @Test
    fun withTheReviewScreenOpenItUsesClaude() =
        runBlocking {
            val c = TestContainer.create(pages, claude, key = "sk-ant-test")
            c.reviewGate.acquire()
            servePage()
            claude.enqueue(MockResponse().setBody(CLAUDE_OK))
            val id = c.importJobs.enqueue(ImportInput(pages.url("/r").toString()))
            val r = c.awaitJob(id).result!!
            assertEquals(ParsedBy.CLAUDE, r.parsedBy)
            assertEquals(listOf("caster sugar"), r.lines.map { it.name })
            assertEquals(1, claude.requestCount)
            assertEquals(1, c.usageHistory().single().calls)
        }

    @Test
    fun withoutAKeyItNeverMarksClaudePending() =
        runBlocking {
            val c = TestContainer.create(pages, claude, key = null)
            c.reviewGate.acquire()
            servePage()
            val r = c.awaitJob(c.importJobs.enqueue(ImportInput(pages.url("/r").toString()))).result!!
            assertEquals(ParsedBy.RULES, r.parsedBy)
            assertEquals(false, r.claudePending)
            assertEquals(0, claude.requestCount)
        }

    @Test
    fun blockedPagesFail() =
        runBlocking {
            val c = TestContainer.create(pages, claude)
            pages.enqueue(MockResponse().setResponseCode(302).setHeader("Location", "http://10.0.0.1/"))
            val job = c.awaitJob(c.importJobs.enqueue(ImportInput(pages.url("/r").toString())))
            assertEquals(JobState.FAILED, job.state)
            assertEquals(ImportError.BLOCKED, job.result!!.error)
        }

    private companion object {
        val CLAUDE_OK = File("../../testdata/claude/synthetic-ok-cake.json").readText()
    }
}
