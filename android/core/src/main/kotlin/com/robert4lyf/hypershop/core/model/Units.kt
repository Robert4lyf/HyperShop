package com.robert4lyf.hypershop.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** docs/parser-spec.md R2.3. */
enum class Dimension { MASS, VOLUME, COUNT }

/**
 * Which cup, pint and fluid ounce a recipe means (R2.2). `US` is the
 * default; `METRIC` for recipes in g/ml; `UK` for imperial UK sources.
 */
enum class UnitLocale { US, METRIC, UK }

/**
 * docs/parser-spec.md R2. [serial] is the lower-case name used by Claude's
 * schema and the golden JSON. [baseFactor] converts one unit into the base of
 * its dimension (g or ml) for a locale; count units have none.
 */
@Serializable
enum class QtyUnit(
    val serial: String,
    val dimension: Dimension?,
) {
    @SerialName("g")
    G("g", Dimension.MASS),

    @SerialName("kg")
    KG("kg", Dimension.MASS),

    @SerialName("oz")
    OZ("oz", Dimension.MASS),

    @SerialName("lb")
    LB("lb", Dimension.MASS),

    @SerialName("ml")
    ML("ml", Dimension.VOLUME),

    @SerialName("l")
    L("l", Dimension.VOLUME),

    @SerialName("tsp")
    TSP("tsp", Dimension.VOLUME),

    @SerialName("tbsp")
    TBSP("tbsp", Dimension.VOLUME),

    @SerialName("cup")
    CUP("cup", Dimension.VOLUME),

    @SerialName("fl_oz")
    FL_OZ("fl_oz", Dimension.VOLUME),

    @SerialName("pint")
    PINT("pint", Dimension.VOLUME),

    @SerialName("quart")
    QUART("quart", Dimension.VOLUME),

    @SerialName("clove")
    CLOVE("clove", Dimension.COUNT),

    @SerialName("bunch")
    BUNCH("bunch", Dimension.COUNT),

    @SerialName("tin")
    TIN("tin", Dimension.COUNT),

    @SerialName("packet")
    PACKET("packet", Dimension.COUNT),

    @SerialName("sprig")
    SPRIG("sprig", Dimension.COUNT),

    @SerialName("slice")
    SLICE("slice", Dimension.COUNT),

    @SerialName("head")
    HEAD("head", Dimension.COUNT),

    @SerialName("stalk")
    STALK("stalk", Dimension.COUNT),

    @SerialName("fillet")
    FILLET("fillet", Dimension.COUNT),

    @SerialName("piece")
    PIECE("piece", Dimension.COUNT),

    @SerialName("stick")
    STICK("stick", Dimension.COUNT),

    @SerialName("pinch")
    PINCH("pinch", Dimension.COUNT),

    @SerialName("dash")
    DASH("dash", Dimension.COUNT),

    /** A unit word that is none of the above (Claude only). Treated as a count. */
    @SerialName("other")
    OTHER("other", Dimension.COUNT),
    ;

    /** Base units (g or ml) in one of this unit, or null for count units. */
    fun baseFactor(locale: UnitLocale): Rational? =
        when (this) {
            G -> Rational.ONE
            KG -> Rational.of(1000)
            OZ -> Rational.of(2835, 100)
            LB -> Rational.of(4536, 10)
            ML -> Rational.ONE
            L -> Rational.of(1000)
            TSP -> Rational.of(5)
            TBSP -> Rational.of(15)
            CUP -> if (locale == UnitLocale.US) Rational.of(240) else Rational.of(250)
            FL_OZ -> if (locale == UnitLocale.UK) Rational.of(2841, 100) else Rational.of(2957, 100)
            PINT -> if (locale == UnitLocale.UK) Rational.of(568) else Rational.of(473)
            QUART -> if (locale == UnitLocale.UK) Rational.of(1137) else Rational.of(946)
            else -> null
        }

    companion object {
        private val bySerial = entries.associateBy { it.serial }

        fun fromSerial(s: String): QtyUnit? = bySerial[s]
    }
}

/** docs/parser-spec.md R9, in default store order. */
@Serializable
enum class AisleCode(
    val displayName: String,
) {
    PRODUCE("Produce"),
    BAKERY("Bakery"),
    MEAT_FISH("Meat & Fish"),
    DAIRY_EGGS("Dairy & Eggs"),
    CHILLED("Chilled"),
    FROZEN("Frozen"),
    TINS_JARS("Tins & Jars"),
    PASTA_RICE_GRAINS("Pasta/Rice/Grains"),
    WORLD_FOODS("World Foods"),
    BAKING("Baking"),
    NUTS_DRIED_FRUIT("Nuts & Dried Fruit"),
    OILS_VINEGARS("Oils & Vinegars"),
    SPICES_CONDIMENTS("Spices & Condiments"),
    FREE_FROM("Free-from"),
    DRINKS("Drinks"),
    HOUSEHOLD("Household"),
    OTHER("Other"),
}
