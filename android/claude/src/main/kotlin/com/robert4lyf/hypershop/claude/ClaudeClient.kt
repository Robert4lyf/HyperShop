package com.robert4lyf.hypershop.claude

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit

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

    private sealed interface Outcome {
        data object Offline : Outcome

        data class Http(
            val code: Int,
            val body: String,
            val retryAfter: Long?,
        ) : Outcome
    }

    private fun send(
        key: String,
        model: ClaudeModel,
        body: String,
    ): Outcome {
        val b = Request.Builder().url("$baseUrl/v1/messages").post(body.toRequestBody(JSON))
        ClaudeRequest.headers(key, model).forEach { (k, v) -> if (k != "content-type") b.header(k, v) }
        val req = b.build()
        return try {
            http.newCall(req).execute().use { r: Response ->
                // Status only: request and response bodies never reach the log.
                log.log("POST ${req.url} ${model.id} → ${r.code}")
                Outcome.Http(r.code, r.body?.string().orEmpty(), r.header("retry-after")?.toLongOrNull())
            }
        } catch (e: IOException) {
            log.log("POST ${req.url} failed: ${e.javaClass.simpleName}")
            Outcome.Offline
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
        if (usage != null) usageSink.record(answeredBy, usage)
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
                .callTimeout(60, TimeUnit.SECONDS)
                .build()
    }
}
