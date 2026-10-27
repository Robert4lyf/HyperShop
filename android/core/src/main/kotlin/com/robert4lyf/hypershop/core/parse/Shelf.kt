package com.robert4lyf.hypershop.core.parse

import com.robert4lyf.hypershop.core.catalog.Catalog
import com.robert4lyf.hypershop.core.catalog.CatalogEntry
import com.robert4lyf.hypershop.core.catalog.CatalogKey
import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.QtyUnit

/**
 * Which shelf a named thing is bought from, when the words round the name change it (Q.3): a tin of
 * something the catalogue knows fresh or dried is its tinned item ("2 tins tomatoes" are tinned tomatoes), and
 * minced or ground meat is that meat's mince. One rule for both readers, the rule parser and Claude's
 * answers (KNOWN_ISSUES #29), so a recipe is filed the same way whichever read it. Only to an entry the
 * catalogue has.
 */
class Shelf(
    private val catalog: Catalog = CatalogLoader.default,
) {
    /**
     * [name] as the shelf has it, given its [unit] and the words [said] about the name itself (its moved
     * qualifiers and its own brackets, never the rest of the line: "lamb, tossed with ground cumin" is not mince).
     */
    fun of(
        name: String,
        unit: QtyUnit?,
        said: Set<String>,
    ): String {
        val tinWord = said.firstOrNull { it in TIN_WORDS }
        val e = entryOf(name) ?: return if (tinWord != null) "$tinWord $name" else name
        if ((unit == QtyUnit.TIN || tinWord != null) && (e.aisle == AisleCode.PRODUCE || e.aisle == AisleCode.PASTA_RICE_GRAINS)) {
            // "canned crushed tomato": the catalogue's plural is what its tinned synonyms are made of. A tin of
            // something the catalogue knows dried ("1 tin beans", "a can of lentils") is its tinned item likewise.
            val forms = listOf(name, e.plural).distinct()
            forms
                .flatMap { listOf("tinned $it", "canned $it") }
                .firstOrNull { entryOf(it)?.aisle?.let { a -> a != AisleCode.PRODUCE } == true }
                ?.let { return it }
        }
        if (e.aisle == AisleCode.MEAT_FISH && said.any { it in MINCE_WORDS }) {
            "$name mince".takeIf { entryOf(it)?.aisle == AisleCode.MEAT_FISH }?.let { return it }
        }
        // "tinned salmon", "canned peaches": a tin of something the catalogue only knows fresh stays a tin,
        // its own item, rather than becoming the fresh one.
        if (tinWord != null && e.aisle != AisleCode.TINS_JARS) return "$tinWord $name"
        return name
    }

    /**
     * A name as Claude wrote it, with its words still on it ("canned tomatoes", "ground lamb") and its [unit].
     * A name the catalogue already files somewhere other than fresh produce is left as written; so is one this
     * rule cannot improve on, since Claude was asked for the name a UK shopper looks for.
     */
    fun ofWritten(
        name: String,
        unit: QtyUnit?,
    ): String {
        val words = name.split(' ').filter { it.isNotEmpty() }
        val lead = words.takeWhile { it in TIN_WORDS || it in MINCE_WORDS }
        if (lead.isEmpty() && unit != QtyUnit.TIN) return name
        if (lead.size == words.size) return name
        val written = entryOf(name)
        if (written != null && written.aisle != AisleCode.PRODUCE) return name
        val base = words.drop(lead.size).joinToString(" ")
        val shelved = of(base, unit, lead.toSet())
        return if (entryOf(shelved) != null && shelved != base) shelved else name
    }

    /** The catalogue's entry for [phrase], as written or with its last word singular. */
    fun entryOf(phrase: String): CatalogEntry? {
        val k = CatalogKey.of(phrase)
        if (k.isEmpty()) return null
        return catalog.lookup(k) ?: catalog.lookup(Normaliser.singularKey(k))
    }

    private companion object {
        val TIN_WORDS = setOf("canned", "tinned")
        val MINCE_WORDS = setOf("minced", "ground")
    }
}
