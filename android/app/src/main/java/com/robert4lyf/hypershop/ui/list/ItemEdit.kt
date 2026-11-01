package com.robert4lyf.hypershop.ui.list

import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.Dimension
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import java.math.BigDecimal
import java.math.RoundingMode

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
) {
    val isEmpty: Boolean get() = name == null && amount == null && aisle == null && !groupChanged
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

    /** [text] with its unit replaced by [unit], keeping the number (1 when there is none yet). */
    fun withUnit(
        text: String,
        unit: String,
    ): String {
        val n = amount.find(text)?.groupValues?.get(1)?.trim().orEmpty().ifEmpty { "1" }
        return if (unit == EACH) n else "$n $unit"
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
            BigDecimal(r.toDouble()).setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString()
        }
}
