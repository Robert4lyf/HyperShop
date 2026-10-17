package com.robert4lyf.hypershop.claude

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The JSON Claude returns under the strict schema. Decoded strictly: every
 * field must be present (nullable ones as null) and unknown keys are an error,
 * so a response that does not match the schema is rejected rather than half-read.
 */
@Serializable
data class ClaudeOutput(
    val status: String,
    val title: String?,
    val servings: Int?,
    val ingredients: List<ClaudeIngredient>,
    @SerialName("source_urls") val sourceUrls: List<String>,
)

@Serializable
data class ClaudeIngredient(
    val raw: String,
    val name: String,
    val quantity: Double?,
    @SerialName("quantity_max") val quantityMax: Double?,
    @SerialName("quantity_text") val quantityText: String?,
    val unit: String?,
    @SerialName("alt_quantity") val altQuantity: Double?,
    @SerialName("alt_unit") val altUnit: String?,
    val note: String?,
    val section: String?,
    val optional: Boolean,
    val estimated: Boolean,
    @SerialName("aisle_suggestion") val aisleSuggestion: String?,
)

/** The Messages API envelope, read leniently: only what HyperShop needs. */
@Serializable
internal data class MessageResponse(
    val model: String? = null,
    @SerialName("stop_reason") val stopReason: String? = null,
    val content: List<ContentBlock> = emptyList(),
    val usage: ApiUsage? = null,
)

@Serializable
internal data class ContentBlock(
    val type: String,
    val text: String? = null,
)

@Serializable
internal data class ApiUsage(
    @SerialName("input_tokens") val inputTokens: Long = 0,
    @SerialName("output_tokens") val outputTokens: Long = 0,
    @SerialName("cache_creation_input_tokens") val cacheCreationInputTokens: Long? = null,
    @SerialName("cache_read_input_tokens") val cacheReadInputTokens: Long? = null,
)
