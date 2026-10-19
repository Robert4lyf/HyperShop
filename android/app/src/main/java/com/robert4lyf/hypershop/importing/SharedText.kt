package com.robert4lyf.hypershop.importing

/**
 * What a share (ACTION_SEND text) or a paste carries (PLAN §3.1): the first
 * web URL, without `utm_*` and `igsh` tracking parameters; EXTRA_SUBJECT is
 * the fallback title. A plain `http://` link is tried as `https://` (the
 * fetcher only speaks https; nearly every recipe site answers on it). Other
 * schemes (file, content, intent) are ignored. Text with no link but several
 * lines is taken as the ingredients themselves ([lines]).
 */
object SharedText {
    private val URL = Regex("""https?://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)
    private const val TRAILING = ".,;:!?)]}'\"”’»"
    private const val MAX_LINES = 300
    private const val MAX_LINE = 500

    fun parse(
        text: String?,
        subject: String? = null,
    ): ImportInput? {
        val match = URL.find(text ?: return null) ?: return null
        var url = match.value
        while (url.isNotEmpty() && url.last() in TRAILING) {
            // Keep a closing bracket that has its opening pair inside the URL (Wikipedia style).
            if (url.last() == ')' && url.count { it == '(' } >= url.count { it == ')' }) break
            url = url.dropLast(1)
        }
        if (url.startsWith("http://", ignoreCase = true)) url = "https://" + url.substring("http://".length)
        val title = subject?.trim()?.takeIf { it.isNotEmpty() && !URL.matches(it) }
        return ImportInput(stripTracking(url), title)
    }

    /**
     * Shared text with no link and at least two non-empty lines ("2 eggs\n100 g
     * flour"): the lines, trimmed and capped as the extractor caps a page's.
     */
    fun lines(text: String?): List<String>? {
        if (text == null || URL.containsMatchIn(text)) return null
        val lines = text.lines().map { it.trim().take(MAX_LINE) }.filter { it.isNotEmpty() }.take(MAX_LINES)
        return lines.takeIf { it.size >= 2 }
    }

    fun stripTracking(url: String): String {
        val hashAt = url.indexOf('#')
        val fragment = if (hashAt >= 0) url.substring(hashAt) else ""
        val noFragment = if (hashAt >= 0) url.substring(0, hashAt) else url
        val q = noFragment.indexOf('?')
        if (q < 0) return url
        val kept =
            noFragment.substring(q + 1).split('&').filter { p ->
                val name = p.substringBefore('=').lowercase()
                p.isNotEmpty() && !name.startsWith("utm_") && name != "igsh" && name != "igshid"
            }
        val base = noFragment.substring(0, q)
        return (if (kept.isEmpty()) base else base + "?" + kept.joinToString("&")) + fragment
    }
}
