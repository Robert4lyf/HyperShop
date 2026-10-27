package com.robert4lyf.hypershop.claude

import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.model.Rational
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit

/** Every stop_reason and every error path, against MockWebServer (Step 4 check). */
class ClaudeClientTest {
    private lateinit var server: MockWebServer
    private val recorded = mutableListOf<Pair<String, Usage>>()
    private val log = RingLog()
    private val key = "sk-ant-api03-" + "x".repeat(40)

    @BeforeEach
    fun up() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun down() {
        runCatching { server.shutdown() }
    }

    private fun TestScope.client(k: String? = key) =
        ClaudeClient(
            apiKey = { k },
            baseUrl = server.url("").toString().trimEnd('/'),
            usageSink = { m, u -> recorded += m to u },
            log = log,
            io = StandardTestDispatcher(testScheduler),
        )

    private val input = ClaudeInput.Lines(listOf("2 onions, chopped", "1 tbsp olive oil"), title = "Soup")

    private fun output(
        ingredients: String = ING,
        urls: String = "[]",
    ) = """{"status":"ok","title":"Soup","servings":4,"ingredients":[$ingredients],"source_urls":$urls}"""

    private fun message(
        stop: String = "end_turn",
        text: String? = output(),
        model: String = "claude-haiku-5-5",
    ): String {
        val content =
            buildJsonArray {
                add(
                    buildJsonObject {
                        put("type", "thinking")
                        put("thinking", "")
                    },
                )
                if (text != null) {
                    add(
                        buildJsonObject {
                            put("type", "text")
                            put("text", text)
                        },
                    )
                }
            }
        return buildJsonObject {
            put("model", model)
            put("stop_reason", stop)
            put("content", content)
            put(
                "usage",
                buildJsonObject {
                    put("input_tokens", 1200)
                    put("output_tokens", 900)
                    put("cache_read_input_tokens", 0)
                },
            )
        }.toString()
    }

    private fun ok(body: String) = MockResponse().setResponseCode(200).setBody(body)

    @Test
    fun aKeyThatCantBeAHeaderIsABadKeyAndNeverSent() =
        runTest {
            val c = client("sk-\u200btest\u00e9")
            assertEquals(ClaudeResult.Failed(ClaudeResult.Reason.BAD_KEY), c.parse(input, ClaudeModel.HAIKU))
            assertEquals(KeyCheck.BAD_KEY, c.testKey("sk-\u200btest"))
            assertEquals(0, server.requestCount)
        }

    @Test
    fun endTurnParsesAndRecordsUsage() =
        runTest {
            server.enqueue(ok(message()))
            val r = client().parse(input, ClaudeModel.HAIKU) as ClaudeResult.Parsed
            assertEquals(2, r.recipe.ingredients.size)
            val onion = r.recipe.ingredients[0]
            assertEquals("onion", onion.name)
            assertEquals(Rational.of(2), onion.quantity)
            assertEquals(AisleCode.PRODUCE, onion.aisleSuggestion)
            assertEquals(QtyUnit.TBSP, r.recipe.ingredients[1].unit)
            assertEquals(4, r.recipe.servings)
            assertEquals(listOf("claude-haiku-5-5" to Usage(1200, 900)), recorded)
            val req = server.takeRequest()
            assertEquals("/v1/messages", req.path)
            assertEquals(key, req.getHeader("x-api-key"))
            assertEquals("2023-06-01", req.getHeader("anthropic-version"))
            val sent = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
            assertEquals("low", sent["output_config"]!!.jsonObject["effort"]!!.jsonPrimitive.content)
        }

    @Test
    fun refusalFallsBackToRules() =
        runTest {
            server.enqueue(ok(message(stop = "refusal", text = null)))
            val r = client().parse(input, ClaudeModel.SONNET)
            assertTrue(r is ClaudeResult.Refused)
            assertEquals("server-side-fallback-2026-07-01", server.takeRequest().getHeader("anthropic-beta"))
        }

    @Test
    fun maxTokensIsAnError() =
        runTest {
            server.enqueue(ok(message(stop = "max_tokens", text = "{\"status\":\"ok\",\"ingre")))
            assertEquals(ClaudeResult.Reason.MAX_TOKENS, (client().parse(input, ClaudeModel.HAIKU) as ClaudeResult.Failed).reason)
            assertEquals(1, recorded.size, "usage is logged even when the response is unusable")
        }

