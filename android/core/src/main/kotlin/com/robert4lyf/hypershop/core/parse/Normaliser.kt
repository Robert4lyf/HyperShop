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
        return null
    }

    /** R5.1 + R5.4: the normalised name; the singular only when the catalogue has it. */
    fun normName(name: String): String {
        val k = CatalogKey.of(name)
        val e = entry(name) ?: return k
        val s = singularKey(k)
        return if (s != k && CatalogKey.of(e.name) == s || CatalogKey.of(e.plural) == k) CatalogKey.of(e.name) else k
    }

    /** R5.5. */
    fun mergeKey(name: String): String = entry(name)?.mergeKey ?: "name:${CatalogKey.of(name)}"

    /** R11 name comparison key: normalised, folded through the catalogue, head noun singular. */
    fun compareKey(name: String): String = entry(name)?.mergeKey ?: "name:${singularKey(CatalogKey.of(name))}"

    companion object {
        /** Singularises the last word of a key (R5.4); mirrors tools/catalog/top_names.py. */
        fun singularKey(key: String): String {
            val words = key.split(' ')
            val last = words.last()
            val s =
                when {
                    last.length > 4 && last.endsWith("ies") -> last.dropLast(3) + "y"
                    last.endsWith("oes") -> last.dropLast(2)
                    last.endsWith("ves") && last.length > 4 -> last.dropLast(3) + "f"
                    last.endsWith("ches") || last.endsWith("shes") || last.endsWith("sses") || last.endsWith("xes") -> last.dropLast(2)
                    last.endsWith("s") && !last.endsWith("ss") && !last.endsWith("us") && !last.endsWith("is") -> last.dropLast(1)
                    else -> last
                }
            return (words.dropLast(1) + s).joinToString(" ")
        }
    }
}
