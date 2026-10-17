package com.robert4lyf.hypershop.importing

/**
 * What a share (ACTION_SEND text) or a paste carries (PLAN §3.1): the first
 * https URL, without `utm_*` and `igsh` tracking parameters; EXTRA_SUBJECT is
 * the fallback title. Other schemes (http, file, content, intent) are ignored.
 */
object SharedText {
    private val URL = Regex("""https://[^\s<>"'`]+""", RegexOption.IGNORE_CASE)
    private const val TRAILING = ".,;:!?)]}'\"”’»"

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
        val title = subject?.trim()?.takeIf { it.isNotEmpty() && !it.startsWith("https://", ignoreCase = true) }
        return ImportInput(stripTracking(url), title)
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
