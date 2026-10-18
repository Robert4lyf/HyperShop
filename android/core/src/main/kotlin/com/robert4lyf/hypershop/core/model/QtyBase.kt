package com.robert4lyf.hypershop.core.model

import kotlinx.serialization.Serializable

/**
 * One slot of a [QtyBase]: mass (g), volume (ml), or a count of a particular
 * count unit (`null` = whole items: "2 onions"). Serialised as "mass",
 * "volume", "count" or "count:<unit serial>".
 */
data class BaseKey(
    val dimension: Dimension,
    val countUnit: QtyUnit? = null,
) {
    init {
        require(dimension == Dimension.COUNT || countUnit == null) { "only counts carry a unit" }
    }

    fun encode(): String =
        when (dimension) {
            Dimension.MASS -> "mass"
            Dimension.VOLUME -> "volume"
            Dimension.COUNT -> if (countUnit == null) "count" else "count:${countUnit.serial}"
        }

    companion object {
        val MASS = BaseKey(Dimension.MASS)
        val VOLUME = BaseKey(Dimension.VOLUME)
        val ITEMS = BaseKey(Dimension.COUNT)

        fun decode(s: String): BaseKey =
            when {
                s == "mass" -> MASS
                s == "volume" -> VOLUME
                s == "count" -> ITEMS
                s.startsWith("count:") ->
                    BaseKey(Dimension.COUNT, requireNotNull(QtyUnit.fromSerial(s.removePrefix("count:"))) { s })
                else -> throw IllegalArgumentException("bad base key $s")
            }
    }
}

/**
 * An amount in base units, per dimension (PLAN §3.3). A contribution stores
 * one of these, already scaled.
 *
 * An empty [parts] map is a *presence* amount: "salt, to taste" is on the
 * list but has no quantity to add up.
 */
@Serializable
data class QtyBase(
    val parts: Map<String, Rational> = emptyMap(),
) {
    val isPresence: Boolean get() = parts.isEmpty()

    operator fun get(key: BaseKey): Rational = parts[key.encode()] ?: Rational.ZERO

    operator fun plus(o: QtyBase): QtyBase = combine(o) { a, b -> a + b }

    /** Subtracts per slot, clamping each at zero; slots that reach zero are dropped. */
    fun minusClamped(o: QtyBase): QtyBase {
        val out = LinkedHashMap<String, Rational>()
        for ((k, v) in parts) {
            val r = v - (o.parts[k] ?: Rational.ZERO)
            if (r > Rational.ZERO) out[k] = r
        }
        return QtyBase(out)
    }

    fun times(factor: Rational): QtyBase = QtyBase(parts.mapValues { it.value * factor })

    val keys: List<BaseKey> get() = parts.keys.map(BaseKey::decode)

    private fun combine(
        o: QtyBase,
        f: (Rational, Rational) -> Rational,
    ): QtyBase {
        val out = LinkedHashMap(parts)
        for ((k, v) in o.parts) out[k] = f(out[k] ?: Rational.ZERO, v)
        return QtyBase(out.filterValues { !it.isZero })
    }

    companion object {
        val PRESENCE = QtyBase()

        /**
         * Converts a parsed amount to base units. Mass and volume go to g/ml;
         * count units keep their unit; no unit means whole items. A null
         * quantity is presence.
         */
        fun of(
            quantity: Rational?,
            unit: QtyUnit?,
            locale: UnitLocale,
        ): QtyBase {
            if (quantity == null || quantity <= Rational.ZERO) return PRESENCE
            if (unit == null) return QtyBase(mapOf(BaseKey.ITEMS.encode() to quantity))
            val factor = unit.baseFactor(locale)
            val key =
                when (unit.dimension) {
                    Dimension.MASS -> BaseKey.MASS
                    Dimension.VOLUME -> BaseKey.VOLUME
                    else -> BaseKey(Dimension.COUNT, unit)
                }
            return QtyBase(mapOf(key.encode() to if (factor == null) quantity else quantity * factor))
        }
    }
}
