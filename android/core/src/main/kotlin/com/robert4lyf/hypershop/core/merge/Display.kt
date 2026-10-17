package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.catalog.CatalogEntry
import com.robert4lyf.hypershop.core.catalog.DisplayPolicy
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.BaseKey
import com.robert4lyf.hypershop.core.model.Dimension
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale

/** The units setting (PLAN §3.3). */
enum class UnitsSetting { METRIC, IMPERIAL, AS_WRITTEN }

/** A list row's two lines. */
data class RowText(
    /** What to put in the basket: "2 onions", "700 ml milk", "1 onion + 200 g". */
    val main: String,
    /** Context or the pack note. */
    val sub: String?,
)

/**
 * Display rounding (PLAN §3.3). Rounding never changes what is stored, so a
 * tick always fully covers. Counts round up (produce to the nearest ½); mass
 * and volume use friendly steps; fractions show as ½ ⅓ ¼ ¾.
 */
object Display {
    fun amount(
        q: QtyBase,
        units: UnitsSetting = UnitsSetting.METRIC,
        produce: Boolean = false,
        hint: QtyUnit? = null,
        gramsPerCup: Rational? = null,
        policy: DisplayPolicy? = null,
    ): String? {
        if (q.isPresence) return null
        var parts = q.parts.mapKeys { BaseKey.decode(it.key) }.filterValues { it > Rational.ZERO }
        // A cup of cheese or chicken is bought by weight, where the catalogue
        // knows the density. Spoons stay spoons.
        val solid = policy != null && policy != DisplayPolicy.VOLUME
        val spoons = hint == QtyUnit.TSP || hint == QtyUnit.TBSP
        if (gramsPerCup != null && !spoons && parts.containsKey(BaseKey.VOLUME) && (solid || parts.containsKey(BaseKey.MASS))) {
            val g = parts.getValue(BaseKey.VOLUME) * gramsPerCup / Rational.of(240)
            parts = parts - BaseKey.VOLUME + (BaseKey.MASS to (parts[BaseKey.MASS] ?: Rational.ZERO) + g)
        }
        if (parts.isEmpty()) return null
        val order = listOf(Dimension.COUNT, Dimension.MASS, Dimension.VOLUME)
        return parts.entries
            .sortedWith(compareBy({ order.indexOf(it.key.dimension) }, { it.key.countUnit?.ordinal ?: -1 }))
            .joinToString(" + ") { (k, v) ->
                when (k.dimension) {
                    Dimension.COUNT -> count(v, k.countUnit, produce)
                    Dimension.MASS -> mass(v, units, hint)
                    Dimension.VOLUME -> volume(v, units, hint)
                }
            }
    }

    /** The row text for an item: main line "<amount> <name>", sub-line the pack note or "2 already ticked". */
    fun row(
        list: ShoppingList,
        item: ListItem,
        entry: CatalogEntry?,
        units: UnitsSetting = UnitsSetting.METRIC,
    ): RowText {
        val done = list.isDone(item.id)
        val shownQty = if (done) list.total(item.id) else list.uncovered(item.id)
        val produce = item.aisle == AisleCode.PRODUCE
        val hint = list.contributionsOf(item.id).mapNotNull { it.unitHint }.distinct().singleOrNull()
        val presence = entry?.displayPolicy == DisplayPolicy.PRESENCE && units != UnitsSetting.AS_WRITTEN
        val amount = if (presence) null else amount(shownQty, units, produce, hint, entry?.gramsPerCup, entry?.displayPolicy)
        val itemsOnly = shownQty.keys.singleOrNull() == BaseKey.ITEMS
        val name =
            if (itemsOnly && entry != null && (shownQty[BaseKey.ITEMS] > Rational.ONE)) entry.plural else item.displayName
        val partial = !done && list.isPartial(item.id)
        val more = if (partial) " more" else ""
        val main =
            when {
                amount == null -> name
                itemsOnly -> "${amount.substringBefore(' ')}$more $name"
                else -> "$amount$more $name"
            }
        val sub =
            when {
                partial ->
                    amount(
                        list.covered(item.id),
                        units,
                        produce,
                        hint,
                        entry?.gramsPerCup,
                        entry?.displayPolicy,
                    )?.let { "$it already ticked" }
                else -> entry?.packNote
            }
        return RowText(main, sub)
    }

    // ---- per dimension ------------------------------------------------------------

