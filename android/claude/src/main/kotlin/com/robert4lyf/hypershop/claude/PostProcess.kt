package com.robert4lyf.hypershop.claude

import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.ParsedIngredient
import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.model.Rational

/**
 * Turns Claude's JSON into the shared [ParsedIngredient] (spec R1). Names are
 * kept as Claude wrote them; the shared normaliser maps them to a mergeKey and
 * the units to QtyBase downstream, exactly as for the rule parser.
 *
 * Numeric ranges are checked here, not in the schema: an out-of-range amount
 * is dropped and the line marked `check`, so it is highlighted in review.
 */
object PostProcess {
    const val MAX_QTY = 100_000.0
    const val MAX_INGREDIENTS = 300
    const val MAX_LINE = 500

    fun recipe(
        out: ClaudeOutput,
        source: String,
        model: String,
    ): ClaudeRecipe {
        val status =
            when (out.status) {
                "ok" -> RecipeStatus.OK
                "no_ingredients" -> RecipeStatus.NO_INGREDIENTS
                "not_a_recipe" -> RecipeStatus.NOT_A_RECIPE
                else -> throw IllegalArgumentException("status ${out.status}")
            }
        return ClaudeRecipe(
            status = status,
            title = out.title?.trim()?.take(200)?.ifEmpty { null },
            servings = out.servings?.takeIf { it in 1..100 },
            ingredients = out.ingredients.take(MAX_INGREDIENTS).map(::ingredient).filter { it.name.isNotBlank() },
            // Cheap checks first: the substring search over the whole source runs only for plausible URLs.
            sourceUrls = out.sourceUrls.filter { it.length <= 2000 && it.startsWith("https://") && source.contains(it) }.distinct(),
            model = model,
        )
    }

    fun ingredient(i: ClaudeIngredient): ParsedIngredient {
        var check = false

        fun amount(d: Double?): Rational? {
            if (d == null) return null
            if (!d.isFinite() || d < 0 || d > MAX_QTY) {
                check = true
                return null
            }
            return if (d == 0.0) null else Rational.fromDouble(d)
        }

        fun unit(s: String?): QtyUnit? =
            s?.let {
                QtyUnit.fromSerial(it) ?: run {
                    check = true
                    QtyUnit.OTHER
                }
            }
        val q = amount(i.quantity)
        var qMax = amount(i.quantityMax)
        if (qMax != null && (q == null || qMax <= q)) {
            if (q == null || qMax < q) check = true
            qMax = null
        }
        return ParsedIngredient(
            raw = i.raw.take(MAX_LINE),
            name = i.name.trim().lowercase().take(MAX_LINE),
            quantity = q,
            quantityMax = qMax,
            quantityText = i.quantityText?.take(60),
            unit = unit(i.unit),
            altQuantity = amount(i.altQuantity),
            altUnit = unit(i.altUnit),
            note = i.note?.trim()?.take(MAX_LINE)?.ifEmpty { null },
            section = i.section?.trim()?.take(100)?.ifEmpty { null },
            optional = i.optional,
            estimated = i.estimated,
            aisleSuggestion = i.aisleSuggestion?.let { a -> AisleCode.entries.firstOrNull { it.name == a } },
            check = check,
        )
    }
}
