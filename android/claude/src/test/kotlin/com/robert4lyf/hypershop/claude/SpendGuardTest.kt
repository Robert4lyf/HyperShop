package com.robert4lyf.hypershop.claude

import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.LocalDate

class SpendGuardTest {
    private val today = LocalDate.of(2026, 10, 17)
    private val guard = SpendGuard()

    @Test
    fun dailyCallCap() {
        val history = List(50) { UsageRecord(today, "claude-haiku-5-5", Usage(1000, 500)) }
        assertTrue(guard.check(history, today, ClaudeModel.HAIKU, 2000) is SpendGuard.Decision.DailyCapReached)
        val yesterday = history.map { it.copy(date = today.minusDays(1)) }
        assertTrue(guard.check(yesterday, today, ClaudeModel.HAIKU, 2000) is SpendGuard.Decision.Allowed)
    }

    @Test
    fun monthlyCapCountsCacheTokensAndTheNextCall() {
        // $1.99 spent on Opus this month (cache reads included).
        val big = Usage(inputTokens = 0, outputTokens = 99_000, cacheReadTokens = 50_000)
        val spent = ClaudeModel.OPUS.costUsd(big)
        assertEquals(1.99, spent, 1e-9)
        val history = listOf(UsageRecord(today.withDayOfMonth(1), "claude-opus-5-5", big))
        assertTrue(guard.check(history, today, ClaudeModel.HAIKU, 2000) is SpendGuard.Decision.Allowed)
        assertTrue(guard.check(history, today, ClaudeModel.OPUS, 30_000) is SpendGuard.Decision.MonthlyCapReached)
        // Last month does not count.
        val old = history.map { it.copy(date = today.minusMonths(1)) }
        assertEquals(0.0, guard.spentThisMonth(old, today))
    }

    @Test
    fun unknownFallbackModelsArePricedHigh() {
        val u = Usage(1000, 1000)
        assertEquals(ClaudeModel.OPUS.costUsd(u), SpendGuard.costUsd("claude-opus-4-8", u))
    }

    @Test
    fun aTwoDollarCapIsAboutTwoThousandHaikuImports() {
        val perImport = SpendGuard.estimateUsd(ClaudeModel.HAIKU, 4000)
        val imports = (2.0 / perImport).toInt()
        assertTrue(imports in 1000..3000, "$imports imports")
    }

    @Test
    fun guardedClientRefusesWithoutCalling() =
        runTest {
            val client = ClaudeClient(apiKey = { error("must not be called") })
            val g =
                GuardedClaude(client, { SpendGuard(dailyCallCap = 0) }, history = { emptyList() }, today = { today })
            val r = g.parse(ClaudeInput.Lines(listOf("1 egg")), ClaudeModel.HAIKU) as ClaudeResult.Failed
            assertEquals(ClaudeResult.Reason.CAP_REACHED, r.reason)
        }
}
