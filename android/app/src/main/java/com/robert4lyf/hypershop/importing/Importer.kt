package com.robert4lyf.hypershop.importing

import com.robert4lyf.hypershop.claude.ClaudeInput
import com.robert4lyf.hypershop.claude.ClaudeModel
import com.robert4lyf.hypershop.claude.ClaudeResult
import com.robert4lyf.hypershop.claude.GuardedClaude
import com.robert4lyf.hypershop.claude.RecipeStatus
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.RuleParser
import com.robert4lyf.hypershop.extract.Extractor
import com.robert4lyf.hypershop.extract.FetchResult
import com.robert4lyf.hypershop.extract.Method
import com.robert4lyf.hypershop.extract.SafeFetcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Claude is only called while the review screen is in the foreground (PLAN §3.7):
 * a share intent on its own never spends money.
 */
class ReviewGate {
    private val v = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = v

    fun set(visible: Boolean) {
        v.value = visible
    }
}

/** Fetch → extract → parse (PLAN §3.1). Rules always; Claude when allowed, keyed and online. */
class Importer(
    private val fetcher: SafeFetcher,
    private val rules: RuleParser,
    private val claude: GuardedClaude?,
    private val hasKey: suspend () -> Boolean,
) {
    suspend fun fromUrl(
        url: String,
        fallbackTitle: String?,
        allowClaude: Boolean,
        model: ClaudeModel,
    ): ImportResult {
        val locale = localeFor(url, emptyList())
        val page =
            when (val f = fetcher.fetch(url)) {
                is FetchResult.Ok -> f
                is FetchResult.Blocked -> return failed(url, fallbackTitle, locale, ImportError.BLOCKED)
                is FetchResult.WrongType -> return failed(url, fallbackTitle, locale, ImportError.NOT_HTML)
                FetchResult.TooLarge -> return failed(url, fallbackTitle, locale, ImportError.TOO_LARGE)
                is FetchResult.HttpError -> return failed(url, fallbackTitle, locale, ImportError.HTTP)
                FetchResult.Network -> return failed(url, fallbackTitle, locale, ImportError.OFFLINE)
            }
        // A bare JSON-LD document (the fetcher allows the type) is read as if it were a page's script block.
        val html = if (page.contentType == "application/ld+json") Extractor.wrapLdJson(page.body) else page.body
        val e = Extractor.extract(html, page.finalUrl)
        val recipe = e.recipes.firstOrNull()
        val title = recipe?.title ?: e.pageTitle ?: fallbackTitle
        val raw = recipe?.lines.orEmpty()
        val base =
            ImportResult(
                url = url,
                title = title,
                servings = recipe?.servings,
                locale = localeFor(url, raw),
                method = e.method.name,
                rawLines = raw,
                pageText = e.pageText,
            )
        return parse(base, allowClaude, model)
    }

    /** Lines typed or pasted (the "Add item" field never comes here; it skips review). */
    suspend fun fromLines(
        lines: List<String>,
        title: String?,
        allowClaude: Boolean,
        model: ClaudeModel,
    ): ImportResult =
        parse(
            ImportResult(null, title, null, localeFor(null, lines), Method.MANUAL.name, rawLines = lines),
            allowClaude,
            model,
        )

    /** Parses [base]'s raw lines (or page text): Claude when allowed, else the rule parser marked "Claude pending". */
    suspend fun parse(
        base: ImportResult,
        allowClaude: Boolean,
        model: ClaudeModel,
    ): ImportResult {
        val ruled = base.copy(lines = rules.parseLines(base.rawLines), parsedBy = ParsedBy.RULES)
        val keyed = claude != null && hasKey()
        val photo = base.photo?.takeIf { base.rawLines.isEmpty() }
        if (photo != null && !keyed) return ruled.copy(claudePending = false, error = ImportError.PHOTO_NEEDS_KEY)
        val input =
            when {
                photo != null ->
                    PhotoStore.read(photo)?.let { ClaudeInput.Photo(it, base.title) } ?: return ruled.copy(error = ImportError.NO_RECIPE)
                base.rawLines.isNotEmpty() -> ClaudeInput.Lines(base.rawLines, base.title, base.servings)
                !base.pageText.isNullOrBlank() -> ClaudeInput.PageText(base.pageText, base.title)
                else -> return ruled.copy(error = ImportError.NO_RECIPE)
            }
        if (!allowClaude || !keyed) {
            val noLines = base.rawLines.isEmpty()
            return ruled.copy(claudePending = keyed, error = if (noLines && !keyed) ImportError.NO_RECIPE else null)
        }
        return when (val r = claude!!.parse(input, model)) {
            is ClaudeResult.Parsed ->
                when (r.recipe.status) {
                    // Claude has answered: it isn't pending any more, and reopening the review won't ask again.
                    RecipeStatus.NOT_A_RECIPE ->
                        ruled.copy(
                            claudePending = false,
                            claudeTried = true,
                            error = if (base.rawLines.isEmpty()) ImportError.NOT_A_RECIPE else null,
                        )
                    RecipeStatus.NO_INGREDIENTS ->
                        ruled.copy(
                            claudePending = false,
                            claudeTried = true,
                            error = if (base.rawLines.isEmpty()) ImportError.NO_RECIPE else null,
                        )
                    RecipeStatus.OK ->
                        base.copy(
                            lines = r.recipe.ingredients,
                            // A photo's lines are kept as text from now on ("Retry with Claude" re-reads those).
                            rawLines = base.rawLines.ifEmpty { r.recipe.ingredients.map { it.raw } },
                            photo = null,
                            title = base.title ?: r.recipe.title,
                            servings = base.servings ?: r.recipe.servings,
                            parsedBy = ParsedBy.CLAUDE,
                            model = r.recipe.model,
                            claudePending = false,
                            sourceUrls = r.recipe.sourceUrls,
                            locale = localeFor(base.url, r.recipe.ingredients.map { it.raw }),
                        ).also { PhotoStore.delete(photo) }
                }
            is ClaudeResult.Refused -> ruled.copy(claudePending = true, claudeTried = true, claudeNote = "Claude declined; parsed offline")
            is ClaudeResult.Failed ->
                ruled.copy(
                    claudePending = true,
                    claudeTried = true,
                    claudeNote = r.reason.name,
                    error = if (base.rawLines.isEmpty()) ImportError.NO_RECIPE else null,
                )
        }
    }

    private fun failed(
        url: String,
        title: String?,
        locale: UnitLocale,
        error: ImportError,
    ) = ImportResult(url, title, null, locale, Method.MANUAL.name, error = error)

    companion object {
        /**
         * [ImportResult.claudeNote] (a [ClaudeResult.Reason] name, or the refusal note) in
         * plain words for the review screen, each saying what to do about it.
         */
        fun claudeNoteText(note: String?): String? =
            when (note) {
                null -> null
                "NO_KEY" -> "No Claude key — add one in Settings › Claude"
                "BAD_KEY" -> "Claude rejected the key — check it in Settings › Claude"
                "CAP_REACHED" -> "Monthly cap reached — raise it in Settings › Claude"
                "OFFLINE" -> "Offline — retry when connected"
                "RATE_LIMITED", "OVERLOADED" -> "Claude is busy — try again in a minute"
                "SERVER" -> "Claude had a problem — try again in a minute"
                "MAX_TOKENS" -> "The recipe was too long for Claude to answer"
                "INVALID_RESPONSE" -> "Claude's answer couldn't be read — try again"
                "REQUEST" -> "Claude turned the request down — try again later"
                else -> if (note.startsWith("Claude declined")) "Claude declined this page" else note
            }

        private val ML_IN_LINE = Regex("""(\d+(?:,\d{3})*(?:[.,]\d+)?)\s*(ml|l)\b""", RegexOption.IGNORE_CASE)

        /** "1,000": a comma before exactly three digits groups thousands (as the tokeniser reads it). */
        private val THOUSANDS = Regex("""\d{1,3}(?:,\d{3})+""")

        /**
         * R2.2: metric when lines use g/ml; UK for UK sites; else US. A line that
         * gives both, like "2 cups milk (500ml)", settles the cup size outright.
         */
        fun localeFor(
            url: String?,
            lines: List<String>,
        ): UnitLocale {
            val host =
                url
                    ?.substringAfter("://")
                    ?.substringBefore('/')
                    ?.substringBefore('?')
                    ?.substringBefore('#')
                    ?.substringAfterLast('@')
                    ?.substringBefore(':') // the port: "bbc.co.uk:443"
                    ?.trimEnd('.')
                    ?.lowercase()
                    .orEmpty()
            if (host.endsWith(".co.uk") || host.endsWith(".uk") || "bbcgoodfood" in host) return UnitLocale.UK
            val rp = RuleParser()
            // Each line is parsed once, for both the cup check and the metric count.
            val sample = lines.take(50).map { it to rp.parse(it) }
            for ((l, p) in sample) {
                val cups = p.quantity?.toDouble()?.takeIf { p.unit == QtyUnit.CUP && it > 0 } ?: continue
                val m = ML_IN_LINE.find(l) ?: continue
                val digits = m.groupValues[1]
                val n = (if (THOUSANDS.matches(digits)) digits.replace(",", "") else digits.replace(',', '.')).toDoubleOrNull() ?: continue
                val ml = if (m.groupValues[2].lowercase() == "l") n * 1000 else n
                val perCup = ml / cups
                if (perCup in 245.0..255.0) return UnitLocale.METRIC
                if (perCup in 230.0..242.0) return UnitLocale.US
            }
            val metric =
                sample.count { (_, p) -> p.unit.let { it == QtyUnit.G || it == QtyUnit.ML || it == QtyUnit.KG || it == QtyUnit.L } }
            return if (metric * 3 >= maxOf(1, sample.size)) UnitLocale.METRIC else UnitLocale.US
        }
    }
}