    @Test
    fun unknownStopReasonIsInvalid() =
        runTest {
            server.enqueue(ok(message(stop = "tool_use")))
            assertEquals(ClaudeResult.Reason.INVALID_RESPONSE, (client().parse(input, ClaudeModel.HAIKU) as ClaudeResult.Failed).reason)
        }

    @Test
    fun schemaViolationsAreRejected() =
        runTest {
            val missing = ING.replace(",\"estimated\":false", "")
            val extra = ING.replace("\"optional\":false", "\"optional\":false,\"price\":2")
            for (bad in listOf(output(missing), output(extra), "not json", output().replace("\"ok\"", "\"maybe\""))) {
                server.enqueue(ok(message(text = bad)))
                val r = client().parse(input, ClaudeModel.HAIKU)
                assertEquals(ClaudeResult.Reason.INVALID_RESPONSE, (r as ClaudeResult.Failed).reason, bad)
            }
            server.enqueue(ok("<html>"))
            assertEquals(ClaudeResult.Reason.INVALID_RESPONSE, (client().parse(input, ClaudeModel.HAIKU) as ClaudeResult.Failed).reason)
        }

    @Test
    fun rangesAreCheckedInKotlin() =
        runTest {
            val wild = ING.replaceFirst("\"quantity\":2,\"quantity_max\":null", "\"quantity\":-3,\"quantity_max\":1e9")
            server.enqueue(ok(message(text = output(wild))))
            val r = client().parse(input, ClaudeModel.HAIKU) as ClaudeResult.Parsed
            val onion = r.recipe.ingredients[0]
            assertNull(onion.quantity)
            assertNull(onion.quantityMax)
            assertTrue(onion.check)
        }

    @Test
    fun sourceUrlsMustBeVerbatimAndHttps() =
        runTest {
            val page = ClaudeInput.PageText("Full recipe at https://example.com/soup and http://plain.example.com/x")
            server.enqueue(
                ok(message(text = output(urls = """["https://example.com/soup","http://plain.example.com/x","https://evil.example/"]"""))),
            )
            val r = client().parse(page, ClaudeModel.HAIKU) as ClaudeResult.Parsed
            assertEquals(listOf("https://example.com/soup"), r.recipe.sourceUrls)
        }

    @Test
    fun badKey() =
        runTest {
            for (code in listOf(401, 403)) {
                server.enqueue(MockResponse().setResponseCode(code))
                assertEquals(ClaudeResult.Reason.BAD_KEY, (client().parse(input, ClaudeModel.HAIKU) as ClaudeResult.Failed).reason)
            }
            assertEquals(ClaudeResult.Reason.NO_KEY, (client(k = null).parse(input, ClaudeModel.HAIKU) as ClaudeResult.Failed).reason)
        }

