package com.robert4lyf.hypershop.claude

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.io.InterruptedIOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Records every response's token use (`ClaudeUsage` in `:data`). */
fun interface UsageSink {
    suspend fun record(
        model: String,
        usage: Usage,
    )
}

enum class KeyCheck { OK, BAD_KEY, OFFLINE, ERROR }

/**
 * The Messages API client (PLAN §3.7, ADR 001). One call per import:
 * - 401/403 → [ClaudeResult.Reason.BAD_KEY];
 * - 429 and 529 back off (honouring `retry-after`) and try again, twice;
 * - a 5xx is retried once;
 * - an IOException before any reply is [ClaudeResult.Reason.OFFLINE].
 * `stop_reason` is read before `content`.
 *
 * The reply is **streamed** (KNOWN_ISSUES #10): the usage arrives in the first
 * event and the text in deltas, so a connection that drops or stalls after the
 * answer started still records what Anthropic bills (the input tokens as
 * reported, the output as far as it got) and is reported as
 * [ClaudeResult.Reason.TIMEOUT] or OFFLINE rather than silently under-counted.
 * A plain JSON reply (tests, a proxy that buffers) is read as before.
 */
class ClaudeClient(
    private val apiKey: suspend () -> String?,
    private val baseUrl: String = "https://api.anthropic.com",
    private val http: OkHttpClient = defaultHttp(),
    private val usageSink: UsageSink = UsageSink { _, _ -> },
    private val log: RingLog = RingLog(),
    private val io: CoroutineDispatcher = Dispatchers.IO,
) {
    private val strict = Json { ignoreUnknownKeys = false }

    /** Records the usage of a call whose caller was cancelled mid-answer (see [send]). */
    private val spill = CoroutineScope(SupervisorJob() + io)
    private val lenient = Json { ignoreUnknownKeys = true }

    suspend fun parse(
        input: ClaudeInput,
        model: ClaudeModel,
    ): ClaudeResult {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return ClaudeResult.Failed(ClaudeResult.Reason.NO_KEY)
        // OkHttp rejects such a header with an exception that quotes the value, i.e. the key.
        if (!headerSafe(key)) return ClaudeResult.Failed(ClaudeResult.Reason.BAD_KEY)
        val body = JsonObject(ClaudeRequest.body(input, model) + ("stream" to JsonPrimitive(true))).toString()
        var backoffs = 0
        var serverRetries = 0
        while (true) {
            val outcome = withContext(io) { send(key, model, body) }
            when (outcome) {
                is Outcome.Offline -> return ClaudeResult.Failed(ClaudeResult.Reason.OFFLINE)
                is Outcome.Interrupted -> {
                    // The answer had started, so it is billed: the meter gets what is known.
                    outcome.usage?.let { u -> withContext(NonCancellable) { usageSink.record(outcome.model ?: model.id, u) } }
                    return ClaudeResult.Failed(
                        if (outcome.timedOut) ClaudeResult.Reason.TIMEOUT else ClaudeResult.Reason.OFFLINE,
                        outcome.usage,
                    )
                }
                is Outcome.Http -> {
                    val code = outcome.code
                    // An error event after the answer started (review C.32): the input was read, so it is metered.
                    outcome.usage?.let { u -> withContext(NonCancellable) { usageSink.record(outcome.model ?: model.id, u) } }
                    when {
                        code == 200 -> return handle(outcome.body, input, model)
                        code == 401 || code == 403 -> return ClaudeResult.Failed(ClaudeResult.Reason.BAD_KEY)
                        code == 429 || code == 529 -> {
                            val reason = if (code == 429) ClaudeResult.Reason.RATE_LIMITED else ClaudeResult.Reason.OVERLOADED
                            if (backoffs >= MAX_BACKOFFS) return ClaudeResult.Failed(reason, retryAfterSeconds = outcome.retryAfter)
                            delay(((outcome.retryAfter ?: BACKOFF_S[backoffs]).coerceAtMost(MAX_WAIT_S)) * 1000)
                            backoffs++
                        }
                        code >= 500 -> {
                            if (serverRetries >= 1) return ClaudeResult.Failed(ClaudeResult.Reason.SERVER)
                            serverRetries++
                            delay(1000)
                        }
                        else -> return ClaudeResult.Failed(ClaudeResult.Reason.REQUEST)
                    }
                }
            }
        }
    }

    /** "Test key": `GET /v1/models` costs nothing. */
    suspend fun testKey(key: String): KeyCheck =
        withContext(io) {
            if (!headerSafe(key)) return@withContext KeyCheck.BAD_KEY
            val req =
                Request.Builder()
                    .url("$baseUrl/v1/models")
                    .header("x-api-key", key)
                    .header("anthropic-version", ClaudeRequest.API_VERSION)
                    .get()
                    .build()
            try {
                http.newCall(req).execute().use {
                    log.log("GET ${req.url} → ${it.code}")
                    when (it.code) {
                        200 -> KeyCheck.OK
                        401, 403 -> KeyCheck.BAD_KEY
                        else -> KeyCheck.ERROR
                    }
                }
            } catch (e: IOException) {
                KeyCheck.OFFLINE
            }
        }

    /** Printable ASCII, the only thing an HTTP header value can hold. */
    private fun headerSafe(key: String) = key.all { it in '\u0021'..'\u007e' }

    private sealed interface Outcome {
        /** No reply at all (no network, or the connection failed before any byte came back). */
        data object Offline : Outcome

        /** The reply started and then stopped: [usage] is what was known by then (null when not even that). */
        data class Interrupted(
            val timedOut: Boolean,
            val model: String?,
            val usage: Usage?,
        ) : Outcome

        /**
         * A whole reply: [body] is the message as JSON (assembled from the stream, or as sent). An `error`
         * event after the answer started carries the [usage] known by then, with its [model]: it is billed.
         */
        data class Http(
            val code: Int,
            val body: String,
            val retryAfter: Long?,
            val model: String? = null,
            val usage: Usage? = null,
        ) : Outcome
    }

    /**
     * One POST. Cancelling the caller cancels the call: leaving the review screen
     * two seconds into a long Opus answer must not keep a thread, the socket and
     * the spend reservation for the rest of the 180 s budget.
     */
    private suspend fun send(
        key: String,
        model: ClaudeModel,
        body: String,
    ): Outcome {
        val b = Request.Builder().url("$baseUrl/v1/messages").post(body.toRequestBody(JSON))
        ClaudeRequest.headers(key, model).forEach { (k, v) -> if (k != "content-type") b.header(k, v) }
        val req = b.build()
        val call = http.newCall(req)
        return suspendCancellableCoroutine { cont ->
            cont.invokeOnCancellation { call.cancel() }

            // The caller gone (the review left, "Paste the ingredients" instead) after the
            // answer started: the generation is billed all the same, so the meter still gets
            // it, here, since a resume of a cancelled continuation reaches no one.
            fun deliver(outcome: Outcome) {
                if (cont.isCancelled) {
                    val cost =
                        when (outcome) {
                            is Outcome.Interrupted -> outcome.usage?.let { (outcome.model ?: model.id) to it }
                            // An error after the answer started, or a whole answer that landed after the caller
                            // left (review C.32): billed as much as any other.
                            is Outcome.Http ->
                                if (outcome.usage != null) {
                                    (outcome.model ?: model.id) to outcome.usage
                                } else if (outcome.code == 200) {
                                    billed(outcome.body)?.let { (m, u) -> (m ?: model.id) to u }
                                } else {
                                    null
                                }
                            Outcome.Offline -> null
                        }
                    cost?.let { (m, u) -> spill.launch { usageSink.record(m, u) } }
                }
                cont.resume(outcome)
            }
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (!call.isCanceled()) log.log("POST ${req.url} failed: ${e.javaClass.simpleName}")
                        // After a cancellation the continuation is already done; this is then a no-op.
                        deliver(Outcome.Offline)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        // Whatever the reply holds, the caller is resumed: an exception out of here
                        // would be swallowed by OkHttp and leave the review waiting for ever.
                        val outcome =
                            try {
                                response.use { r ->
                                    // Status only: request and response bodies never reach the log.
                                    log.log("POST ${req.url} ${model.id} → ${r.code}")
                                    val streamed = r.header("content-type").orEmpty().contains("text/event-stream")
                                    val retryAfter = r.header("retry-after")?.toLongOrNull()
                                    if (r.code == 200 && streamed) {
                                        readStream(r)
                                    } else {
                                        // A plain reply is read up to a cap: a body with no end must not fill memory.
                                        Outcome.Http(r.code, r.peekBody(MAX_BODY_BYTES).string(), retryAfter)
                                    }
                                }
                            } catch (e: IOException) {
                                log.log("POST ${req.url} body failed: ${e.javaClass.simpleName}")
                                Outcome.Offline
                            } catch (e: RuntimeException) {
                                // A reply of an unexpected shape: an answer that can't be read, not a hang.
                                log.log("POST ${req.url} reply unreadable: ${e.javaClass.simpleName}")
                                Outcome.Http(200, "", null)
                            }
                        deliver(outcome)
                    }
                },
            )
        }
    }

    /**
     * Reads a Messages API event stream into one [Outcome]: the usage and model
     * from `message_start`, the text from the deltas, the stop reason and the
     * output tokens from `message_delta`. A stream that ends early is
     * [Outcome.Interrupted] with the usage known so far (the output counted from
     * the text received when the final count never came). An `error` event is
     * an HTTP-style outcome, so the retry rules apply as to a plain reply.
     */
    private fun readStream(r: Response): Outcome {
        val source = r.body?.source() ?: return Outcome.Offline
        var model: String? = null
        var inputTokens = 0L
        var cacheWrite = 0L
        var cacheRead = 0L
        var outputTokens: Long? = null
        var stopReason: String? = null
        var started = false
        var stopped = false
        val text = StringBuilder()
        var event: String? = null
        try {
            while (true) {
                // A line, or a text, with no end in sight is not an answer: read up to a cap, then give up.
                val line =
                    try {
                        source.readUtf8LineStrict(MAX_LINE_BYTES)
                    } catch (e: java.io.EOFException) {
                        if (source.exhausted()) break
                        log.log("stream line over ${MAX_LINE_BYTES} bytes")
                        return Outcome.Http(200, "", null)
                    }
                if (text.length > MAX_TEXT_CHARS) {
                    log.log("stream text over ${MAX_TEXT_CHARS} chars")
                    return Outcome.Http(200, "", null)
                }
                when {
                    line.startsWith("event:") -> event = line.removePrefix("event:").trim()
                    line.startsWith("data:") -> {
                        val payload = line.removePrefix("data:").trim()
                        val data = runCatching { lenient.parseToJsonElement(payload).jsonObject }.getOrNull() ?: continue
                        // An event of the wrong shape (a null where an object is due) is skipped, not fatal.
                        try {
                            when (event) {
                                "message_start" -> {
                                    started = true
                                    val m = data["message"]?.jsonObject
                                    model = m?.get("model")?.jsonPrimitive?.content
                                    m?.get("usage")?.jsonObject?.let { u ->
                                        inputTokens = u.long("input_tokens")
                                        cacheWrite = u.long("cache_creation_input_tokens")
                                        cacheRead = u.long("cache_read_input_tokens")
                                    }
                                }
                                "content_block_start" -> {
                                    val type = data["content_block"]?.jsonObject?.get("type")?.jsonPrimitive?.content
                                    if (type == "text") text.clear()
                                }
                                "content_block_delta" -> {
                                    val d = data["delta"]?.jsonObject
                                    if (d?.get("type")?.jsonPrimitive?.content == "text_delta") {
                                        text.append(
                                            d["text"]?.jsonPrimitive?.content.orEmpty(),
                                        )
                                    }
                                }
                                "message_delta" -> {
                                    data["delta"]?.jsonObject?.get("stop_reason")?.jsonPrimitive?.content?.let { stopReason = it }
                                    data["usage"]?.jsonObject?.get(
                                        "output_tokens",
                                    )?.jsonPrimitive?.content?.toLongOrNull()?.let { outputTokens = it }
                                }
                                "message_stop" -> stopped = true
                                "error" -> {
                                    val type = data["error"]?.jsonObject?.get("type")?.jsonPrimitive?.content
                                    val code =
                                        if (type == "overloaded_error") {
                                            529
                                        } else if (type == "rate_limit_error") {
                                            429
                                        } else {
                                            500
                                        }
                                    // After message_start the input was read and is billed: its usage goes with the error.
                                    val usage =
                                        if (started) {
                                            Usage(inputTokens, outputTokens ?: estimateTokens(text), cacheWrite, cacheRead)
                                        } else {
                                            null
                                        }
                                    return Outcome.Http(code, "", null, model, usage)
                                }
                            }
                        } catch (e: IllegalArgumentException) {
                            log.log("stream event $event unreadable")
                        }
                    }
                }
                if (stopped) break
            }
        } catch (e: IOException) {
            log.log("stream failed: ${e.javaClass.simpleName}")
            return interrupted(e is InterruptedIOException, started, model, inputTokens, cacheWrite, cacheRead, outputTokens, text)
        }
        if (!stopped) return interrupted(false, started, model, inputTokens, cacheWrite, cacheRead, outputTokens, text)
        val usage =
            buildJsonObject {
                put("input_tokens", inputTokens)
                put("output_tokens", outputTokens ?: estimateTokens(text))
                put("cache_creation_input_tokens", cacheWrite)
                put("cache_read_input_tokens", cacheRead)
            }
        val message =
            buildJsonObject {
                model?.let { put("model", it) }
                stopReason?.let { put("stop_reason", it) }
                put(
                    "content",
                    buildJsonArray {
                        add(
                            buildJsonObject {
                                put("type", "text")
                                put("text", text.toString())
                            },
                        )
                    },
                )
                put("usage", usage)
            }
        return Outcome.Http(200, message.toString(), null)
    }

    private fun interrupted(
        timedOut: Boolean,
        started: Boolean,
        model: String?,
        inputTokens: Long,
        cacheWrite: Long,
        cacheRead: Long,
        outputTokens: Long?,
        text: CharSequence,
    ): Outcome =
        Outcome.Interrupted(
            timedOut,
            model,
            if (started) Usage(inputTokens, outputTokens ?: estimateTokens(text), cacheWrite, cacheRead) else null,
        )

    private fun JsonObject.long(key: String): Long = this[key]?.jsonPrimitive?.content?.toLongOrNull() ?: 0L

    /** What a whole message cost (its model and usage), when the body is one that says. */
    private fun billed(raw: String): Pair<String?, Usage>? {
        val msg = runCatching { lenient.decodeFromString(MessageResponse.serializer(), raw) }.getOrNull() ?: return null
        val u = msg.usage ?: return null
        return msg.model to Usage(u.inputTokens, u.outputTokens, u.cacheCreationInputTokens ?: 0, u.cacheReadInputTokens ?: 0)
    }

    private suspend fun handle(
        raw: String,
        input: ClaudeInput,
        model: ClaudeModel,
    ): ClaudeResult {
        val msg =
            runCatching { lenient.decodeFromString(MessageResponse.serializer(), raw) }.getOrNull()
                ?: return ClaudeResult.Failed(ClaudeResult.Reason.INVALID_RESPONSE)
        val usage =
            msg.usage?.let {
                Usage(it.inputTokens, it.outputTokens, it.cacheCreationInputTokens ?: 0, it.cacheReadInputTokens ?: 0)
            }
        val answeredBy = msg.model ?: model.id
        // The tokens are spent whatever happens next: a cancellation can't skip the meter.
        if (usage != null) withContext(NonCancellable) { usageSink.record(answeredBy, usage) }
        // stop_reason first, content after.
        when (msg.stopReason) {
            "refusal" -> return ClaudeResult.Refused(usage)
            "max_tokens" -> return ClaudeResult.Failed(ClaudeResult.Reason.MAX_TOKENS, usage)
            "end_turn", "stop_sequence" -> Unit
            else -> return ClaudeResult.Failed(ClaudeResult.Reason.INVALID_RESPONSE, usage)
        }
        val text =
            msg.content.lastOrNull { it.type == "text" }?.text
                ?: return ClaudeResult.Failed(ClaudeResult.Reason.INVALID_RESPONSE, usage)
        val out =
            runCatching { strict.decodeFromString(ClaudeOutput.serializer(), text) }.getOrNull()
                ?: return ClaudeResult.Failed(ClaudeResult.Reason.INVALID_RESPONSE, usage)
        val source =
            when (input) {
                is ClaudeInput.Lines -> input.lines.joinToString("\n")
                is ClaudeInput.PageText -> input.text
                // A photo has no text to check source URLs against: none are kept.
                is ClaudeInput.Photo -> ""
            }
        val recipe =
            runCatching { PostProcess.recipe(out, source, answeredBy) }.getOrNull()
                ?: return ClaudeResult.Failed(ClaudeResult.Reason.INVALID_RESPONSE, usage)
        return ClaudeResult.Parsed(recipe, usage ?: Usage(0, 0))
    }

    companion object {
        /** A reply line (one SSE event) or a plain body longer than this is not an answer. A full reply is about 64 KB. */
        const val MAX_LINE_BYTES = 2L * 1024 * 1024
        const val MAX_BODY_BYTES = 2L * 1024 * 1024
        const val MAX_TEXT_CHARS = 2 * 1024 * 1024
        private val JSON = "application/json".toMediaType()
        private const val MAX_BACKOFFS = 2
        private val BACKOFF_S = longArrayOf(2, 8)
        private const val MAX_WAIT_S = 30L

        /** Rough token count of text that never got its count: about four characters a token. */
        fun estimateTokens(text: CharSequence): Long = (text.length + 3) / 4L

        fun defaultHttp(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                // Replies are streamed, so bytes keep coming while the answer is written; a
                // minute without any means the connection is dead. The whole call still has
                // a budget: ~2 min for a full MAX_TOKENS reply on Opus, with room to spare.
                .readTimeout(60, TimeUnit.SECONDS)
                .callTimeout(300, TimeUnit.SECONDS)
                // OkHttp would otherwise re-POST on a dropped connection: two generations billed,
                // one recorded. Retries are this client's own, explicit policy.
                .retryOnConnectionFailure(false)
                .build()
    }
}
