package com.robert4lyf.hypershop.claude

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
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
 * - an IOException is [ClaudeResult.Reason.OFFLINE].
 * `stop_reason` is read before `content`.
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
    private val lenient = Json { ignoreUnknownKeys = true }

    suspend fun parse(
        input: ClaudeInput,
        model: ClaudeModel,
    ): ClaudeResult {
        val key = apiKey()?.takeIf { it.isNotBlank() } ?: return ClaudeResult.Failed(ClaudeResult.Reason.NO_KEY)
        // OkHttp rejects such a header with an exception that quotes the value, i.e. the key.
        if (!headerSafe(key)) return ClaudeResult.Failed(ClaudeResult.Reason.BAD_KEY)
        val body = ClaudeRequest.body(input, model).toString()
        var backoffs = 0
        var serverRetries = 0
        while (true) {
            val outcome = withContext(io) { send(key, model, body) }
            when (outcome) {
                is Outcome.Offline -> return ClaudeResult.Failed(ClaudeResult.Reason.OFFLINE)
                is Outcome.Http -> {
                    val code = outcome.code
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
        data object Offline : Outcome

        data class Http(
            val code: Int,
            val body: String,
            val retryAfter: Long?,
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
            call.enqueue(
                object : Callback {
                    override fun onFailure(
                        call: Call,
                        e: IOException,
                    ) {
                        if (!call.isCanceled()) log.log("POST ${req.url} failed: ${e.javaClass.simpleName}")
                        // After a cancellation the continuation is already done; this is then a no-op.
                        cont.resume(Outcome.Offline)
                    }

                    override fun onResponse(
                        call: Call,
                        response: Response,
                    ) {
                        val outcome =
                            try {
                                response.use { r ->
                                    // Status only: request and response bodies never reach the log.
                                    log.log("POST ${req.url} ${model.id} → ${r.code}")
                                    Outcome.Http(r.code, r.body?.string().orEmpty(), r.header("retry-after")?.toLongOrNull())
                                }
                            } catch (e: IOException) {
                                log.log("POST ${req.url} body failed: ${e.javaClass.simpleName}")
                                Outcome.Offline
                            }
                        cont.resume(outcome)
                    }
                },
            )
        }
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
        private val JSON = "application/json".toMediaType()
        private const val MAX_BACKOFFS = 2
        private val BACKOFF_S = longArrayOf(2, 8)
        private const val MAX_WAIT_S = 30L

        fun defaultHttp(): OkHttpClient =
            OkHttpClient.Builder()
                .connectTimeout(10, TimeUnit.SECONDS)
                // Replies are not streamed, so nothing arrives until the whole answer is
                // written: ~14 s for 15 lines on Opus, ~2 min for a full MAX_TOKENS reply.
                // OkHttp's 10 s default read timeout would cut those off.
                .readTimeout(180, TimeUnit.SECONDS)
                .callTimeout(180, TimeUnit.SECONDS)
                // OkHttp would otherwise re-POST on a dropped connection: two generations billed,
                // one recorded. Retries are this client's own, explicit policy.
                .retryOnConnectionFailure(false)
                .build()
    }
}
