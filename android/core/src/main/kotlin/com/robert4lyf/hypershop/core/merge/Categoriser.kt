package com.robert4lyf.hypershop.core.merge

import com.robert4lyf.hypershop.core.catalog.Catalog
import com.robert4lyf.hypershop.core.catalog.CatalogKey
import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import com.robert4lyf.hypershop.core.catalog.DisplayPolicy
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.parse.Normaliser

/** Where an aisle came from, for the item sheet and for tests. */
enum class AisleSource { OVERRIDE, IDENTITY, CATALOGUE, CLAUDE, HEAD_NOUN, KEYWORD, OTHER }

/**
 * Picks an item's aisle (PLAN §3.4), first match wins:
 * 1. your override (`CategoryOverride`, keyed by mergeKey)
 * 2. catalogue id of the whole name ("dried pasta", "canned pumpkin")
 * 3. identity words ("frozen …", "tinned …", "dried mango")
 * 4. Claude's `aisle_suggestion`
 * 5. head noun ("heirloom tomato" → tomato)
 * 5a. Step AB: a few unmistakable non-food phrases ("dishwasher salt", "sun cream") to Household
 * 6. keyword rules (Step AB: non-food single words last, so they only ever turn an Other into Household)
 * 7. Other
 */
class Categoriser(
    private val catalog: Catalog = CatalogLoader.default,
    private val normaliser: Normaliser = Normaliser(catalog),
) {
    fun aisle(
        name: String,
        overrides: Map<String, AisleCode> = emptyMap(),
        claude: AisleCode? = null,
    ): Pair<AisleCode, AisleSource> {
        val key = CatalogKey.of(name)
        overrides[normaliser.mergeKey(name)]?.let { return it to AisleSource.OVERRIDE }
        normaliser.entry(name)?.let { return it.aisle to AisleSource.CATALOGUE }
        identity(key)?.let { return it to AisleSource.IDENTITY }
        if (claude != null && claude != AisleCode.OTHER) return claude to AisleSource.CLAUDE
        // Step AB: before the head noun, which would read "dishwasher salt" as salt and "sun cream" as cream.
        if (householdPhrase(key)) return AisleCode.HOUSEHOLD to AisleSource.KEYWORD
        headNoun(key)?.let { return it to AisleSource.HEAD_NOUN }
        keyword(key)?.let { return it to AisleSource.KEYWORD }
        return AisleCode.OTHER to AisleSource.OTHER
    }

    private fun identity(key: String): AisleCode? {
        val words = key.split(' ')
        if (words.size < 2) return null
        val first = words.first()
        val rest = words.drop(1).joinToString(" ")
        return when (first) {
            "frozen" -> AisleCode.FROZEN
            "tinned", "canned" -> AisleCode.TINS_JARS
            // Dried herbs are spices; dried fruit is dried fruit; dried pulses sit with
            // the dried beans; anything else ("dried egg noodles") keeps its own aisle.
            "dried" -> {
                val fresh = normaliser.entry(rest)
                when {
                    fresh == null -> null
                    fresh.aisle != AisleCode.PRODUCE ->
                        if (fresh.aisle == AisleCode.TINS_JARS) AisleCode.PASTA_RICE_GRAINS else fresh.aisle
                    fresh.displayPolicy == DisplayPolicy.PRESENCE -> AisleCode.SPICES_CONDIMENTS
                    else -> AisleCode.NUTS_DRIED_FRUIT
                }
            }
            "ground" -> if (normaliser.entry(rest)?.aisle == AisleCode.SPICES_CONDIMENTS) AisleCode.SPICES_CONDIMENTS else null
            else -> null
        }
    }

    /** "washing powder", "tea towel", "dog biscuits": a food word inside them would win later, and none is food. */
    private fun householdPhrase(key: String): Boolean {
        val joined = " " + key.split(' ', '-').filter { it.isNotEmpty() }.joinToString(" ") { Normaliser.singularKey(it) } + " "
        return HOUSEHOLD_PHRASES.any { " $it " in joined }
    }

    /** The longest catalogued suffix of the name: "heirloom cherry tomatoes" → cherry tomato. */
    private fun headNoun(key: String): AisleCode? {
        val words = key.split(' ')
        for (start in 1 until words.size) {
            val tail = words.subList(start, words.size).joinToString(" ")
            normaliser.entry(tail)?.let { return it.aisle }
        }
        return null
    }

    private fun keyword(key: String): AisleCode? {
        val words = key.split(' ', '-').map { Normaliser.singularKey(it) }.toSet()
        for ((aisle, keys) in KEYWORDS) if (keys.any { it in words }) return aisle
        if (HOUSEHOLD_WORDS.any { it in words }) return AisleCode.HOUSEHOLD
        return null
    }

    internal companion object {
        /**
         * Step AB: non-food phrases a food keyword would otherwise catch ("powder", "salt", "tea", "cream", "biscuit",
         * "fish"), matched as whole words, singular. Nothing here can name a food.
         */
        val HOUSEHOLD_PHRASES: List<String> =
            listOf(
                "washing powder", "washing liquid", "washing tablet", "washing up", "laundry", "dishwasher",
                "fabric softener", "fabric conditioner", "toilet", "bin bag", "bin liner", "freezer bag", "sandwich bag",
                "food bag", "paper towel", "tea towel", "kitchen towel", "light bulb", "dog food", "dog biscuit",
                "dog treat", "cat food", "cat litter", "cat treat", "pet food", "bird seed", "fish food", "sun cream",
                "hand cream", "face cream", "body lotion", "hand wash", "body wash", "shower gel", "hand soap",
                "oven cleaner", "rubber glove", "cotton bud", "cotton wool",
            ).map { p -> p.split(' ').joinToString(" ") { Normaliser.singularKey(it) } }

        /**
         * Step AB: words that only name non-food things, checked after every food keyword. Left out on purpose: "bulb"
         * (fennel bulb), "sponge" (sponge cake), "polish" (Polish sausage), "dog" (hot dog), "spray" (cooking spray),
         * "paper" (rice paper), "liquid" (liquid smoke), "bag" (tea bags).
         */
        val HOUSEHOLD_WORDS: Set<String> =
            setOf(
                "detergent", "bleach", "soap", "shampoo", "conditioner", "toothpaste", "toothbrush", "floss", "mouthwash",
                "deodorant", "antiperspirant", "razor", "battery", "tissue", "nappy", "wipe", "cleaner", "disinfectant",
                "descaler", "limescale", "litter", "sunscreen", "moisturiser", "moisturizer", "tampon", "sanitary",
                "plaster", "napkin", "candle", "glove", "bin", "hoover", "softener",
            ).map { Normaliser.singularKey(it) }.toSet()

        val KEYWORDS: List<Pair<AisleCode, Set<String>>> =
            listOf(
                AisleCode.FROZEN to setOf("ice"),
                AisleCode.MEAT_FISH to
                    setOf(
                        "chicken", "beef", "pork", "lamb", "veal", "turkey", "duck", "fillet", "steak", "mince", "fish",
                        "salmon", "tuna", "cod", "prawn", "shrimp", "crab", "lobster", "sausage", "venison", "rabbit", "goose",
                        "chop", "loin", "brisket", "rib", "shank", "trout", "halibut", "monkfish", "seabass", "sardine",
                    ),
                AisleCode.DAIRY_EGGS to setOf("cheese", "milk", "cream", "yogurt", "yoghurt", "butter", "egg", "creme"),
                AisleCode.BAKERY to
                    setOf("bread", "roll", "bun", "loaf", "baguette", "bagel", "muffin", "croissant", "crumpet", "tortilla"),
                AisleCode.OILS_VINEGARS to setOf("oil", "vinegar"),
                AisleCode.SPICES_CONDIMENTS to
                    setOf(
                        "sauce", "spice", "seasoning", "mustard", "ketchup", "relish", "powder", "salt", "pepper", "paste", "rub",
                        "dressing",
                    ),
                AisleCode.BAKING to
                    setOf("flour", "sugar", "chocolate", "cocoa", "extract", "essence", "yeast", "icing", "syrup", "cookie", "biscuit"),
                AisleCode.PASTA_RICE_GRAINS to setOf("pasta", "rice", "noodle", "grain", "oat", "barley", "spaghetti", "macaroni"),
                AisleCode.TINS_JARS to setOf("stock", "broth", "bean", "jam", "pickle", "olive", "tomato"),
                AisleCode.NUTS_DRIED_FRUIT to setOf("nut", "seed", "almond", "raisin", "sultana"),
                AisleCode.DRINKS to setOf("wine", "juice", "beer", "liqueur", "spirit", "soda", "water", "tea", "coffee"),
                AisleCode.PRODUCE to
                    setOf(
                        "lettuce", "berry", "mushroom", "pepper", "squash", "leaf", "herb", "greens", "chilli", "chili", "apple",
                        "onion", "potato", "carrot", "cabbage", "melon", "orange", "lemon", "lime", "fruit", "vegetable",
                    ),
            )
    }
}
