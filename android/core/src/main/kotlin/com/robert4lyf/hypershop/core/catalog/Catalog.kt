package com.robert4lyf.hypershop.core.catalog

import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.Rational
import java.text.Normalizer

/** How an item's amount is shown on the list (PLAN §3.3 rounding). */
enum class DisplayPolicy { COUNT, MASS, VOLUME, PRESENCE }

data class CatalogEntry(
    val id: String,
    val name: String,
    val plural: String,
    val synonyms: List<String>,
    val aisle: AisleCode,
    val staple: Boolean,
    val shoppingUnit: String,
    val displayPolicy: DisplayPolicy,
    /** Lets volume and mass combine (PLAN §3.3). Null for most items. */
    val gramsPerCup: Rational?,
    val packNote: String?,
    /** Ingredient ids it can be swapped for on the list ("agave syrup" → maple syrup, honey). */
    val swaps: List<String> = emptyList(),
    /** Always use this ingredient id instead: new recipe lines go straight to it. */
    val replaceWith: String? = null,
) {
    val mergeKey: String get() = "cat:$id"
}

/**
 * Swaps that ship with the app: within each group any ingredient can stand in
 * for another. Your edits in the ingredient library replace an ingredient's list.
 */
object DefaultSwaps {
    private val groups =
        listOf(
            listOf("agave_syrup", "maple_syrup", "honey", "golden_syrup"),
            listOf("caster_sugar", "brown_sugar", "demerara"),
            listOf("brown_sugar", "dark_brown_sugar"),
            listOf("double_cream", "single_cream", "creme_fraiche"),
            listOf("sour_cream", "creme_fraiche", "greek_yogurt"),
            listOf("yogurt", "greek_yogurt"),
            listOf("buttermilk", "yogurt", "milk"),
            listOf("milk", "oat_milk", "almond_milk", "soy_milk"),
            listOf("butter", "vegan_butter"),
            listOf("onion", "shallot", "red_onion"),
            listOf("spring_onion", "chives"),
            listOf("parsley", "coriander"),
            listOf("lemon", "lime"),
            listOf("lemon_juice", "lime_juice"),
            listOf("white_wine_vinegar", "cider_vinegar", "red_wine_vinegar", "sherry_vinegar", "rice_vinegar"),
            listOf("olive_oil", "vegetable_oil", "evoo"),
            listOf("parmesan", "pecorino"),
            listOf("cheddar", "gruyere", "emmental"),
            listOf("feta", "goat_cheese"),
            listOf("ricotta", "cream_cheese", "mascarpone"),
            listOf("cannellini_beans", "haricot_beans", "butter_beans"),
            listOf("kidney_beans", "black_beans"),
            listOf("breadcrumbs", "panko"),
            listOf("flour", "gf_flour"),
            listOf("pasta", "spaghetti", "penne", "gf_pasta"),
            listOf("rice", "basmati_rice", "jasmine_rice"),
            listOf("egg_noodles", "rice_noodles"),
            listOf("chicken_stock", "vegetable_stock", "beef_stock"),
            listOf("beef_mince", "pork_mince", "lamb_mince", "turkey_mince"),
            listOf("chicken_breast", "chicken_thigh"),
            listOf("bacon", "pancetta"),
            listOf("spinach", "kale", "chard"),
            listOf("sweet_potato", "butternut_squash"),
            listOf("paprika", "smoked_paprika"),
            listOf("chilli_flakes", "chilli_powder", "cayenne"),
            listOf("dijon", "wholegrain_mustard"),
            listOf("white_wine", "vermouth"),
        )

    /** Every id named in a group (a test checks they are all in the catalogue). */
    val ids: Set<String> = groups.flatten().toSet()

    fun forId(id: String): List<String> = groups.filter { id in it }.flatten().distinct().filter { it != id }
}

/**
 * The catalogue key of a name (spec R5.1): lower case, accents stripped,
 * straight quotes, anything but letters, digits, apostrophes, spaces and hyphens
 * dropped, whitespace collapsed. Must match `tools/catalog/norm.py`.
 */
