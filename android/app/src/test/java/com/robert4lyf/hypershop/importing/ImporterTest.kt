package com.robert4lyf.hypershop.importing

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.claude.ClaudeModel
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.testutil.TestContainer
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
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
}
