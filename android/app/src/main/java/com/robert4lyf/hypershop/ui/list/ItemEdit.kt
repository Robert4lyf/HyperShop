package com.robert4lyf.hypershop.ui.list

import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.Dimension
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.QuantityInput
import com.robert4lyf.hypershop.core.parse.RuleParser
import java.math.BigDecimal

/**
 * What the item sheet's Save changes. Null means "leave it": [groupChanged] is
 * separate because no category is a choice too.
 */
data class ItemChanges(
    val name: String? = null,
    val amount: String? = null,
    val aisle: AisleCode? = null,
    /** Put it in [aisle] from now on, not only on this list. */
    val alwaysAisle: Boolean = true,
    val groupChanged: Boolean = false,
    val group: String? = null,
    val packChanged: Boolean = false,
    val pack: String? = null,
) {
    val isEmpty: Boolean get() = name == null && amount == null && aisle == null && !groupChanged && !packChanged
}

/** The amount field's text: a total the parser reads back, and its unit swapped by a chip. */
object ItemEdit {
    /** The unit chips under the amount; "each" is a plain count. */
    val UNITS = listOf(EACH, "g", "kg", "ml", "l", "tin", "jar", "packet", "bunch")

    const val EACH = "each"

    private val amount = Regex("""^\s*(\d[\d.,/\s½¼¾⅓⅔]*?)\s*([^\d\s.,/½¼¾⅓⅔].*)?$""")

    /**
     * The total as text to edit ("400 g", "1.5 kg", "2 tin", "3"), or blank when it
     * is not one amount ("2 + 200 g", or no amount at all): the field then starts empty.
     */
    fun text(total: QtyBase): String {
        val (k, v) = total.parts.entries.singleOrNull() ?: return ""
        val key = BaseKey.decode(k)
        val big = v >= Rational.of(1000)
        return when (key.dimension) {
            Dimension.MASS -> if (big) "${number(v / Rational.of(1000))} kg" else "${number(v)} g"
            Dimension.VOLUME -> if (big) "${number(v / Rational.of(1000))} l" else "${number(v)} ml"
            Dimension.COUNT -> key.countUnit?.let { "${number(v)} ${it.serial}" } ?: number(v)
        }
    }

    /** Exact conversion only. Null asks the user to enter an amount in the new measure. */
    fun withUnit(
        text: String,
        unit: String,
    ): String? {
        val parsed = RuleParser().parse(text.trim() + " x")
        val q = parsed.quantity ?: return null
        val old = parsed.base(UnitLocale.METRIC)
        val new = RuleParser().parse(if (unit == EACH) "1 x" else "1 $unit x").base(UnitLocale.METRIC)
        if (old.keys != new.keys || old.isPresence || new.isPresence) return null
        val value = q * (parsed.unit?.baseFactor(UnitLocale.METRIC) ?: Rational.ONE) / new.parts.values.single()
        return if (unit == EACH) number(value) else "${number(value)} $unit"
    }

    /**
     * The unit chips shown before "More units": the current unit, the ones it converts to exactly, and a plain
     * count. With no recognised unit, a count, grams and millilitres. In [UNITS] order.
     */
    fun commonUnits(current: String?): List<String> {
        val family =
            when (current) {
                "g", "kg" -> setOf("g", "kg")
                "ml", "l" -> setOf("ml", "l")
                null, EACH -> setOf("g", "ml")
                else -> setOf(current)
            }
        return UNITS.filter { it == EACH || it in family }
    }

    /** What the incompatible-unit dialog's amount field reads as: the new amount text, or why it can't be used. */
    data class Proposal(
        val text: String?,
        val error: String?,
    )

    private val number = Regex("""^[\d\s.,/½¼¾⅓⅔]+$""")

    /**
     * An amount typed for [unit] when no conversion exists: a positive number ("2", "1.5", "1/3", "1 ½") that the
     * list reads back as one amount. The number is kept as typed, so fractions stay exact.
     */
    fun propose(
        typed: String,
        unit: String,
    ): Proposal {
        val n = typed.trim()
        if (n.isEmpty()) return Proposal(null, "Enter the amount in $unit")
        if (n.startsWith("-") || n.startsWith("−")) return Proposal(null, "Enter an amount above zero")
        if (!number.matches(n) || n.none { it.isDigit() || it in "½¼¾⅓⅔" }) {
            return Proposal(null, "Enter a number, like 2, 1.5 or 1/2")
        }
        val text = if (unit == EACH) n else "$n $unit"
        val q = RuleParser().parse("$n x").quantity
        if (q != null && q.isZero) return Proposal(null, "Enter an amount above zero")
        if (q == null || QuantityInput.parse(text) == null) return Proposal(null, "Enter a number, like 2, 1.5 or 1/2")
        return Proposal(text, null)
    }

    /** The chip [text]'s unit matches, so the chip shows selected; null when none does. */
    fun unitOf(text: String): String? {
        val m = amount.find(text) ?: return null
        val word = m.groupValues[2].trim().lowercase()
        if (word.isEmpty()) return EACH
        return UNITS.firstOrNull { word == it || word == it + "s" || (it == "bunch" && word == "bunches") }
    }

    private fun number(r: Rational): String =
        if (r.isWhole) {
            r.toString()
        } else {
            runCatching {
                BigDecimal(r.num).divide(BigDecimal(r.den)).stripTrailingZeros().toPlainString()
            }.getOrElse { r.toString() }
        }
}
