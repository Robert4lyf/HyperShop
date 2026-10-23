package com.robert4lyf.hypershop.core.parse

import com.robert4lyf.hypershop.core.catalog.Catalog
import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import com.robert4lyf.hypershop.core.model.ParsedIngredient
import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.model.Rational
import kotlin.math.abs

/**
 * Claude's reading of a line, brought to the spec the rule parser follows where the two only differ in
 * convention, never in what is meant (U.1, measured on Claude's recorded readings of the labelled sets):
 *
 * - the shelf ([Shelf.ofWritten]): "tomatoes" in tins are tinned tomatoes, "minced lamb" lamb mince;
 * - R3.4 containers: "400g tin chopped tomatoes" is 1 tin of 400 g, not 400 g — the rule parser's reading
 *   is taken only when it says the same amount, as the tin's size;
 * - R3.3: "a pinch of nutmeg" is 1 pinch, where Claude gives the unit without the one;
 * - juice: "2 tbsp fresh lemon juice (from 1 lemon)" is 2 tbsp of lemon juice, not of lemons — taken only when
 *   the rule parser reads the same amount in the same unit.
 *
 * And it flags a line whose amount the rule parser reads differently ([DISAGREE]).
 *
 * Idempotent, so a reading aligned once can be aligned again (the recorded readings are scored through it).
 */
class ClaudeAlignment(
    catalog: Catalog = CatalogLoader.default,
) {
    private val rules = RuleParser(catalog)
    private val shelf = Shelf(catalog)

    fun align(p: ParsedIngredient): ParsedIngredient {
        var out = p.copy(name = shelf.ofWritten(p.name, p.unit))
        if (out.unit == QtyUnit.PINCH && out.quantity == null) out = out.copy(quantity = Rational.ONE)
        val r by lazy { rules.parse(p.raw) }
        val q = out.quantity
        if (q != null && out.unit != null && out.unit !in CONTAINERS) {
            if (r.unit in CONTAINERS && r.altUnit == out.unit && same(r.altQuantity, q) && out.quantityMax == null) {
                out = out.copy(quantity = r.quantity, unit = r.unit, altQuantity = q, altUnit = out.unit)
            }
        }
        val juiceOfIt = r.name.endsWith(" juice") && out.name == r.name.removeSuffix(" juice")
        if (juiceOfIt && out.unit == r.unit && same(r.quantity, out.quantity)) {
            out = out.copy(name = r.name)
        }
        // U.2: two readers that disagree on the amount are a line worth a look. Measured on Claude's recorded
        // readings of the labelled sets, this flags about a third of its wrong lines and 3 right ones in 540.
        // The item is not compared: the two name things differently far more often than either is wrong.
        if (!out.check && amountsDiffer(out, r)) out = out.copy(check = true, checkReason = DISAGREE)
        return out
    }

    private fun amountsDiffer(
        c: ParsedIngredient,
        r: ParsedIngredient,
    ): Boolean {
        if (c.unit?.dimension != r.unit?.dimension) return true
        if ((c.quantity == null) != (r.quantity == null)) return true
        return c.quantity != null && !same(c.quantity, r.quantity)
    }

    private fun same(
        a: Rational?,
        b: Rational?,
    ) = a != null && b != null && abs(a.toDouble() - b.toDouble()) <= 0.01 * maxOf(abs(b.toDouble()), 1e-9)

    companion object {
        const val DISAGREE = "the amount was read two ways"
        private val CONTAINERS = setOf(QtyUnit.TIN, QtyUnit.PACKET)
    }
}
