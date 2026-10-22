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
    /** An unhandled pattern (R7), or an amount Claude gave that made no sense: look at the line. */
    val check: Boolean = false,
    /** Why [check] is set, in a few words for the review screen ("has 'or'"). */
    val checkReason: String? = null,
) {
    /** R1 derived flag. */
    val garnish: Boolean
        get() = GARNISH.any { note.orEmpty().lowercase().contains(it) || raw.lowercase().contains(it) }

    /** R1 derived flag: no quantity, or "to taste"-style lines are never scaled. */
    val noScale: Boolean
        get() = quantity == null || NO_SCALE.any { raw.lowercase().contains(it) }

    /**
     * The metric amount the recipe gives alongside a cup or ounce amount (the
     * 500 ml in "2 cups (500ml)" or "2 cups milk (500ml)"), or the grams alongside
     * spoons ("2 tbsp (30g)"). It is what the author measured, so it wins over
     * converting; spoons with only an ml alternative stay spoons.
     */
    private val metricAlt: Pair<Rational, QtyUnit>?
        get() {
            val u = unit ?: return null
            if (quantity == null || u.dimension == Dimension.COUNT || u in METRIC) return null
            val accepts = if (u == QtyUnit.TSP || u == QtyUnit.TBSP) GRAMS else METRIC
            if (altQuantity != null && altUnit in accepts) return altQuantity to altUnit!!
            // The rule parser moves a trailing "(500ml)" into the note as "500ml" (R4.3). Only a note that
            // *is* an amount counts: "from a 250g block" or "plus 50g for dusting" describe something else.
            val m = note.orEmpty().split(", ").firstNotNullOfOrNull { METRIC_IN_NOTE.matchEntire(it.trim()) } ?: return null
            val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
            val mu = QtyUnit.entries.firstOrNull { it.serial == m.groupValues[2].lowercase() }?.takeIf { it in accepts } ?: return null
            return Rational.fromDouble(v) to mu
        }

    /** The unit to shop by: [unit], or the metric one from [metricAlt]. */
    val shopUnit: QtyUnit?
        get() = metricAlt?.second ?: unit

    /** The amount to shop by, using the upper end of a range. */
    fun shopBase(locale: UnitLocale): QtyBase {
        val alt = metricAlt ?: return QtyBase.of(quantityMax ?: quantity, unit, locale)
        // Scale the alternative by max/quantity; a zero lower bound ("0-2 cups") has no ratio.
        val q = quantity!!
        val amount = quantityMax?.takeIf { !q.isZero }?.let { alt.first * it / q } ?: alt.first
        return QtyBase.of(amount, alt.second, locale)
    }

    fun base(locale: UnitLocale): QtyBase = QtyBase.of(quantity, unit, locale)

    companion object {
        private val GARNISH = listOf("garnish", "to serve", "for serving", "to decorate")
        private val NO_SCALE = listOf("to taste", "for frying", "for greasing", "for dusting", "as needed")
        private val METRIC = setOf(QtyUnit.G, QtyUnit.KG, QtyUnit.ML, QtyUnit.L)
        private val GRAMS = setOf(QtyUnit.G, QtyUnit.KG)

        // Digits bounded as in the tokeniser: a 320-digit "amount" in a note would parse to Infinity and crash fromDouble.
        private val METRIC_IN_NOTE =
            Regex("""(?:about |approx\.? |approximately )?(\d{1,9}(?:[.,]\d{1,4})?)\s*(ml|g|kg|l)\.?""", RegexOption.IGNORE_CASE)
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
