package com.robert4lyf.hypershop.claude

import com.robert4lyf.hypershop.core.model.ParsedIngredient

enum class RecipeStatus { OK, NO_INGREDIENTS, NOT_A_RECIPE }

data class ClaudeRecipe(
    val status: RecipeStatus,
    val title: String?,
    val servings: Int?,
    val ingredients: List<ParsedIngredient>,
    /** Only URLs that appear verbatim in the source and use https; opened only on a tap. */
    val sourceUrls: List<String>,
    /** The model that answered (a server-side fallback can differ from the one asked). */
    val model: String,
)

sealed interface ClaudeResult {
    /** Tokens spent, when the API reported them (logged to `ClaudeUsage` either way). */
    val usage: Usage?

    data class Parsed(
        val recipe: ClaudeRecipe,
        override val usage: Usage,
    ) : ClaudeResult

    /** `stop_reason: refusal`: fall back to the rule parser. */
    data class Refused(
        override val usage: Usage?,
    ) : ClaudeResult

    data class Failed(
        val reason: Reason,
        override val usage: Usage? = null,
        val retryAfterSeconds: Long? = null,
    ) : ClaudeResult

    enum class Reason {
        /** 401/403: ask for a new key. */
        BAD_KEY,

        /** 429 after back-off. */
        RATE_LIMITED,

        /** 529 after back-off. */
        OVERLOADED,

        /** 5xx after one retry. */
        SERVER,

        /** No network: switch to offline mode (rule parser, "Claude pending"). */
        OFFLINE,

        /** `stop_reason: max_tokens`: treated as an error, never half-used. */
        MAX_TOKENS,

        /** The response did not match the schema. */
        INVALID_RESPONSE,

        /** Other 4xx. */
        REQUEST,

        /** No key set. */
        NO_KEY,

        /** The monthly spend cap or the daily call cap is reached. */
        CAP_REACHED,
    }
}
