package com.robert4lyf.hypershop.importing

import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.ParsedIngredient
import com.robert4lyf.hypershop.core.model.UnitLocale
import kotlinx.serialization.Serializable

/** DISCARDING: cancelled on review, with "Undo" still showing; not on the ready card, and DONE once Undo has passed. */
enum class JobState { QUEUED, RUNNING, READY, FAILED, DONE, DISCARDING }

/** Why an import could not produce lines; each has its own message and a way out. */
enum class ImportError { BLOCKED, NOT_HTML, TOO_LARGE, HTTP, OFFLINE, NO_RECIPE, NOT_A_RECIPE, PHOTO_NEEDS_KEY }

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
    /** False for "Add the in-stock items now": full amounts, nothing pre-ticked, no "add again?". */
    val preTick: Boolean = true,
    /** The servings the review opens at, when not [servings] (the recipe's own). */
    val targetServings: Int? = null,
    /** Claude was already called for this import (and failed or refused); don't call it again on open. */
    val claudeTried: Boolean = false,
    /** "Edit" on the Recipes tab: the use this review replaces when saved. */
    val editsUse: Long? = null,
    /** With [editsUse]: the indices of [lines] that were marked in stock. */
    val inStockLines: List<Int> = emptyList(),
    /** A photo of a recipe ([PhotoStore]) for Claude to read, until it has been read. */
    val photo: String? = null,
    /** The recipe-library entry this is a copy of (an edit, or "Add to list" from the library). */
    val libraryKey: String? = null,
    /** The rows marked in stock on the review (merge keys), so reopening it shows the same decisions. */
    val inStockKeys: List<String> = emptyList(),
    /** [inStockKeys] was saved: an empty list then means nothing in stock, not "not decided yet". */
    val decisionsSaved: Boolean = false,
    /** "Just this time" aisle moves made on the review, by merge key. */
    val aisleMoves: Map<String, AisleCode> = emptyMap(),
    /** A cookbook recipe's book and page, as typed (photo or typed in; editable on review). */
    val book: String? = null,
    val page: String? = null,
)
