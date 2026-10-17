package com.robert4lyf.hypershop.claude

/**
 * Log hygiene (PLAN §3.7): URLs lose their query strings, and anything that
 * looks like an Anthropic key or an `x-api-key` header value is replaced.
 * Request and response bodies are never logged at all.
 */
object Redactor {
    private val key = Regex("sk-ant-[A-Za-z0-9_\\-]+")
    private val header = Regex("(?i)(x-api-key\\s*[:=]\\s*)\\S+")
    private val query = Regex("(https?://[^\\s?#]+)[?#][^\\s]*")

    fun redact(s: String): String = s.replace(key, "sk-ant-[redacted]").replace(header, "$1[redacted]").replace(query, "$1")
}

/** Release builds keep logs in memory only: the last [capacity] lines, redacted. */
class RingLog(
    private val capacity: Int = 500,
) {
    private val lines = ArrayDeque<String>()

    @Synchronized
    fun log(line: String) {
        lines.addLast(Redactor.redact(line))
        while (lines.size > capacity) lines.removeFirst()
    }

    @Synchronized
    fun snapshot(): List<String> = lines.toList()
}
