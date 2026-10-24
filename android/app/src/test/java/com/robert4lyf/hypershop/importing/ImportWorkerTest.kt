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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.SQLiteMode
import java.io.File
import java.util.concurrent.TimeUnit

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

    /** A page whose one line the rule parser cannot settle ("juice of"): the hybrid gate wants Claude for it. */
    private fun serveDoubtfulPage() =
        pages.enqueue(
            MockResponse().setHeader("Content-Type", "text/html").setBody(
                """<html><head><title>Lemon drizzle</title><script type="application/ld+json">{"@context":"https://schema.org",""" +
                    """"@type":"Recipe","name":"Lemon drizzle","recipeIngredient":["200g golden caster sugar","juice of 1 lemon"]}""" +
                    """</script></head><body></body></html>""",
            ),
        )

    @Test
    fun setToOnlyWhenUnsureAClearRecipeIsReadByRulesAlone() =
        runBlocking {
            // Every line of the cake is read without doubt: with Claude set to read only when unsure (W.1), the gate
            // keeps the rule parser's reading.
            val c = TestContainer.create(pages, claude, key = "sk-ant-test")
            c.settings.setClaudeAlways(false)
            c.reviewGate.acquire()
            servePage()
            val id = c.importJobs.enqueue(ImportInput(pages.url("/recipes/easy-chocolate-cake").toString(), "Cake"))
            val job = c.awaitJob(id)
            assertEquals(JobState.READY, job.state)
            val r = job.result!!
            assertEquals(ParsedBy.RULES, r.parsedBy)
            assertFalse("nothing pending: Claude was not needed", r.claudePending)
            assertTrue(r.claudeSkipped)
            assertEquals(16, r.lines.size)
            assertEquals("Easy chocolate cake", r.title)
            assertEquals(0, claude.requestCount)
        }

    @Test
    fun inTheBackgroundADoubtfulLineIsLeftPendingForTheReview() =
        runBlocking {
            val c = TestContainer.create(pages, claude, key = "sk-ant-test")
            serveDoubtfulPage()
            val r = c.awaitJob(c.importJobs.enqueue(ImportInput(pages.url("/r").toString()))).result!!
            assertEquals(ParsedBy.RULES, r.parsedBy)
            assertTrue("marked for Claude when the review opens", r.claudePending)
            assertFalse(r.claudeSkipped)
            assertEquals(0, claude.requestCount)
        }

    @Test
    fun withTheReviewScreenOpenItUsesClaude() =
        runBlocking {
            val c = TestContainer.create(pages, claude, key = "sk-ant-test")
            c.reviewGate.acquire()
            serveDoubtfulPage()
            claude.enqueue(MockResponse().setBody(CLAUDE_OK))
            val id = c.importJobs.enqueue(ImportInput(pages.url("/r").toString()))
            val r = c.awaitJob(id).result!!
            assertEquals(ParsedBy.CLAUDE, r.parsedBy)
            assertEquals(listOf("caster sugar"), r.lines.map { it.name })
            assertEquals(1, claude.requestCount)
            assertEquals(1, c.usageHistory().single().calls)
        }

    @Test
    fun aReviewClosedDuringTheFetchMeansNoClaudeCall() =
        runBlocking {
            // The share arrives with the review open, the page takes its time, the user leaves: the gate
            // is read when Claude would be called, not when the share arrived, so nothing is spent.
            val c = TestContainer.create(pages, claude, key = "sk-ant-test")
            c.reviewGate.acquire()
            pages.enqueue(
                MockResponse().setHeader("Content-Type", "text/html").setBodyDelay(1500, TimeUnit.MILLISECONDS).setBody(
                    """<html><head><title>Lemon drizzle</title><script type="application/ld+json">{"@context":"https://schema.org",""" +
                        """"@type":"Recipe","name":"Lemon drizzle","recipeIngredient":["200g golden caster sugar","juice of 1 lemon"]}""" +
                        """</script></head><body></body></html>""",
                ),
            )
            claude.enqueue(MockResponse().setBody(CLAUDE_OK))
            val id = c.importJobs.enqueue(ImportInput(pages.url("/r").toString()))
            pages.takeRequest(5, TimeUnit.SECONDS)
            c.reviewGate.release()
            val r = c.awaitJob(id).result!!
            assertEquals(0, claude.requestCount)
            assertTrue(r.claudePending)
            assertEquals(ParsedBy.RULES, r.parsedBy)
        }

    @Test
    fun withoutAKeyItNeverMarksClaudePending() =
        runBlocking {
            val c = TestContainer.create(pages, claude, key = null)
            c.reviewGate.acquire()
            serveDoubtfulPage()
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
