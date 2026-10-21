package com.robert4lyf.hypershop.core.parse

import com.robert4lyf.hypershop.core.model.ParsedIngredient

/**
 * Which rule-parsed lines are worth a second opinion (the hybrid pipeline,
 * docs/design/simplify.md phase 3). Measurable signs only, no scores: the
 * parser's own `check` flag (R7), a line it found no name in, an amount it
 * left in the text, or a name so long it is probably a sentence. A recipe
 * with none of these is read offline alone; one with any goes to Claude,
 * which reads the whole recipe so that its answer is consistent.
 *
 * `HybridGateTest` reports how the rule parser does on the lines this
 * passes against the ones it flags.
 */
object Uncertainty {
    /** Why [p] should be looked at again, or null when the rule parser's reading can stand. */
    fun reason(p: ParsedIngredient): String? {
        if (p.check) return p.checkReason ?: "an unhandled pattern"
        if (p.name.isBlank()) return "no ingredient name"
        if (p.quantity == null && p.raw.any { it.isDigit() }) return "an amount it couldn't read"
        if (p.name.length > MAX_NAME || p.name.count { it == ' ' } >= MAX_NAME_WORDS) return "a name that reads like a sentence"
        return null
    }

    fun uncertain(p: ParsedIngredient): Boolean = reason(p) != null

    /**
     * Whether a recipe's rule-parsed [lines] need Claude: any uncertain line,
     * or no lines at all (nothing structured was found).
     */
    fun needsClaude(lines: List<ParsedIngredient>): Boolean = lines.isEmpty() || lines.any(::uncertain)

    private const val MAX_NAME = 40
    private const val MAX_NAME_WORDS = 5
}
