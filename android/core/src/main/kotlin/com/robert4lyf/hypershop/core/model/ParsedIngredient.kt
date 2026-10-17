package com.robert4lyf.hypershop.core.model

import kotlinx.serialization.Serializable

enum class ParsedBy { RULES, CLAUDE }

/**
 * One ingredient line, in the shape of docs/parser-spec.md R1. Produced by the
 * rule parser and by Claude (after post-processing) alike.
 */
@Serializable
data class ParsedIngredient(
    val raw: String,
    val name: String,
    val quantity: Rational? = null,
    val quantityMax: Rational? = null,
    val quantityText: String? = null,
    val unit: QtyUnit? = null,
    val altQuantity: Rational? = null,
    val altUnit: QtyUnit? = null,
    val note: String? = null,
    val section: String? = null,
    val optional: Boolean = false,
    val estimated: Boolean = false,
    val aisleSuggestion: AisleCode? = null,
    /** Rule parser only: an unhandled pattern (R7). */
    val check: Boolean = false,
) {
    /** R1 derived flag. */
    val garnish: Boolean
        get() = GARNISH.any { note.orEmpty().lowercase().contains(it) || raw.lowercase().contains(it) }

    /** R1 derived flag: no quantity, or "to taste"-style lines are never scaled. */
    val noScale: Boolean
        get() = quantity == null || NO_SCALE.any { raw.lowercase().contains(it) }

    /**
     * The metric amount the recipe gives for a cup or ounce amount: the
     * 500 ml in "2 cups (500ml)" or "2 cups milk (500ml)". It is what the author
     * measured, so it wins over converting the cups (whose size varies).
     */
    private val metricAlt: Pair<Rational, QtyUnit>?
        get() {
            val u = unit ?: return null
            // Spoons are kept as written, and metric amounts need no help.
            if (quantity == null || u.dimension == Dimension.COUNT || u in METRIC || u == QtyUnit.TSP || u == QtyUnit.TBSP) return null
            if (altQuantity != null && altUnit in METRIC) return altQuantity to altUnit!!
            // The rule parser moves a trailing "(500ml)" into the note as "500ml" (R4.3).
            val m = METRIC_IN_NOTE.find(note.orEmpty()) ?: return null
            val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val mu = QtyUnit.entries.firstOrNull { it.serial == m.groupValues[2].lowercase() } ?: return null
            return Rational.fromDouble(v) to mu
        }

    /** The unit to shop by: [unit], or the metric one from [metricAlt]. */
    val shopUnit: QtyUnit?
        get() = metricAlt?.second ?: unit

    /** The amount to shop by, using the upper end of a range. */
    fun shopBase(locale: UnitLocale): QtyBase {
        val alt = metricAlt ?: return QtyBase.of(quantityMax ?: quantity, unit, locale)
        val amount = quantityMax?.let { alt.first * it / quantity!! } ?: alt.first
        return QtyBase.of(amount, alt.second, locale)
    }

    fun base(locale: UnitLocale): QtyBase = QtyBase.of(quantity, unit, locale)

    companion object {
        private val GARNISH = listOf("garnish", "to serve", "for serving", "to decorate")
        private val NO_SCALE = listOf("to taste", "for frying", "for greasing", "for dusting", "as needed")
        private val METRIC = setOf(QtyUnit.G, QtyUnit.KG, QtyUnit.ML, QtyUnit.L)
        private val METRIC_IN_NOTE = Regex("""(?<![\d.,])(\d+(?:[.,]\d+)?)\s*(ml|g|kg|l)\b""", RegexOption.IGNORE_CASE)
    }
}

/** A recipe as imported, before review. */
@Serializable
data class RecipeImport(
    val url: String?,
    val title: String?,
    val servings: Int?,
    val unitLocale: UnitLocale,
    val ingredients: List<ParsedIngredient>,
    val parsedBy: ParsedBy,
    val model: String? = null,
    val claudePending: Boolean = false,
)
