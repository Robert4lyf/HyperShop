package com.robert4lyf.hypershop.ui.library

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robert4lyf.hypershop.catalog.EntryEdit
import com.robert4lyf.hypershop.catalog.SaveResult
import com.robert4lyf.hypershop.core.catalog.CatalogEntry
import com.robert4lyf.hypershop.core.catalog.CatalogKey
import com.robert4lyf.hypershop.core.catalog.DisplayPolicy
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.ui.common.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** One ingredient in the library list. */
data class LibraryRow(
    val id: String,
    val name: String,
    val aisle: AisleCode,
    /** "Also: scallion, green onion · 1 × 30 g pack". */
    val detail: String?,
    /** You changed a bundled ingredient. */
    val edited: Boolean,
    /** You added it. */
    val custom: Boolean,
)

data class LibraryUi(
    val rows: List<LibraryRow> = emptyList(),
    val total: Int = 0,
    val query: String = "",
)

/** What the edit sheet shows and saves. */
data class EntryForm(
    /** Blank for a new ingredient. */
    val id: String = "",
    val name: String = "",
    val plural: String = "",
    /** Comma-separated. */
    val synonyms: String = "",
    val aisle: AisleCode = AisleCode.OTHER,
    val policy: DisplayPolicy = DisplayPolicy.COUNT,
    val packNote: String = "",
    val gramsPerCup: String = "",
    val alwaysInStock: Boolean = false,
    val edited: Boolean = false,
    val custom: Boolean = false,
) {
    val isNew: Boolean get() = id.isBlank()
}

/** Settings › Ingredient library: browse, search, edit and add catalogue ingredients. */
class LibraryViewModel(
    private val c: AppContainer,
) : ViewModel() {
    private val query = MutableStateFlow("")
    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = messages.receiveAsFlow()

    val ui: StateFlow<LibraryUi> =
        combine(c.library.edits, query) { edits, q ->
            val all = c.catalog.entries
            val key = CatalogKey.of(q)
            val rows =
                all
                    .asSequence()
                    .filter { e -> key.isEmpty() || (listOf(e.name, e.plural) + e.synonyms).any { CatalogKey.of(it).contains(key) } }
                    .sortedBy { it.name.lowercase() }
                    .map { e -> row(e, edited = e.id in edits && c.library.isBundled(e.id), custom = !c.library.isBundled(e.id)) }
                    .toList()
            LibraryUi(rows, all.size, q)
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUi())

    fun search(text: String) {
        query.value = text
    }

    private fun row(
        e: CatalogEntry,
        edited: Boolean,
        custom: Boolean,
    ): LibraryRow {
        val bits =
            listOfNotNull(
                e.synonyms.takeIf { it.isNotEmpty() }?.let { "Also: " + it.take(3).joinToString(", ") + if (it.size > 3) "…" else "" },
                e.packNote?.let { "1 × $it" },
            )
        return LibraryRow(
            e.id,
            e.name.replaceFirstChar { it.uppercase() },
            e.aisle,
            bits.joinToString(" · ").ifEmpty { null },
            edited,
            custom,
        )
    }

    /** The form for an existing ingredient, or a blank one for "Add ingredient". */
    suspend fun form(id: String?): EntryForm {
        val e = id?.let { c.catalog.byId[it] } ?: return EntryForm()
        val staple = c.repository.staples().contains(e.mergeKey)
        return EntryForm(
            id = e.id,
            name = e.name,
            plural = e.plural,
            synonyms = e.synonyms.joinToString(", "),
            aisle = e.aisle,
            policy = e.displayPolicy,
            packNote = e.packNote.orEmpty(),
            gramsPerCup = e.gramsPerCup?.toDouble()?.let { if (it % 1.0 == 0.0) it.toInt().toString() else it.toString() }.orEmpty(),
            alwaysInStock = staple,
            edited = e.id in c.library.edits.value && c.library.isBundled(e.id),
            custom = !c.library.isBundled(e.id),
        )
    }

    /**
     * Saves the form, then brings the list in line: items whose names now match
     * merge (the re-key), every row redraws with the new units, an item already on
     * the list moves to the new aisle, and "always in stock" follows the switch.
     * Null when saved, else why not.
     */
    suspend fun save(f: EntryForm): String? {
        val grams = f.gramsPerCup.trim().replace(',', '.').takeIf { it.isNotEmpty() }?.toDoubleOrNull()
        if (f.gramsPerCup.isNotBlank() && (grams == null || grams <= 0 || grams > 2000)) return "Grams per cup must be a number up to 2000"
        val before = f.id.takeIf { it.isNotBlank() }?.let { c.catalog.byId[it] }
        val edit =
            EntryEdit(
                id = f.id,
                name = f.name,
                plural = f.plural,
                synonyms = f.synonyms.split(',', '\n'),
                aisle = f.aisle.name,
                shoppingUnit = before?.shoppingUnit.orEmpty(),
                displayPolicy = f.policy.name,
                gramsPerCup = grams,
                packNote = f.packNote,
            )
        return when (val r = c.library.save(edit)) {
            SaveResult.NoName -> "Give it a name"
            is SaveResult.Taken -> "\"${r.name}\" is already ${r.by}"
            is SaveResult.Saved -> {
                apply(r.id, f.aisle.takeIf { before == null || before.aisle != it }, f.alwaysInStock)
                messages.send(UiMessage(if (before == null) "Added ${f.name.trim()}" else "Saved ${f.name.trim()}"))
                null
            }
        }
    }

    /** "Reset to default" for an edited ingredient, or "Delete" for one you added. */
    fun reset(id: String) =
        viewModelScope.launch {
            val name = c.catalog.byId[id]?.name ?: return@launch
            val bundled = c.library.isBundled(id)
            c.library.reset(id)
            c.repository.rekeyIfCatalogChanged()
            messages.send(UiMessage(if (bundled) "Reset $name" else "Deleted $name"))
        }

    private suspend fun apply(
        id: String,
        movedTo: AisleCode?,
        staple: Boolean,
    ) {
        val key = "cat:$id"
        c.repository.rekeyIfCatalogChanged()
        if (movedTo != null) {
            // Future imports land there too, whatever an older "always" said.
            c.repository.setOverride(key, movedTo)
            c.repository.snapshot().itemByKey(key)?.let { c.repository.moveAisle(it.id, movedTo, always = false) }
        }
        c.repository.setStaple(key, staple)
    }
}
