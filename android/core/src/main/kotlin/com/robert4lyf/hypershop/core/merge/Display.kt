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

    /** What a row asks you to buy, in the way the thing is bought (W.2): "1 bulb", "2", "1 pot"; [need] the recipes' amount. */
    data class Buy(
        val amount: String,
        val count: Rational,
        val each: Boolean,
        val need: String?,
        /** "1 punnet cherry tomatoes": after a container the name is plural. */
        val pluralName: Boolean = false,
    )

    /**
     * W.2: the amount in the way the catalogue says the thing is bought, or null to show the recipes' own amount
     * as before (bought by weight, a staple, as-written units, or nothing to convert with). Never less than needed:
     * every conversion rounds up to a whole thing.
     *
     * - by the each (onions, lemons): whole ones, from a count, a weight ([CatalogEntry.gramsEach]) or a cup
     *   ([CatalogEntry.gramsPerCup] then weight); half an onion is one onion;
     * - garlic: bulbs, about ten cloves each;
     * - in a container (a jar, pot, bottle, pack of herbs): how many of them, from the pack note's size when it
     *   has one, else one; a non-staple with no amount at all (basil, vodka) is still one.
     */
    fun buy(
        q: QtyBase,
        entry: CatalogEntry?,
        units: UnitsSetting,
        hint: QtyUnit?,
    ): Buy? {
        if (entry == null || units == UnitsSetting.AS_WRITTEN) return null
        val unit = entry.shoppingUnit.trim().lowercase()
        val parts = q.parts.mapKeys { BaseKey.decode(it.key) }.filterValues { it > Rational.ZERO }
        val asWritten = amount(q, units, entry.aisle == AisleCode.PRODUCE, hint, entry.gramsPerCup, entry.displayPolicy)
        if (unit == "bulb") {
            val cloves = parts.entries.filter { it.key.countUnit == QtyUnit.CLOVE }.fold(Rational.ZERO) { a, e -> a + e.value }
            val bulbs = parts[BaseKey.ITEMS] ?: Rational.ZERO
            val weighed = parts[BaseKey.MASS]?.let { g -> entry.gramsEach?.let { g / it } ?: return null } ?: Rational.ZERO
            if (parts.keys.any { it != BaseKey.ITEMS && it != BaseKey.MASS && it.countUnit != QtyUnit.CLOVE }) return null
            val n = roundUp(bulbs + weighed + cloves / Rational.of(CLOVES_A_BULB), Rational.ONE).coerceAtLeastOne()
            return Buy(
                "${fraction(n)} ${if (n > Rational.ONE) "bulbs" else "bulb"}",
                n,
                false,
                asWritten?.takeIf {
                    n != bulbs
                }?.let { "$it in the recipe" },
            )
        }
        if (unit in EACH_UNITS && entry.displayPolicy == DisplayPolicy.COUNT) {
            if (parts.isEmpty()) return null
            var n = Rational.ZERO
            var converted = false
            for ((k, v) in parts) {
                n +=
                    when {
                        k == BaseKey.ITEMS -> v
                        k == BaseKey.MASS ->
                            entry.gramsEach?.let {
                                converted = true
                                v / it
                            } ?: return null
                        k == BaseKey.VOLUME -> {
                            val g = entry.gramsPerCup?.let { v * it / Rational.of(240) } ?: return null
                            converted = true
                            entry.gramsEach?.let { g / it } ?: return null
                        }
                        else -> return null // counted in its own unit (sticks, slices): shown as written
                    }
            }
            val whole = roundUp(n, Rational.ONE).coerceAtLeastOne()
            val need = if (converted || whole != n) asWritten?.let { "$it in the recipe" } else null
            return Buy(fraction(whole), whole, true, need)
        }
        // Meat and fish keep their weight (X.3): packs of chicken run from 300 g to a kilo, so "3 packs" says less
        // than "800g" does. A count the recipe gave ("2 chicken breasts") stays as written below anyway.
        if (unit in CONTAINERS && !entry.staple && entry.displayPolicy != DisplayPolicy.MASS && entry.aisle != AisleCode.MEAT_FISH) {
            // Counted in a container already ("1 bunch", "2 tins"): that count, which a presence row would hide.
            val counted = parts.keys.singleOrNull()?.countUnit
            if (counted in COUNTED_CONTAINERS) {
                val n = roundUp(parts.values.single(), Rational.ONE).coerceAtLeastOne()
                val word = if (n > Rational.ONE) pluralOf(counted!!.serial) else counted!!.serial
                return Buy("${fraction(n)} $word", n, false, null, pluralName = true)
            }
            // Counted as things ("2 chicken breasts", sold in packs of no fixed number), or mixed: as written.
            if (parts.keys.any { it.dimension == Dimension.COUNT }) return null
            // A presence item (herbs, spirits, a paste) is one of whatever it comes in when the amount cannot be
            // counted: the catalogue's word that a recipe's use rarely needs more, with the amount on the sub-line.
            val fallback = if (entry.displayPolicy == DisplayPolicy.PRESENCE) Rational.ONE else null
            // Nothing said ("basil, to serve"): one. An amount is counted in the pack's own dimension, a cup weighed
            // (or a weight measured) with the catalogue's density. Without one, only "one pack" is safe to say, and
            // only when the amount fits it even at an extreme density ("60ml yoghurt" in a 500 g pot); otherwise a
            // count could come out short ("700ml passata" against a 500 g carton) and the row stays as written.
            val n =
                if (parts.isEmpty()) {
                    Rational.ONE
                } else {
                    countIn(parts, entry) ?: fallback ?: return null
                }
            val word = if (n > Rational.ONE) pluralOf(unit) else unit
            return Buy("${fraction(n)} $word", n, false, asWritten?.let { "$it needed" }, pluralName = true)
        }
        return null
    }

    /** How many packs [parts] fill, rounded up; null when that cannot be said without risking too few. */
    private fun countIn(
        parts: Map<BaseKey, Rational>,
        entry: CatalogEntry,
    ): Rational? {
        val (base, key) = entry.packNote?.let(::packSize) ?: return null
        val perCup = entry.gramsPerCup
        var total = Rational.ZERO
        var guessed = false
        for ((k, v) in parts) {
            total +=
                when {
                    k == key -> v
                    key == BaseKey.MASS && k == BaseKey.VOLUME && perCup != null -> v * perCup / Rational.of(240)
                    key == BaseKey.VOLUME && k == BaseKey.MASS && perCup != null -> v * Rational.of(240) / perCup
                    // Heavier than honey (1.4 g/ml), lighter than flour (0.5): the worst either way.
                    key == BaseKey.MASS && k == BaseKey.VOLUME -> v * Rational.of(3, 2).also { guessed = true }
                    key == BaseKey.VOLUME && k == BaseKey.MASS -> v * Rational.of(2).also { guessed = true }
                    else -> return null
                }
        }
        if (guessed && total > base) return null
        return roundUp(total / base, Rational.ONE).coerceAtLeastOne()
    }

    /**
     * The amount a review or "at home" row shows, matching what its list row will say: the buying unit where
     * [buy] has one ("2" bananas, "1 jar"), else the amount as before. With the name, plural when it is counted.
     */
    fun brief(
        q: QtyBase,
        entry: CatalogEntry?,
        name: String,
        units: UnitsSetting = UnitsSetting.METRIC,
        produce: Boolean = false,
        hint: QtyUnit? = null,
    ): Pair<String?, String> {
        buy(q, entry, units, hint)?.let { b ->
            val plural = entry != null && (b.pluralName || b.each && b.count > Rational.ONE)
            return b.amount to (if (plural) entry!!.plural else name)
        }
        if (entry?.displayPolicy == DisplayPolicy.PRESENCE && units != UnitsSetting.AS_WRITTEN) {
            return packs(q, entry.shoppingUnit) to name
        }
        return amount(q, units, produce, hint, entry?.gramsPerCup, entry?.displayPolicy) to name
    }

    private fun Rational.coerceAtLeastOne() = if (this < Rational.ONE) Rational.ONE else this

    private fun pluralOf(word: String) = if (listOf("ch", "sh", "x", "s").any { word.endsWith(it) }) word + "es" else word + "s"

    /** A pack note's size: "1 × 300 ml pot" is 300 ml; null when it gives none. */
    private fun packSize(note: String): Pair<Rational, BaseKey>? {
        val m = PACK_SIZE.find(note) ?: return null
        val v = Rational.fromDouble(m.groupValues[1].toDouble())
        return when (m.groupValues[2].lowercase()) {
            "g" -> v to BaseKey.MASS
            "kg" -> v * Rational.of(1000) to BaseKey.MASS
            "ml" -> v to BaseKey.VOLUME
            "l" -> v * Rational.of(1000) to BaseKey.VOLUME
            else -> null
        }
    }

    private const val CLOVES_A_BULB = 10L
    private val EACH_UNITS = setOf("each", "head", "piece", "loaf", "ball")
    private val CONTAINERS = setOf("jar", "bottle", "pot", "carton", "tub", "pack", "bag", "box", "punnet", "bunch", "tube")
    private val COUNTED_CONTAINERS = setOf(QtyUnit.BUNCH, QtyUnit.TIN, QtyUnit.PACKET, QtyUnit.SPRIG)
    private val PACK_SIZE = Regex("""(\d+(?:\.\d+)?)\s*(kg|g|ml|l)\b""", RegexOption.IGNORE_CASE)

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
        val bought = buy(shownQty, entry, units, hint)
        val presence = entry?.displayPolicy == DisplayPolicy.PRESENCE && units != UnitsSetting.AS_WRITTEN
        val amount = if (presence) null else amount(shownQty, units, produce, hint, entry?.gramsPerCup, entry?.displayPolicy)
        val itemsOnly = shownQty.keys.singleOrNull() == BaseKey.ITEMS
        val name =
            when {
                bought != null && entry != null && (bought.pluralName || bought.each && bought.count > Rational.ONE) -> entry.plural
                bought != null -> item.displayName
                itemsOnly && entry != null && (shownQty[BaseKey.ITEMS] > Rational.ONE) -> entry.plural
                else -> item.displayName
            }
        val partial = !done && list.isPartial(item.id)
        val more = if (partial) " more" else ""
        val main =
            when {
                bought != null -> "${bought.amount}$more $name"
                amount == null -> name
                itemsOnly -> "${amount.substringBefore(' ')}$more $name"
                else -> "$amount$more $name"
            }
        val sub =
            when {
                bought?.need != null && !partial -> bought.need
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
