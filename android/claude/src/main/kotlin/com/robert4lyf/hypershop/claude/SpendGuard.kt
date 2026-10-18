package com.robert4lyf.hypershop.claude

import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** One `ClaudeUsage` row: a day's tokens on one model. */
data class UsageRecord(
    val date: LocalDate,
    val model: String,
    val usage: Usage,
    val calls: Int = 1,
)

/**
 * Spend control (PLAN §3.7): a monthly spend cap (default $2) and a daily cap
 * of 50 calls. The cost counter includes cache tokens. Checked before every call,
 * with the call's own likely cost included, so the cap is not overshot by one
 * large import.
 */
class SpendGuard(
    val monthlyCapUsd: Double = DEFAULT_MONTHLY_CAP_USD,
    val dailyCallCap: Int = DEFAULT_DAILY_CALLS,
) {
    sealed interface Decision {
        data class Allowed(
            val estimatedUsd: Double,
        ) : Decision

        data class MonthlyCapReached(
            val spentUsd: Double,
        ) : Decision

        data class DailyCapReached(
            val calls: Int,
        ) : Decision
    }

    fun spentThisMonth(
        history: List<UsageRecord>,
        today: LocalDate,
    ): Double = history.filter { it.date.year == today.year && it.date.month == today.month }.sumOf { costUsd(it.model, it.usage) }

    fun callsToday(
        history: List<UsageRecord>,
        today: LocalDate,
    ): Int = history.filter { it.date == today }.sumOf { it.calls }

    fun check(
        history: List<UsageRecord>,
        today: LocalDate,
        model: ClaudeModel,
        inputChars: Int,
        /** Calls already allowed but not yet recorded (running now), with their estimates. */
        reservedCalls: Int = 0,
        reservedUsd: Double = 0.0,
    ): Decision {
        val calls = callsToday(history, today) + reservedCalls
        if (calls >= dailyCallCap) return Decision.DailyCapReached(calls)
        val spent = spentThisMonth(history, today) + reservedUsd
        val estimate = estimateUsd(model, inputChars)
        if (spent + estimate > monthlyCapUsd) return Decision.MonthlyCapReached(spent)
        return Decision.Allowed(estimate)
    }

    companion object {
        const val DEFAULT_MONTHLY_CAP_USD = 2.0
        const val DEFAULT_DAILY_CALLS = 50

        /**
         * The most a call can cost: roughly 4 characters a token plus the system
         * prompt and schema in, and the full ClaudeRequest.MAX_TOKENS out (at most
         * $0.32 on Opus), so a call is only allowed if even its worst case fits under the cap.
         */
        fun estimateUsd(
            model: ClaudeModel,
            inputChars: Int,
        ): Double = model.costUsd(Usage(inputTokens = inputChars / 4L + 1200, outputTokens = ClaudeRequest.MAX_TOKENS.toLong()))

        /**
         * Cost of a usage row. A model HyperShop does not list (a server-side
         * fallback answered) is priced at the highest listed rates.
         */
        fun costUsd(
            modelId: String,
            usage: Usage,
        ): Double = (ClaudeModel.fromId(modelId) ?: ClaudeModel.OPUS).costUsd(usage)
    }
}

/** The client behind the caps: what the import worker calls. */
class GuardedClaude(
    private val client: ClaudeClient,
    private val guard: suspend () -> SpendGuard,
    private val history: suspend () -> List<UsageRecord>,
    private val today: () -> LocalDate = { LocalDate.now() },
) {
    suspend fun parse(
        input: ClaudeInput,
        model: ClaudeModel,
    ): ClaudeResult {
        val chars =
            when (input) {
                is ClaudeInput.Lines -> input.lines.sumOf { it.length + 1 }
                is ClaudeInput.PageText -> minOf(input.text.length, ClaudeRequest.INPUT_CAP)
                is ClaudeInput.Photo -> ClaudeRequest.PHOTO_CHARS
            }
        // Check and reserve together: two imports at once (a background share while a
        // review is open) can't both pass on the same history and overshoot a cap.
        val estimate =
            lock.withLock {
                when (val d = guard().check(history(), today(), model, chars, reservedCalls, reservedUsd)) {
                    is SpendGuard.Decision.Allowed -> {
                        reservedCalls++
                        reservedUsd += d.estimatedUsd
                        d.estimatedUsd
                    }
                    else -> return ClaudeResult.Failed(ClaudeResult.Reason.CAP_REACHED)
                }
            }
        return try {
            client.parse(input, model)
        } finally {
            // By now the call's real usage is recorded (or it failed unbilled). Released even
            // when the caller was cancelled, or the reservation would block calls for ever.
            withContext(NonCancellable) {
                lock.withLock {
                    reservedCalls--
                    reservedUsd -= estimate
                }
            }
        }
    }

    private val lock = Mutex()
    private var reservedCalls = 0
    private var reservedUsd = 0.0
}
