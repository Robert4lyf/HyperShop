package com.robert4lyf.hypershop.core.parse

import com.robert4lyf.hypershop.core.catalog.Catalog
import com.robert4lyf.hypershop.core.catalog.CatalogEntry
import com.robert4lyf.hypershop.core.catalog.CatalogKey
import com.robert4lyf.hypershop.core.catalog.CatalogLoader

/**
 * Names to identities (spec R5), shared by the rule parser and Claude's
 * post-processing so both produce the same `mergeKey` for the same thing.
 */
class Normaliser(
    private val catalog: Catalog = CatalogLoader.default,
) {
    /** The catalogue entry for a name: as written, or with its head noun singular (R5.4). */
    fun entry(name: String): CatalogEntry? {
        val k = CatalogKey.of(name)
        if (k.isEmpty()) return null
        catalog.lookup(k)?.let { return it }
        val singular = singularKey(k)
        if (singular != k) catalog.lookup(singular)?.let { return it }
        return cheese(k)
    }

    /**
     * "Mascarpone cheese", "Parmigiano Reggiano PDO cheese": the word "cheese" and a
     * protected-origin mark after a cheese the catalogue knows are the same cheese. Only
     * for the catalogue's cheeses, so "cream cheese" never becomes cream.
     */
    private fun cheese(k: String): CatalogEntry? {
        val words = k.split(' ').filter { it !in ORIGIN_MARKS }
        val bare = (if (words.size > 1 && words.last() == "cheese") words.dropLast(1) else words).joinToString(" ")
        if (bare == k || bare.isEmpty()) return null
        return (catalog.lookup(bare) ?: catalog.lookup(singularKey(bare)))?.takeIf { it.group == CHEESE }
    }

    /** R5.1 + R5.4: the normalised name; the singular only when the catalogue has it. */
    fun normName(name: String): String {
        val k = CatalogKey.of(name)
        val e = entry(name) ?: return k
        val s = singularKey(k)
        return if (s != k && CatalogKey.of(e.name) == s || CatalogKey.of(e.plural) == k) CatalogKey.of(e.name) else k
    }

    /**
     * R5.5. A blank name (a line the parser could not read) is keyed on its [raw]
     * line in parentheses, which no real name's key contains, so unreadable lines
     * do not all merge into one item.
     */
    fun mergeKey(
        name: String,
        raw: String = name,
    ): String {
        entry(name)?.let { return it.mergeKey }
        val k = CatalogKey.of(name)
        if (k.isNotEmpty()) return "name:$k"
        return "name:(${CatalogKey.of(raw).ifEmpty { raw.trim() }})"
    }

    /** R11 name comparison key: normalised, folded through the catalogue, head noun singular. */
    fun compareKey(name: String): String = entry(name)?.mergeKey ?: "name:${singularKey(CatalogKey.of(name))}"

    companion object {
        private const val CHEESE = "Cheese"

        /** Protected designations of origin, printed after a cheese's name ("PDO", Italian "DOP"). */
        private val ORIGIN_MARKS = setOf("pdo", "dop", "aop", "aoc", "pgi", "igp")

        /** "-ves" plurals whose singular ends in f/fe; every other "-ves" just drops the s (cloves, olives, chives). */
        /** U.3: "-ies" words whose singular is not "-y": "chillies" were "chilly". */
        private val IE_PLURALS =
            mapOf(
                "chillies" to "chilli",
                "chilies" to "chili",
                "cookies" to "cookie",
                "brownies" to "brownie",
                "veggies" to "veggie",
                "beanies" to "beanie",
                "smoothies" to "smoothie",
            )

        private val F_PLURALS =
            mapOf(
                "leaves" to "leaf",
                "loaves" to "loaf",
                "halves" to "half",
                "calves" to "calf",
                "shelves" to "shelf",
                "wolves" to "wolf",
                "knives" to "knife",
                "wives" to "wife",
                "scarves" to "scarf",
            )

        /** Singularises the last word of a key (R5.4); mirrors tools/catalog/top_names.py. */
        fun singularKey(key: String): String {
            val words = key.split(' ')
            val last = words.last()
            val s =
                when {
                    last in IE_PLURALS -> IE_PLURALS.getValue(last)
                    last.length > 4 && last.endsWith("ies") -> last.dropLast(3) + "y"
                    last.endsWith("oes") -> last.dropLast(2)
                    last in F_PLURALS -> F_PLURALS.getValue(last)
                    last.endsWith("ches") || last.endsWith("shes") || last.endsWith("sses") || last.endsWith("xes") -> last.dropLast(2)
                    last.endsWith("s") && !last.endsWith("ss") && !last.endsWith("us") && !last.endsWith("is") -> last.dropLast(1)
                    else -> last
                }
            return (words.dropLast(1) + s).joinToString(" ")
        }
    }
}
