package com.robert4lyf.hypershop.importing

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.claude.ClaudeModel
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.testutil.TestContainer
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ImporterTest {
    @Test
    fun aNotARecipeAnswerIsNotAskedAgain() {
        val claude = MockWebServer().apply { start() }
        try {
            val answer = """{\"status\":\"not_a_recipe\",\"title\":null,\"servings\":null,\"ingredients\":[],\"source_urls\":[]}"""
            claude.enqueue(
                MockResponse().setBody(
                    """{"model":"claude-haiku-5-5","stop_reason":"end_turn","usage":{"input_tokens":900,"output_tokens":30},""" +
                        """"content":[{"type":"text","text":"$answer"}]}""",
                ),
            )
            val c = TestContainer.create(claude = claude, key = "sk-ant-test")
            val base =
                ImportResult(
                    "https://example.com/blog",
                    "A blog post",
                    null,
                    UnitLocale.METRIC,
                    "PAGE_TEXT",
                    pageText = "Some words about a holiday",
                    claudePending = true,
                )
            val r = runBlocking { c.importer.parse(base, allowClaude = true, model = ClaudeModel.HAIKU) }
            assertFalse("Claude answered, so nothing is pending", r.claudePending)
            assertTrue("reopening the review won't call Claude again", r.claudeTried)
        } finally {
            claude.shutdown()
        }
    }

    private val answer = """{\\"status\\":\\"ok\\",\\"title\\":null,\\"servings\\":null,\\"ingredients\\":[],\\"source_urls\\":[]}"""
    private val ok =
        """{"model":"claude-haiku-5-5","stop_reason":"end_turn","usage":{"input_tokens":900,"output_tokens":30},""" +
            """"content":[{"type":"text","text":"$answer"}]}"""

    @Test
    fun theGateAsksClaudeOnlyForLinesTheRulesDoubt() {
        val claude = MockWebServer().apply { start() }
        try {
            val c = TestContainer.create(claude = claude, key = "sk-ant-test")
            runBlocking { c.settings.setClaudeAlways(false) }
            val m = ClaudeModel.HAIKU
            // Every line clear: read offline, nothing pending, no request.
            val clearLines = listOf("200g plain flour", "2 eggs", "1 tsp salt")
            val clear = runBlocking { c.importer.fromLines(clearLines, "Pancakes", allowClaude = true, model = m) }
            assertTrue(clear.claudeSkipped)
            assertFalse(clear.claudePending)
            assertEquals(0, claude.requestCount)
            // A doubtful line ("A or B"): the whole recipe goes to Claude.
            claude.enqueue(MockResponse().setBody(ok))
            val doubt = listOf("200g plain flour", "butter or margarine")
            val doubtful = runBlocking { c.importer.fromLines(doubt, "Pancakes", allowClaude = true, model = m) }
            assertFalse(doubtful.claudeSkipped)
            assertEquals(1, claude.requestCount)
            // The same doubtful recipe in the background: pending for the review, no request.
            val later = runBlocking { c.importer.fromLines(doubt, "Pancakes", allowClaude = false, model = m) }
            assertTrue(later.claudePending)
            assertEquals(1, claude.requestCount)
            // "Ask Claude" on a clear recipe: the gate is overruled.
            claude.enqueue(MockResponse().setBody(ok))
            runBlocking { c.importer.parse(clear.copy(askClaude = true), allowClaude = true, model = m) }
            assertEquals(2, claude.requestCount)
        } finally {
            claude.shutdown()
        }
    }

    @Test
    fun byDefaultClaudeReadsEveryRecipeButOnlyInTheForeground() {
        // W.1: Claude reads fresh recipes better than the rules (U.1), so with a key it reads them all.
        val claude = MockWebServer().apply { start() }
        try {
            val c = TestContainer.create(claude = claude, key = "sk-ant-test")
            val m = ClaudeModel.HAIKU
            val clearLines = listOf("200g plain flour", "2 eggs", "1 tsp salt")
            // A share in the background: nothing is spent, the review will ask.
            val later = runBlocking { c.importer.fromLines(clearLines, "Pancakes", allowClaude = false, model = m) }
            assertTrue(later.claudePending)
            assertFalse(later.claudeSkipped)
            assertEquals(0, claude.requestCount)
            // The review in the foreground: Claude reads it, clear lines and all.
            claude.enqueue(MockResponse().setBody(ok))
            val now = runBlocking { c.importer.fromLines(clearLines, "Pancakes", allowClaude = true, model = m) }
            assertFalse(now.claudeSkipped)
            assertEquals(1, claude.requestCount)
        } finally {
            claude.shutdown()
        }
    }

    @Test
    fun withNoKeyTheRulesReadAloneAndNothingIsPending() {
        val c = TestContainer.create()
        val r =
            runBlocking {
                c.importer.fromLines(
                    listOf("200g plain flour", "2 eggs"),
                    "Pancakes",
                    allowClaude = true,
                    model = ClaudeModel.HAIKU,
                )
            }
        assertTrue(r.claudeSkipped)
        assertFalse(r.claudePending)
    }
}
