package com.robert4lyf.hypershop.core.report

/**
 * W.4: "This is wrong", as plain text a person can read and paste anywhere. What was written, how it was read,
 * what the list or review showed, and what was expected — enough to turn into a test without the phone.
 */
data class WrongReport(
    /** "Review" or "List". */
    val where: String,
    val name: String,
    /** The recipe's lines, each with how it was read: "2 garlic cloves" to "2 clove garlic". */
    val lines: List<Pair<String, String>>,
    /** What the screen showed: "1 bulb garlic · 2 cloves". */
    val shown: String,
    val expected: String,
    val catalogVersion: String,
    /** ISO-8601, from the caller's clock. */
    val at: String,
) {
    fun text(): String =
        buildString {
            append("--- This is wrong ($where, $at, catalogue $catalogVersion)\n")
            append("Item: ${oneLine(name)}\n")
            for ((raw, read) in lines) append("Line: ${oneLine(raw)}\n  read as: ${oneLine(read)}\n")
            append("Showed: ${oneLine(shown)}\n")
            append("Expected: ${oneLine(expected).ifBlank { "(not said)" }}\n")
        }

    private fun oneLine(s: String) = s.replace(Regex("""\s+"""), " ").trim().take(MAX)

    private companion object {
        const val MAX = 500
    }
}
