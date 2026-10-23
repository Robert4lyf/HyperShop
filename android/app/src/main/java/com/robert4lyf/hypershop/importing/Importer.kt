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
import com.robert4lyf.hypershop.core.parse.Uncertainty
import com.robert4lyf.hypershop.extract.Extractor
import com.robert4lyf.hypershop.extract.FetchResult
import com.robert4lyf.hypershop.extract.Method
import com.robert4lyf.hypershop.extract.SafeFetcher
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Claude is only called while the review screen is in the foreground (PLAN §3.7):
 * a share intent on its own never spends money. Counted, not a flag: when one
 * review replaces another ("Try again", a queued share), the old screen's stop
 * must not close the gate on the new one.
 */
class ReviewGate {
    private var count = 0
    private val v = MutableStateFlow(false)
    val visible: StateFlow<Boolean> = v

    /** A review screen has come to the foreground. */
    @Synchronized
    fun acquire() {
        count++
        v.value = true
    }

    /** That screen has left it (or gone); one release per acquire. */
    @Synchronized
    fun release() {
        if (count > 0) count--
        v.value = count > 0
    }
}

/**
 * Fetch → extract → parse (PLAN §3.1). Rules always; Claude when allowed, keyed,
 * online **and worth it**: a recipe whose every line the rule parser read with
 * no doubt ([Uncertainty]) is kept as read, and Claude is only asked when a
 * line is flagged, when nothing structured was found (page text, a photo), or
 * when the user asks ([ImportResult.askClaude]). The eval behind this is
 * `HybridGateTest` (docs/design/simplify.md phase 3).
 */
class Importer(
    private val fetcher: SafeFetcher,
    private val rules: RuleParser,
    private val claude: GuardedClaude?,
    private val hasKey: suspend () -> Boolean,
) {
    suspend fun fromUrl(
        url: String,
        fallbackTitle: String?,
        /**
         * Whether Claude may be called, asked *after* the fetch: the review screen must
         * be in the foreground at the moment of the call, not when the share arrived
         * (a fetch can take 30 s, and the user may have left meanwhile).
         */
        allowClaude: () -> Boolean,
        model: ClaudeModel,
    ): ImportResult {
        val locale = localeFor(url, emptyList())
        val page =
            when (val f = fetcher.fetch(url)) {
                is FetchResult.Ok -> f
                is FetchResult.Blocked -> return failed(url, fallbackTitle, locale, ImportError.BLOCKED)
                // An image is only what fetchImage returns; a page fetch never gets one.
                is FetchResult.WrongType, is FetchResult.Image -> return failed(url, fallbackTitle, locale, ImportError.NOT_HTML)
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
                imageUrl = e.image,
            )
        return parse(base, allowClaude(), model)
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

    /**
     * Parses [base]'s raw lines (or page text): the rule parser first; Claude when
     * allowed and the rules left a doubt, else the rule parser's reading stands
     * (marked "Claude pending" only when Claude was wanted and could not run).
     */
    suspend fun parse(
        base: ImportResult,
        allowClaude: Boolean,
        model: ClaudeModel,
    ): ImportResult {
        // Lines read afresh: the review's origins no longer describe them, and its corrections are laid over them
        // from the lines they replace (ReviewViewModel.begin and keepCorrections).
        val replaced = ReviewLines(base.lines, LineIdentity.originsOf(base)).takeIf { base.corrections.isNotEmpty() }
        val ruled =
            base.copy(lines = rules.parseLines(base.rawLines), parsedBy = ParsedBy.RULES, origins = emptyList(), claudeBefore = replaced)
        val keyed = claude != null && hasKey()
        val photo = base.photo?.takeIf { base.rawLines.isEmpty() }
        if (photo != null && !keyed) return ruled.copy(claudePending = false, error = ImportError.PHOTO_NEEDS_KEY)
        // The gate: structured lines the rules read cleanly need no second opinion (and cost nothing).
        if (photo == null && base.rawLines.isNotEmpty() && !base.askClaude && !Uncertainty.needsClaude(ruled.lines)) {
            return ruled.copy(claudePending = false, claudeSkipped = true)
        }
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
                            rawLines = base.rawLines.ifEmpty { LineIdentity.sourceLines(r.recipe.ingredients) },
                            // Claude's lines are new: the review's origins no longer describe them, and its
                            // corrections are laid over them from the lines they replaced (ReviewViewModel.begin).
                            origins = emptyList(),
                            claudeBefore = replaced,
                            photo = null,
                            title = base.title ?: r.recipe.title,
                            servings = base.servings ?: r.recipe.servings,
                            parsedBy = ParsedBy.CLAUDE,
                            model = r.recipe.model,
                            claudePending = false,
                            claudeSkipped = false,
                            askClaude = false,
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
         * plain words for the review screen.
         */
        fun claudeNoteText(note: String?): String? =
            when (note) {
                null -> null
                "NO_KEY" -> "No Claude key"
                "BAD_KEY" -> "Claude rejected the key"
                "CAP_REACHED" -> "Monthly cap reached"
                "OFFLINE" -> "Offline"
                "TIMEOUT" -> "Claude took too long"
                "RATE_LIMITED", "OVERLOADED" -> "Claude is busy"
                "SERVER" -> "Claude had a problem"
                "MAX_TOKENS" -> "Too long for Claude"
                "INVALID_RESPONSE" -> "Claude's answer couldn't be read"
                "REQUEST" -> "Claude turned the request down"
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
