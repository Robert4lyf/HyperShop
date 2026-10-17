package com.robert4lyf.hypershop.importing

import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.ParsedIngredient
import com.robert4lyf.hypershop.core.model.UnitLocale
import kotlinx.serialization.Serializable

enum class JobState { QUEUED, RUNNING, READY, FAILED, DONE }

/** Why an import could not produce lines; each has its own message and a way out. */
enum class ImportError { BLOCKED, NOT_HTML, TOO_LARGE, HTTP, OFFLINE, NO_RECIPE, NOT_A_RECIPE }

/** What an import produced, kept in `ImportJob.resultJson` until it is reviewed. */
@Serializable
data class ImportResult(
    val url: String?,
    val title: String?,
    val servings: Int?,
    val locale: UnitLocale,
    val method: String,
    val lines: List<ParsedIngredient> = emptyList(),
    val parsedBy: ParsedBy = ParsedBy.RULES,
    val model: String? = null,
    /** Parsed by rules because Claude was not called (offline, no key, background, failed). */
    val claudePending: Boolean = false,
    /** Raw lines (or page text lines) for "Retry with Claude" and "Select lines manually". */
    val rawLines: List<String> = emptyList(),
    val pageText: String? = null,
    val sourceUrls: List<String> = emptyList(),
    val error: ImportError? = null,
    val claudeNote: String? = null,
)
