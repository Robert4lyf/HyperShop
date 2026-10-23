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
import java.util.Locale

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
        // A cup of something solid the catalogue has no density for: millilitres of chickpeas
        // would be a conversion nobody shops by, and grams would be a guess. The recipe's cups stay.
        // (Bought by weight or by count; a "presence" item such as wine is shown in ml as before.)
        val weighedOrCounted = policy == DisplayPolicy.MASS || policy == DisplayPolicy.COUNT
        val cupsOfSolid = weighedOrCounted && hint == QtyUnit.CUP && parts.containsKey(BaseKey.VOLUME)
        val order = listOf(Dimension.COUNT, Dimension.MASS, Dimension.VOLUME)
        return parts.entries
            .sortedWith(compareBy({ order.indexOf(it.key.dimension) }, { it.key.countUnit?.ordinal ?: -1 }))
            .joinToString(" + ") { (k, v) ->
                when (k.dimension) {
                    Dimension.COUNT -> count(v, k.countUnit, produce)
                    Dimension.MASS -> mass(v, units, hint)
                    Dimension.VOLUME -> if (cupsOfSolid) solidCups(v) else volume(v, units, hint)
                }
            }
    }

    /** The row text for an item: main line "<amount> <name>", sub-line the pack note or "2 already bought". */
    fun row(
        list: ShoppingList,
        item: ListItem,
        entry: CatalogEntry?,
        units: UnitsSetting = UnitsSetting.METRIC,
    ): RowText {
        val done = list.isDone(item.id)
        val shownQty = if (done) list.total(item.id) else list.uncovered(item.id)
        val produce = item.aisle == AisleCode.PRODUCE
        val hint = hintOf(list.contributionsOf(item.id).map { it.unitHint })
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
                    )?.let { "$it already bought" }
                else -> entry?.packNote?.let(::compactUnits)
            }
        return RowText(main, sub)
    }

    /**
     * "1 × 30 g pack" → "1 × 30g pack": abbreviated units sit right after their
     * number, as amounts are shown; words ("2 cups", "1 pint") keep their space.
     */
    fun compactUnits(text: String): String = COMPACT.replace(text) { "${it.groupValues[1]}${it.groupValues[2]}" }

    private val COMPACT = Regex("""(\d|[½⅓⅔¼¾⅛])\s+(kg|g|ml|l|cl|oz|lb|tsp|tbsp)\b""")

    /**
     * The unit to show an amount in: the one its lines share, or tablespoons when
     * they mix teaspoons and tablespoons ("1 tbsp" + "1 tsp" of maple syrup), so
     * spoons stay spoons rather than becoming 20ml. Null when the lines disagree.
     */
    fun hintOf(units: Iterable<QtyUnit?>): QtyUnit? {
        val u = units.filterNotNull().distinct()
        if (u.size == 1) return u.single()
        // Spoons stay spoons even beside another dimension ("75g" from one recipe,
        // "4 tbsp" from another): the hint only shapes the volume part.
        val volume = u.filter { it.dimension == Dimension.VOLUME }
        return when {
            volume.isNotEmpty() && volume.all { it == QtyUnit.TSP || it == QtyUnit.TBSP } ->
                if (volume.size == 1) volume.single() else QtyUnit.TBSP
            else -> null
        }
    }

    /**
     * An ingredient bought by the pack (herbs, a jar of spice): whatever the recipe
     * asks for, you buy packs. "1 pack", or "2 packs" when the recipes count two;
     * just the number when the catalogue has no pack word ("each").
     */
    fun packs(
        q: QtyBase,
        shoppingUnit: String?,
    ): String {
        val n = q.parts[BaseKey.ITEMS.encode()]?.takeIf { q.parts.size == 1 }?.let { roundUp(it, Rational.ONE) } ?: Rational.ONE
        val word = shoppingUnit?.trim()?.takeIf { it.isNotEmpty() && it != "each" } ?: return fraction(n)
        val plural = if (word.endsWith("ch") || word.endsWith("sh") || word.endsWith("x") || word.endsWith("s")) word + "es" else word + "s"
        return "${fraction(n)} ${if (n > Rational.ONE) plural else word}"
    }

    /** Tablespoons, to the half; what doesn't fit goes in teaspoons: "1½tbsp", "1tbsp + 1tsp", "2tsp". */
    private fun tablespoons(ml: Rational): String {
        val half = Rational.of(15, 2)
        if ((ml / half).den == 1L) return "${fraction(ml / Rational.of(15))}tbsp"
        var whole = Math.floorDiv((ml / Rational.of(15)).num, (ml / Rational.of(15)).den)
        val rest = ml - Rational.of(whole * 15)
        val tsp = roundUp(rest / Rational.of(5), Rational.of(1, 4))
        // Three teaspoons are a tablespoon: 29.25 ml is "2tbsp", not "1tbsp + 3tsp".
        if (tsp >= Rational.of(3)) {
            whole++
            return "${whole}tbsp"
        }
        return if (whole == 0L) "${fraction(tsp)}tsp" else "${whole}tbsp + ${fraction(tsp)}tsp"
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
                )}lb"
            } else {
                "${fraction(roundUp(oz, Rational.of(1, 4)))}oz"
            }
        }
        // Rounded first, so 998 g reads "1kg" and not "1000g".
        val d = g.toDouble().let { if (it >= 100) roundUpD(it, 10.0) else it }
        return when {
            d >= 1000 -> "${trim(roundUpD(d / 1000, 0.1))}kg"
            d >= 100 -> "${trim(roundUpD(d, 10.0))}g"
            d >= 10 -> "${trim(roundUpD(d, 5.0))}g"
            else -> "${trim(roundUpD(d, 1.0))}g"
        }
    }

    private fun cups(ml: Rational): String =
        "${fraction(roundUp(ml / Rational.of(240), Rational.of(1, 4)))} ${if (ml > Rational.of(240)) "cups" else "cup"}"

    /**
     * Cups as the recipe wrote them. A metric cup is 250 ml, 4 % over the US 240, so an amount
     * within 5 % above a quarter cup is that quarter: one metric cup reads "1 cup", not "1¼".
     */
    private fun solidCups(ml: Rational): String {
        val q = ml / Rational.of(240)
        val quarter = Rational.of(1, 4)
        val down = roundUp(q, quarter).let { up -> if (up > q) up - quarter else up }
        val c = if (down > Rational.ZERO && q <= down * Rational.of(105, 100)) down else roundUp(q, quarter)
        return "${fraction(c)} ${if (c > Rational.ONE) "cups" else "cup"}"
    }

    fun volume(
        ml: Rational,
        units: UnitsSetting,
        hint: QtyUnit? = null,
    ): String {
        // Spoons are never converted, whatever the amount.
        if (hint == QtyUnit.TSP) return "${fraction(roundUp(ml / Rational.of(5), Rational.of(1, 4)))}tsp"
        if (hint == QtyUnit.TBSP) return tablespoons(ml)
        val d = ml.toDouble()
        // Small amounts become spoons in imperial, and "as written" unless they were written in ml or l.
        val writtenMetric = hint == QtyUnit.ML || hint == QtyUnit.L
        val spoons = d < 60 && (units == UnitsSetting.IMPERIAL || units == UnitsSetting.AS_WRITTEN && !writtenMetric)
        if (units == UnitsSetting.AS_WRITTEN && hint == QtyUnit.CUP || units == UnitsSetting.IMPERIAL && d >= 60) return cups(ml)
        if (spoons && d < 60) {
            // Under a tablespoon: teaspoons, unless they round up to three (14 ml is "1tbsp", not "3tsp").
            val tsp = roundUp(ml / Rational.of(5), Rational.of(1, 4))
            return if (d < 15 && tsp < Rational.of(3)) {
                "${fraction(tsp)}tsp"
            } else {
                "${fraction(roundUp(ml / Rational.of(15), Rational.of(1, 2)))}tbsp"
            }
        }
        val r = if (d >= 100) roundUpD(d, 10.0) else d
        return when {
            r >= 1000 -> "${trim(roundUpD(r / 1000, 0.1))}l"
            d >= 100 -> "${trim(roundUpD(d, 10.0))}ml"
            d >= 10 -> "${trim(roundUpD(d, 5.0))}ml"
            else -> "${trim(roundUpD(d, 1.0))}ml"
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

    // Locale.ROOT: a German phone would otherwise show "1,1kg" beside "1/2" and the tests' "1.1kg".
    private fun trim(d: Double): String = if (d == Math.floor(d)) d.toLong().toString() else String.format(Locale.ROOT, "%.1f", d)

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
                else -> return String.format(Locale.ROOT, "%.1f", r.toDouble())
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
