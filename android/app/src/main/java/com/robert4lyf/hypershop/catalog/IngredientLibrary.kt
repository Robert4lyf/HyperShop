package com.robert4lyf.hypershop.catalog

import com.robert4lyf.hypershop.core.catalog.Catalog
import com.robert4lyf.hypershop.core.catalog.CatalogEntry
import com.robert4lyf.hypershop.core.catalog.CatalogKey
import com.robert4lyf.hypershop.core.catalog.CatalogLoader
import com.robert4lyf.hypershop.core.catalog.DisplayPolicy
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.Rational
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.security.MessageDigest

/** One ingredient as you edited or added it: it replaces the bundled entry with the same id. */
@Serializable
data class EntryEdit(
    val id: String,
    val name: String,
    val plural: String,
    val synonyms: List<String> = emptyList(),
    val aisle: String,
    val shoppingUnit: String = "",
    val displayPolicy: String = DisplayPolicy.COUNT.name,
    val gramsPerCup: Double? = null,
    val packNote: String? = null,
    /** Added by you rather than an edit of a bundled ingredient. */
    val custom: Boolean = false,
) {
    fun toEntry(): CatalogEntry =
        CatalogEntry(
            id = id,
            name = name,
            plural = plural,
            synonyms = synonyms,
            aisle = runCatching { AisleCode.valueOf(aisle) }.getOrDefault(AisleCode.OTHER),
            staple = false,
            shoppingUnit = shoppingUnit,
            displayPolicy = runCatching { DisplayPolicy.valueOf(displayPolicy) }.getOrDefault(DisplayPolicy.COUNT),
            gramsPerCup = gramsPerCup?.let { Rational.fromDouble(it) },
            packNote = packNote?.takeIf { it.isNotBlank() },
        )

    companion object {
        fun of(
            e: CatalogEntry,
            custom: Boolean = false,
        ) = EntryEdit(
            e.id,
            e.name,
            e.plural,
            e.synonyms,
            e.aisle.name,
            e.shoppingUnit,
            e.displayPolicy.name,
            e.gramsPerCup?.toDouble(),
            e.packNote,
            custom,
        )
    }
}

/** Why an edit was refused. */
sealed interface SaveResult {
    data class Saved(
        val id: String,
    ) : SaveResult

    data object NoName : SaveResult

    /** A name or synonym already belongs to another ingredient. */
    data class Taken(
        val name: String,
        val by: String,
    ) : SaveResult
}

/**
 * Your edits to the ingredient catalogue: kept in a small JSON file (backed up
 * with the app's data) and applied on top of the bundled catalogue in [catalog],
 * which every parser and the list read. The file is read once, at start-up, before
 * anything uses the catalogue. The effective catalogue's version changes with the
 * edits, so the list's re-key (ListRepository.rekeyIfCatalogChanged) picks them up.
 */
class IngredientLibrary(
    private val file: File,
    private val catalog: Catalog,
    private val base: Catalog = CatalogLoader.default,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Mutex()
    private val _edits = MutableStateFlow(read())

    /** By id: edited bundled ingredients and the ones you added. */
    val edits: StateFlow<Map<String, EntryEdit>> = _edits

    init {
        apply(_edits.value)
    }

    fun isBundled(id: String): Boolean = base.byId.containsKey(id)

    /** The bundled version of an ingredient, for "Reset". */
    fun original(id: String): CatalogEntry? = base.byId[id]

    /** Saves [edit] (a new ingredient when its id is blank). */
    suspend fun save(edit: EntryEdit): SaveResult =
        lock.withLock {
            val name = edit.name.trim()
            if (name.isEmpty()) return@withLock SaveResult.NoName
            val id = edit.id.ifBlank { newId(name) }
            val custom = !isBundled(id)
            val clean =
                edit.copy(
                    id = id,
                    name = name,
                    plural = edit.plural.trim().ifEmpty { name },
                    synonyms = edit.synonyms.map { it.trim() }.filter { it.isNotEmpty() }.distinctBy { CatalogKey.of(it) },
                    shoppingUnit = edit.shoppingUnit.trim(),
                    packNote = edit.packNote?.trim()?.takeIf { it.isNotEmpty() },
                    custom = custom,
                )
            for (n in listOf(clean.name, clean.plural) + clean.synonyms) {
                val other = catalog.lookup(n)
                if (other != null && other.id != id) return@withLock SaveResult.Taken(n, other.name)
            }
            val next = _edits.value + (id to clean)
            write(next)
            SaveResult.Saved(id)
        }

    /** Back to the bundled version, or gone if you added it. */
    suspend fun reset(id: String) =
        lock.withLock {
            if (id !in _edits.value) return@withLock
            write(_edits.value - id)
        }

    private suspend fun write(next: Map<String, EntryEdit>) {
        withContext(Dispatchers.IO) {
            file.parentFile?.mkdirs()
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(json.encodeToString(ListSerializer(EntryEdit.serializer()), next.values.sortedBy { it.id }))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        }
        // The catalogue first: anything reacting to [edits] then reads the new entries.
        apply(next)
        _edits.value = next
    }

    private fun read(): Map<String, EntryEdit> =
        runCatching {
            if (!file.exists()) return emptyMap()
            json.decodeFromString(ListSerializer(EntryEdit.serializer()), file.readText()).associateBy { it.id }
        }.getOrDefault(emptyMap())

    private fun apply(edits: Map<String, EntryEdit>) {
        val entries = base.entries.map { edits[it.id]?.toEntry() ?: it } + edits.values.filter { !isBundled(it.id) }.map { it.toEntry() }
        catalog.replace(versionFor(edits), entries)
    }

    private fun versionFor(edits: Map<String, EntryEdit>): String {
        if (edits.isEmpty()) return base.version
        val text = json.encodeToString(ListSerializer(EntryEdit.serializer()), edits.values.sortedBy { it.id })
        val hash = MessageDigest.getInstance("SHA-256").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
        return "${base.version}+${hash.take(12)}"
    }

    private fun newId(name: String): String {
        val slug = CatalogKey.of(name).replace(Regex("[^a-z0-9]+"), "_").trim('_').ifEmpty { "item" }
        var id = "user_$slug"
        var n = 2
        while (catalog.byId.containsKey(id) || id in _edits.value) id = "user_${slug}_${n++}"
        return id
    }
}
