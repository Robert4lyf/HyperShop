package com.robert4lyf.hypershop.claude

/**
 * The models HyperShop offers (PLAN §3.7) with their per-million-token prices
 * in USD: input, output, 5-minute cache write, cache read.
 */
enum class ClaudeModel(
    val id: String,
    val label: String,
    val inputPerM: Double,
    val outputPerM: Double,
    val cacheWritePerM: Double,
    val cacheReadPerM: Double,
    /** Server-side refusal fallback (`fallbacks: "default"`). Haiku has none. */
    val serverFallback: Boolean,
) {
    // Claude Haiku 5.5's rates are for prompts up to 100K tokens; HyperShop caps input at ~30k characters.
    HAIKU("claude-haiku-5-5", "Claude Haiku 5.5 (default)", 0.10, 0.50, 0.125, 0.01, serverFallback = false),
    SONNET("claude-sonnet-5-5", "Claude Sonnet 5.5", 2.00, 10.00, 2.50, 0.20, serverFallback = true),
    OPUS("claude-opus-5-5", "Claude Opus 5.5", 4.00, 20.00, 5.00, 0.20, serverFallback = true),
    ;

    fun costUsd(u: Usage): Double {
        val input = u.inputTokens * inputPerM + u.cacheWriteTokens * cacheWritePerM + u.cacheReadTokens * cacheReadPerM
        return (input + u.outputTokens * outputPerM) / 1e6
    }

    companion object {
        val DEFAULT = HAIKU

        fun fromId(id: String): ClaudeModel? = entries.firstOrNull { it.id == id }
    }
}

data class Usage(
    val inputTokens: Long,
    val outputTokens: Long,
    val cacheWriteTokens: Long = 0,
    val cacheReadTokens: Long = 0,
)