    fun count(
        v: Rational,
        unit: QtyUnit?,
        produce: Boolean,
    ): String {
        // Counts round up; produce to the nearest half.
        val r = roundUp(v, if (produce) Rational.of(1, 2) else Rational.ONE)
        val n = fraction(r)
        if (unit == null || unit == QtyUnit.OTHER) return n
        val word = if (r > Rational.ONE) PLURALS[unit] ?: (unit.serial + "s") else unit.serial
        return "$n $word"
    }

    fun mass(
        g: Rational,
        units: UnitsSetting,
        hint: QtyUnit? = null,
    ): String {
        val imperial = units == UnitsSetting.IMPERIAL || (units == UnitsSetting.AS_WRITTEN && (hint == QtyUnit.OZ || hint == QtyUnit.LB))
        if (imperial) {
            val oz = g / QtyUnit.OZ.baseFactor(UnitLocale.US)!!
            return if (oz >= Rational.of(16)) {
                "${fraction(
                    roundUp(oz / Rational.of(16), Rational.of(1, 4)),
                )} lb"
            } else {
                "${fraction(roundUp(oz, Rational.of(1, 4)))} oz"
            }
        }
        val d = g.toDouble()
        return when {
            d >= 1000 -> "${trim(roundUpD(d / 1000, 0.1))} kg"
            d >= 100 -> "${trim(roundUpD(d, 10.0))} g"
            d >= 10 -> "${trim(roundUpD(d, 5.0))} g"
            else -> "${trim(roundUpD(d, 1.0))} g"
        }
    }

    fun volume(
        ml: Rational,
        units: UnitsSetting,
        hint: QtyUnit? = null,
    ): String {
        // Spoons are never converted, whatever the amount.
        if (hint == QtyUnit.TSP) return "${fraction(roundUp(ml / Rational.of(5), Rational.of(1, 4)))} tsp"
        if (hint == QtyUnit.TBSP) return "${fraction(roundUp(ml / Rational.of(15), Rational.of(1, 2)))} tbsp"
        val d = ml.toDouble()
        val spoons = units != UnitsSetting.METRIC && d < 60
        if (units == UnitsSetting.AS_WRITTEN && hint == QtyUnit.CUP || units == UnitsSetting.IMPERIAL && d >= 60) {
            return "${fraction(roundUp(ml / Rational.of(240), Rational.of(1, 4)))} ${if (ml > Rational.of(240)) "cups" else "cup"}"
        }
        if (spoons && d < 60) {
            return if (d < 15) {
                "${fraction(
                    roundUp(ml / Rational.of(5), Rational.of(1, 4)),
                )} tsp"
            } else {
                "${fraction(roundUp(ml / Rational.of(15), Rational.of(1, 2)))} tbsp"
            }
        }
        return when {
            d >= 1000 -> "${trim(roundUpD(d / 1000, 0.1))} l"
            d >= 100 -> "${trim(roundUpD(d, 10.0))} ml"
            d >= 10 -> "${trim(roundUpD(d, 5.0))} ml"
            else -> "${trim(roundUpD(d, 1.0))} ml"
        }
    }

    /** Rounds up to a multiple of [step]. */
    fun roundUp(
        v: Rational,
        step: Rational,
    ): Rational {
        val n = v / step
        val whole = Math.floorDiv(n.num, n.den) + if (n.num % n.den == 0L) 0 else 1
        return Rational.of(whole) * step
    }

    private fun roundUpD(
        v: Double,
        step: Double,
    ): Double = Math.ceil(v / step - 1e-9) * step

    private fun trim(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else "%.1f".format(d)

    /** 2½, ⅓, 1¼, 3. Other fractions fall back to one decimal. */
    fun fraction(r: Rational): String {
        val whole = r.num / r.den
        val frac = r - Rational.of(whole)
        val glyph =
            when (frac) {
                Rational.ZERO -> ""
                Rational.of(1, 2) -> "½"
                Rational.of(1, 3) -> "⅓"
                Rational.of(2, 3) -> "⅔"
                Rational.of(1, 4) -> "¼"
                Rational.of(3, 4) -> "¾"
                else -> return "%.1f".format(r.toDouble())
            }
        return if (whole == 0L && glyph.isNotEmpty()) glyph else "$whole$glyph"
    }

    private val PLURALS =
        mapOf(
            QtyUnit.BUNCH to "bunches",
            QtyUnit.PINCH to "pinches",
            QtyUnit.DASH to "dashes",
            QtyUnit.PACKET to "packets",
        )
}
