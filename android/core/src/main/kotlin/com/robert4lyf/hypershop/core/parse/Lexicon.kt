package com.robert4lyf.hypershop.core.parse

import com.robert4lyf.hypershop.core.model.QtyUnit
import com.robert4lyf.hypershop.core.model.Rational

/** Word lists from docs/parser-spec.md. Everything is matched lower case except R2.1's T/t. */
internal object Lexicon {
    /** R2 spellings (single words). Multi-word units are handled in the grammar. */
    val units: Map<String, QtyUnit> =
        buildMap {
            fun u(
                unit: QtyUnit,
                vararg words: String,
            ) = words.forEach { put(it, unit) }
            u(QtyUnit.G, "g", "gram", "grams", "gr", "gm", "gms")
            u(QtyUnit.KG, "kg", "kgs", "kilo", "kilos", "kilogram", "kilograms")
            u(QtyUnit.OZ, "oz", "ounce", "ounces")
            u(QtyUnit.LB, "lb", "lbs", "pound", "pounds")
            u(QtyUnit.ML, "ml", "millilitre", "millilitres", "milliliter", "milliliters", "mls")
            u(QtyUnit.L, "l", "litre", "litres", "liter", "liters", "ltr")
            u(QtyUnit.TSP, "tsp", "tsps", "teaspoon", "teaspoons", "teaspoonful", "teaspoonfuls")
            u(QtyUnit.TBSP, "tbsp", "tbsps", "tbs", "tbl", "tbls", "tablespoon", "tablespoons", "tablespoonful", "tablespoonfuls")
            u(QtyUnit.CUP, "cup", "cups", "c")
            u(QtyUnit.PINT, "pint", "pints", "pt", "pts")
            u(QtyUnit.QUART, "quart", "quarts", "qt", "qts")
            u(QtyUnit.CLOVE, "clove", "cloves")
            u(QtyUnit.BUNCH, "bunch", "bunches")
            u(QtyUnit.TIN, "tin", "tins", "can", "cans")
            u(QtyUnit.PACKET, "packet", "packets", "package", "packages", "pack", "packs", "sachet", "sachets", "envelope", "envelopes")
            u(QtyUnit.SPRIG, "sprig", "sprigs")
            u(QtyUnit.SLICE, "slice", "slices")
            u(QtyUnit.HEAD, "head", "heads")
            u(QtyUnit.STALK, "stalk", "stalks")
            u(QtyUnit.FILLET, "fillet", "fillets")
            u(QtyUnit.PIECE, "piece", "pieces")
            u(QtyUnit.STICK, "stick", "sticks")
            u(QtyUnit.PINCH, "pinch", "pinches")
            u(QtyUnit.DASH, "dash", "dashes")
        }

    /** R2.4: unit words that are not a [QtyUnit]; the rule parser reads them as `other`. */
    val otherUnits =
        setOf(
            "ear", "ears", "leaf", "leaves", "bag", "bags", "strip", "strips", "rectangle", "rectangles",
            "handful", "handfuls", "bottle", "bottles", "jar", "jars", "sheet", "sheets", "drop", "drops",
            "loaf", "loaves", "bulb", "bulbs", "splash", "splashes", "cube", "cubes", "square", "squares",
            "wedge", "wedges", "stem", "stems", "branch", "branches", "pod", "pods", "punnet", "punnets",
            "knob", "knobs", "rasher", "rashers", "box", "boxes", "carton", "cartons", "tub", "tubs",
            "pot", "pots", "block", "blocks", "bar", "bars", "ball", "balls", "slab", "slabs", "tray", "trays",
            "drizzle", "glug", "squeeze", "dollop", "sprinkle", "sprinkling", "scattering",
        )

    /** A length before a piece ("5cm piece of ginger", "2 inch stick"): a size, never a count. */
    val lengths = setOf("cm", "mm", "inch", "inches")

    /** "2 dl water": decilitres, read as 100 ml each. */
    val decilitres = setOf("dl", "decilitre", "decilitres", "deciliter", "deciliters")

    /**
     * Words that end the name: what follows describes it ("parsley for
     * garnish", "starter from above", "saffron crumbled into water").
     */
    val nameEnders = setOf("for", "to", "from", "with", "into", "in", "plus", "such", "at", "if", "as")

    /** Count units that may follow the name instead ("2 garlic cloves"). */
    val trailingUnits = setOf(QtyUnit.CLOVE, QtyUnit.SPRIG, QtyUnit.STALK, QtyUnit.SLICE, QtyUnit.FILLET, QtyUnit.HEAD, QtyUnit.BUNCH)

    /** Container units that take an alternative size before them ("400g tin"). */
    val containers = setOf(QtyUnit.TIN, QtyUnit.PACKET)

    val numberWords: Map<String, Rational> =
        mapOf(
            "a" to Rational.ONE,
            "an" to Rational.ONE,
            "one" to Rational.of(1),
            "two" to Rational.of(2),
            "three" to Rational.of(3),
            "four" to Rational.of(4),
            "five" to Rational.of(5),
            "six" to Rational.of(6),
            "seven" to Rational.of(7),
            "eight" to Rational.of(8),
            "nine" to Rational.of(9),
            "ten" to Rational.of(10),
            "eleven" to Rational.of(11),
            "twelve" to Rational.of(12),
            "half" to Rational.of(1, 2),
            "dozen" to Rational.of(12),
        )

    /** R4.1 prep words: participles and their adverbs. */
    val prep =
        setOf(
            "chopped", "diced", "minced", "sliced", "grated", "shredded", "crushed", "sifted", "peeled",
            "cored", "seeded", "deseeded", "pitted", "halved", "quartered", "cubed", "trimmed", "rinsed",
            "drained", "beaten", "whisked", "melted", "softened", "room-temperature", "cooked", "thawed",
            "defrosted", "julienned", "torn", "squeezed", "zested", "finely", "roughly", "coarsely",
            "thinly", "lightly", "freshly", "divided", "snipped", "slivered", "smashed", "mashed", "picked",
            "washed", "scrubbed", "stemmed", "hulled", "shelled", "skinned", "boned", "cleaned", "toasted",
            "roasted", "blanched", "soaked", "warmed", "chilled", "cold", "warm", "very", "well", "crumbled",
            "packed", "firmly", "loosely", "pressed", "cut", "split", "broken", "shaved", "scored", "pounded",
            "bruised", "bashed", "deveined", "butterflied", "spatchcocked", "deboned", "scaled", "gutted",
            "shucked", "flattened", "sieved", "strained", "reserved", "separated", "removed", "discarded",
        )

    /** R4.1 sizes and qualifiers. */
    val qualifiers =
        setOf(
            "small", "medium", "large", "extra-large", "medium-size", "medium-sized", "large-size", "big",
            "whole", "good-size", "good-sized", "miniature", "mini", "baby-size",
            "thick", "thin", "fresh", "about", "approximately", "approx", "heaped", "heaping", "level",
            "scant", "good", "good-quality", "high-quality", "best-quality", "generous", "ripe", "few",
            "fat", "rounded", "plump", "juicy", "thumb-sized", "thumb-size", "each", "organic",
            // The shelf, not the product: "canned crushed tomatoes" are crushed tomatoes (RuleParser.shelf).
            "canned", "tinned",
        )

    /**
     * R4.2 identity words that look like prep: they stay in the name when they
     * come straight before the noun of a known product ("toasted sesame oil",
     * "roasted red peppers"). Checked against the catalogue by the normaliser.
     */
    val identityOverrides = setOf("toasted", "roasted", "smoked", "dried", "ground", "cooked", "whole")
}
