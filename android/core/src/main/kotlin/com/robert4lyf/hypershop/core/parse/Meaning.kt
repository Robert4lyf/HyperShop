package com.robert4lyf.hypershop.core.parse

import com.robert4lyf.hypershop.core.model.QtyUnit

/**
 * Readings that would lose what a line means even though its catalogue item and arithmetic are right
 * (November evaluation): a part of an ingredient written beside the whole ("1 large egg plus 1 egg yolk"),
 * and a spoon or cup said to be heaped. The model has no way to hold either, so the line keeps its source
 * text (and the part or qualifier in its note) and is flagged for review. No conversion is guessed.
 *
 * Both reasons mention the amount, so the review's own correction of the amount clears them
 * (`ReviewViewModel.aboutTheAmount`).
 */
object Meaning {
    /**
     * A part (yolk, white) beside a whole amount of the same ingredient. Each part is still one to buy, so the
     * count is summed, but the part stays in the note and the line is flagged.
     */
    const val PART = "an ingredient part beside the whole amount"

    /** "2 heaped tablespoons": the level amount is shown; more is meant. */
    const val HEAPED = "a heaped measure; the level amount is shown"

    val REASONS = setOf(PART, HEAPED)

    /** Parts of an ingredient that are not the whole of it. Singular and plural. */
    private val PARTS = mapOf("yolk" to "yolk", "yolks" to "yolk", "white" to "white", "whites" to "white")

    /** Qualifiers meaning more than the level measure. "Level" and "scant" are covered by the level amount. */
    val MORE_THAN_LEVEL = setOf("heaped", "heaping", "rounded", "generous")

    /** Measures a "heaped" qualifier changes: spoons and cups, scooped rather than weighed. */
    val SCOOPED = setOf(QtyUnit.TSP, QtyUnit.TBSP, QtyUnit.CUP)

    /** The part [words] name ("yolk", "white"), or null for the whole ingredient. */
    fun partOf(words: List<String>): String? = words.firstNotNullOfOrNull { PARTS[it] }
}