object CatalogKey {
    private val nonKey = Regex("[^a-z0-9' -]")
    private val spaces = Regex("\\s+")
    private val marks = Regex("\\p{M}+")

    fun of(s: String): String {
        val folded = Normalizer.normalize(s.replace('’', '\''), Normalizer.Form.NFKD).replace(marks, "")
        val ascii = folded.filter { it.code < 128 }
        return ascii
            .lowercase()
            .replace(nonKey, " ")
            .replace(spaces, " ")
            .trim(' ', '-', '\'')
    }
}

/**
 * The catalogue with key lookups. Its contents can be swapped as a whole
 * ([replace]) when you edit the ingredient library; every reader sees either
 * the old set or the new one, never a mix.
 */
class Catalog(
    version: String,
    entries: List<CatalogEntry>,
) {
    private class Snapshot(
        val version: String,
        val entries: List<CatalogEntry>,
    ) {
        val byId: Map<String, CatalogEntry> = entries.associateBy { it.id }
        val byKey: Map<String, CatalogEntry> =
            buildMap {
                for (e in this@Snapshot.entries) {
                    for (n in listOf(e.name, e.plural) + e.synonyms) put(CatalogKey.of(n), e)
                }
            }
    }

    @Volatile private var s = Snapshot(version, entries)

    val version: String get() = s.version
    val entries: List<CatalogEntry> get() = s.entries
    val byId: Map<String, CatalogEntry> get() = s.byId

    /** The entry whose name, plural or synonym has this name's key. */
    fun lookup(name: String): CatalogEntry? = s.byKey[CatalogKey.of(name)]

    fun containsKey(key: String): Boolean = s.byKey.containsKey(key)

    /** Swaps in a new set of entries (the bundled ones with your edits applied). */
    fun replace(
        version: String,
        entries: List<CatalogEntry>,
    ) {
        s = Snapshot(version, entries)
    }

    /** An independent copy, so editing it leaves [CatalogLoader.default] alone. */
    fun copy(): Catalog = Catalog(version, entries)

    companion object {
        /** Parses the generated `catalog.tsv` (see tools/catalog/build_catalog.py). */
        fun parseTsv(text: String): Catalog {
            var version = ""
            val entries = mutableListOf<CatalogEntry>()
            for (line in text.lineSequence()) {
                if (line.isBlank()) continue
                if (line.startsWith("#version\t")) {
                    version = line.substringAfter('\t')
                    continue
                }
                if (line.startsWith("#")) continue
                val f = line.split('\t')
                require(f.size == 10) { "catalog.tsv: expected 10 fields, got ${f.size}: $line" }
                entries +=
                    CatalogEntry(
                        id = f[0],
                        name = f[1],
                        plural = f[2],
                        synonyms = f[3].split('|').filter { it.isNotEmpty() },
                        aisle = AisleCode.valueOf(f[4]),
                        staple = f[5].toBooleanStrict(),
                        shoppingUnit = f[6],
                        displayPolicy = DisplayPolicy.valueOf(f[7].uppercase()),
                        gramsPerCup = f[8].takeIf { it.isNotEmpty() }?.let { Rational.fromDouble(it.toDouble()) },
                        packNote = f[9].takeIf { it.isNotEmpty() },
                        swaps = DefaultSwaps.forId(f[0]),
                    )
            }
            require(version.isNotEmpty()) { "catalog.tsv: no #version line" }
            return Catalog(version, entries)
        }
    }
}

/** The bundled catalogue, parsed on first use. */
object CatalogLoader {
    val default: Catalog by lazy {
        val stream =
            requireNotNull(CatalogLoader::class.java.getResourceAsStream("/catalog/catalog.tsv")) {
                "catalog.tsv missing from resources"
            }
        Catalog.parseTsv(stream.bufferedReader(Charsets.UTF_8).use { it.readText() })
    }
}