    @Test
    fun rateLimitAndOverloadBackOffThenSucceedOrGiveUp() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(429).setHeader("retry-after", "3"))
            server.enqueue(MockResponse().setResponseCode(529))
            server.enqueue(ok(message()))
            assertTrue(client().parse(input, ClaudeModel.HAIKU) is ClaudeResult.Parsed)
            assertEquals(3, server.requestCount)
            repeat(3) { server.enqueue(MockResponse().setResponseCode(529)) }
            assertEquals(ClaudeResult.Reason.OVERLOADED, (client().parse(input, ClaudeModel.HAIKU) as ClaudeResult.Failed).reason)
            repeat(3) { server.enqueue(MockResponse().setResponseCode(429).setHeader("retry-after", "1")) }
            val r = client().parse(input, ClaudeModel.HAIKU) as ClaudeResult.Failed
            assertEquals(ClaudeResult.Reason.RATE_LIMITED, r.reason)
            assertEquals(1L, r.retryAfterSeconds)
        }

    @Test
    fun serverErrorRetriedOnce() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(500))
            server.enqueue(ok(message()))
            assertTrue(client().parse(input, ClaudeModel.HAIKU) is ClaudeResult.Parsed)
            server.enqueue(MockResponse().setResponseCode(502))
            server.enqueue(MockResponse().setResponseCode(503))
            assertEquals(ClaudeResult.Reason.SERVER, (client().parse(input, ClaudeModel.HAIKU) as ClaudeResult.Failed).reason)
            server.enqueue(MockResponse().setResponseCode(400))
            assertEquals(ClaudeResult.Reason.REQUEST, (client().parse(input, ClaudeModel.HAIKU) as ClaudeResult.Failed).reason)
        }

    @Test
    fun networkErrorsMeanOffline() =
        runTest {
            val c = client()
            server.shutdown()
            assertEquals(ClaudeResult.Reason.OFFLINE, (c.parse(input, ClaudeModel.HAIKU) as ClaudeResult.Failed).reason)
            assertEquals(KeyCheck.OFFLINE, c.testKey(key))
        }

    @Test
    fun testKeyUsesTheFreeModelsEndpoint() =
        runTest {
            server.enqueue(MockResponse().setResponseCode(200).setBody("{\"data\":[]}"))
            server.enqueue(MockResponse().setResponseCode(401))
            assertEquals(KeyCheck.OK, client().testKey(key))
            assertEquals(KeyCheck.BAD_KEY, client().testKey("sk-ant-wrong"))
            val r = server.takeRequest()
            assertEquals("GET", r.method)
            assertEquals("/v1/models", r.path)
        }

    @Test
    fun logsAreRedacted() =
        runTest {
            server.enqueue(ok(message()))
            client().parse(input, ClaudeModel.HAIKU)
            log.log("leak $key x-api-key: $key https://example.com/p?token=abc")
            val all = log.snapshot().joinToString("\n")
            assertFalse(all.contains(key))
            assertFalse(all.contains("token=abc"))
            assertFalse(all.contains("onions"), "bodies are never logged")
        }

    @Test
    fun aStreamedReplyStillNeedsAWholeCallBudget() {
        // Streamed: a minute with no bytes is a dead connection, but a long Opus answer needs minutes in all.
        val h = ClaudeClient.defaultHttp()
        assertTrue(h.readTimeoutMillis < h.callTimeoutMillis)
        assertTrue(h.callTimeoutMillis >= 180_000)
    }

    /** The Messages API event stream for [text], as the real API sends it. */
    private fun sse(
        text: String = output(),
        stop: String = "end_turn",
        model: String = "claude-haiku-5-5",
        outputTokens: Long = 900,
        /** Unknown-key padding in the first event, so a throttled server sends it whole before stalling. */
        padding: Int = 0,
    ): String =
        buildString {
            append("event: message_start\n")
            append(
                "data: {\"type\":\"message_start\",\"pad\":\"${"x".repeat(padding)}\"," +
                    "\"message\":{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\"," +
                    "\"model\":\"$model\",\"content\":[],\"stop_reason\":null,\"usage\":{\"input_tokens\":1200,\"output_tokens\":1," +
                    "\"cache_creation_input_tokens\":10,\"cache_read_input_tokens\":20}}}\n\n",
            )
            append(
                "event: content_block_start\n" +
                    "data: {\"type\":\"content_block_start\",\"index\":0,\"content_block\":{\"type\":\"text\",\"text\":\"\"}}\n\n",
            )
            // The text in three deltas, as a real stream chops it.
            for (part in text.chunked((text.length + 2) / 3)) {
                val escaped = Json.encodeToString(String.serializer(), part)
                append(
                    "event: content_block_delta\n" +
                        "data: {\"type\":\"content_block_delta\",\"index\":0,\"delta\":{\"type\":\"text_delta\",\"text\":$escaped}}\n\n",
                )
            }
            append("event: content_block_stop\ndata: {\"type\":\"content_block_stop\",\"index\":0}\n\n")
            append(
                "event: message_delta\n" +
                    "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"$stop\",\"stop_sequence\":null}," +
                    "\"usage\":{\"output_tokens\":$outputTokens}}\n\n",
            )
            append("event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n")
        }

    private fun stream(body: String) = MockResponse().setResponseCode(200).setHeader("content-type", "text/event-stream").setBody(body)

    @Test
    fun aStreamedReplyIsReadLikeAPlainOne() =
        runTest {
            server.enqueue(stream(sse()))
            val r = client().parse(input, ClaudeModel.HAIKU) as ClaudeResult.Parsed
            assertEquals(2, r.recipe.ingredients.size)
            assertEquals("onion", r.recipe.ingredients[0].name)
            assertEquals(listOf("claude-haiku-5-5" to Usage(1200, 900, 10, 20)), recorded)
            // The request asked for the stream.
            val sent = Json.parseToJsonElement(server.takeRequest().body.readUtf8()).jsonObject
            assertEquals("true", sent["stream"]!!.jsonPrimitive.content)
        }

    @Test
    fun aStreamedRefusalAndMaxTokensReadTheStopReasonFirst() =
        runTest {
            server.enqueue(stream(sse(stop = "refusal")))
            assertTrue(client().parse(input, ClaudeModel.HAIKU) is ClaudeResult.Refused)
            server.enqueue(stream(sse(stop = "max_tokens")))
            assertEquals(ClaudeResult.Reason.MAX_TOKENS, (client().parse(input, ClaudeModel.HAIKU) as ClaudeResult.Failed).reason)
            // Both were billed, both metered.
            assertEquals(2, recorded.size)
        }

    @Test
    fun aStreamThatDropsMidAnswerStillMetersWhatWasBilled() =
        runTest {
            // The answer started (input tokens known, some text sent), then the connection went.
            server.enqueue(stream(sse()).setSocketPolicy(SocketPolicy.DISCONNECT_DURING_RESPONSE_BODY))
            val r = client().parse(input, ClaudeModel.HAIKU) as ClaudeResult.Failed
            assertEquals(ClaudeResult.Reason.OFFLINE, r.reason)
            val (model, usage) = recorded.single()
            assertEquals("claude-haiku-5-5", model)
            assertEquals(1200, usage.inputTokens)
            assertEquals(10, usage.cacheWriteTokens)
            // Half the body went: the output is estimated from the text that arrived, never zero.
            assertTrue(usage.outputTokens > 0, "$usage")
            assertEquals(r.usage, usage)
        }

    @Test
    fun aStreamThatStallsIsATimeoutNotOffline() {
        // Bytes stop coming after the first event; a short read timeout stands in for the real minute.
        // MockWebServer throttles the request body it reads with the same figure, so the first chunk must be
        // bigger than the request (a few KB of prompt and schema): the padding makes the first event 64 KB.
        val body = sse(padding = 64 * 1024)
        val firstEvent = body.indexOf("event: content_block_start").toLong()
        server.enqueue(stream(body).throttleBody(firstEvent, 10, TimeUnit.SECONDS))
        val http = ClaudeClient.defaultHttp().newBuilder().readTimeout(2, TimeUnit.SECONDS).build()
        val c =
            ClaudeClient(
                apiKey = { key },
                baseUrl = server.url("").toString().trimEnd('/'),
                http = http,
                usageSink = { m, u -> recorded += m to u },
                log = log,
            )
        val r = runBlocking { withTimeout(20_000) { c.parse(input, ClaudeModel.HAIKU) } } as ClaudeResult.Failed
        assertEquals(ClaudeResult.Reason.TIMEOUT, r.reason, log.snapshot().joinToString("\n"))
        assertEquals(1200, recorded.single().second.inputTokens)
    }

    @Test
    fun aCallCancelledAfterTheAnswerStartedStillRecordsItsUsage() {
        // The first event (the input tokens) arrives, then the stream stalls and the caller leaves
        // ("Paste the ingredients", the review closed): the generation is billed, so the meter gets it.
        val body = sse(padding = 64 * 1024)
        val firstEvent = body.indexOf("event: content_block_start").toLong()
        server.enqueue(stream(body).throttleBody(firstEvent, 30, TimeUnit.SECONDS))
        val c =
            ClaudeClient(
                apiKey = { key },
                baseUrl = server.url("").toString().trimEnd('/'),
                usageSink = { m, u -> synchronized(recorded) { recorded += m to u } },
                log = log,
            )
        runBlocking {
            val job = launch { c.parse(input, ClaudeModel.HAIKU) }
            server.takeRequest(5, TimeUnit.SECONDS)
            delay(1_500)
            job.cancel()
            withTimeout(5_000) { job.join() }
            withTimeout(5_000) { while (synchronized(recorded) { recorded.isEmpty() }) delay(50) }
        }
        val (model, usage) = synchronized(recorded) { recorded.single() }
        assertEquals("claude-haiku-5-5", model)
        assertEquals(1200, usage.inputTokens)
    }

    @Test
    fun aStreamOfTheWrongShapeIsUnreadableNotAHang() =
        runTest {
            // "usage": null where an object is due, then "message": 5: neither may leave the caller waiting.
            val odd =
                "event: message_start\ndata: {\"type\":\"message_start\",\"message\":{\"model\":\"m\",\"usage\":null}}\n\n" +
                    "event: message_start\ndata: {\"type\":\"message_start\",\"message\":5}\n\n" +
                    "event: message_stop\ndata: {\"type\":\"message_stop\"}\n\n"
            server.enqueue(stream(odd))
            // runTest's own timeout is the hang guard (a real withTimeout here would run on virtual time).
            val r = client().parse(input, ClaudeModel.HAIKU) as ClaudeResult.Failed
            assertEquals(ClaudeResult.Reason.INVALID_RESPONSE, r.reason)
        }

    @Test
    fun aStreamedErrorEventFollowsTheHttpRules() =
        runTest {
            // Overloaded mid-stream, then fine: the back-off and retry are the same as for a 529.
            server.enqueue(
                stream("event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\",\"message\":\"busy\"}}\n\n"),
            )
            server.enqueue(stream(sse()))
            assertTrue(client().parse(input, ClaudeModel.HAIKU) is ClaudeResult.Parsed)
            assertEquals(2, server.requestCount)
        }

    @Test
    fun anErrorEventAfterTheAnswerStartedStillMetersItsInput() =
        runTest {
            // Review C.32: message_start was read (the input tokens are known and billed), then the server gave up.
            val started = sse().substringBefore("event: content_block_start")
            server.enqueue(stream(started + "event: error\ndata: {\"type\":\"error\",\"error\":{\"type\":\"overloaded_error\"}}\n\n"))
            server.enqueue(stream(sse()))
            assertTrue(client().parse(input, ClaudeModel.HAIKU) is ClaudeResult.Parsed)
            assertEquals(2, recorded.size, "$recorded")
            assertEquals("claude-haiku-5-5", recorded.first().first)
            assertEquals(1200, recorded.first().second.inputTokens)
            assertEquals(10, recorded.first().second.cacheWriteTokens)
        }

    @Test
    fun ringLogKeepsTheLast500() {
        val l = RingLog()
        repeat(600) { l.log("line $it") }
        assertEquals(500, l.snapshot().size)
        assertEquals("line 599", l.snapshot().last())
    }

    private companion object {
        private const val TAIL = "\"note\":null,\"section\":null,\"optional\":false,\"estimated\":false"
        val ING =
            listOf(
                """{"raw":"2 onions, chopped","name":"onion","quantity":2,"quantity_max":null,"quantity_text":"2",""" +
                    """"unit":null,"alt_quantity":null,"alt_unit":null,""" +
                    """"note":"chopped","section":null,"optional":false,"estimated":false,"aisle_suggestion":"PRODUCE"}""",
                """{"raw":"1 tbsp olive oil","name":"olive oil","quantity":1,"quantity_max":null,"quantity_text":"1",""" +
                    """"unit":"tbsp","alt_quantity":null,"alt_unit":null,$TAIL,"aisle_suggestion":"OILS_VINEGARS"}""",
            ).joinToString(",")
    }

    @Test
    fun cancellingTheCallerCancelsTheRequest() {
        // A server that never answers: the call would otherwise block its thread for the whole 180 s budget.
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val c =
            ClaudeClient(
                apiKey = { key },
                baseUrl = server.url("").toString().trimEnd('/'),
                usageSink = { m, u -> recorded += m to u },
                log = log,
            )
        runBlocking {
            val job = launch { c.parse(input, ClaudeModel.HAIKU) }
            server.takeRequest(5, TimeUnit.SECONDS)
            job.cancel()
            withTimeout(5_000) { job.join() }
        }
        assertTrue(recorded.isEmpty())
    }

    @Test
    fun aDroppedConnectionIsNeverRetriedByOkHttpItself() {
        // The client's own policy decides what is retried; a silent re-POST would bill a second generation.
        assertFalse(ClaudeClient.defaultHttp().retryOnConnectionFailure)
    }
}
