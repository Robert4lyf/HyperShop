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
) {
    val mergeKey: String get() = "cat:$id"
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
