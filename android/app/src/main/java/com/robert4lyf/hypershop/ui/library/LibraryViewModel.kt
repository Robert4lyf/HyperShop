package com.robert4lyf.hypershop.ui.library

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robert4lyf.hypershop.catalog.EntryEdit
import com.robert4lyf.hypershop.catalog.SaveResult
import com.robert4lyf.hypershop.core.catalog.CatalogEntry
import com.robert4lyf.hypershop.core.catalog.CatalogKey
import com.robert4lyf.hypershop.core.catalog.DisplayPolicy
import com.robert4lyf.hypershop.core.merge.Display
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.ui.common.UiMessage
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
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
    /** "Also: scallion, green onion · 1 × 30g pack". */
    val detail: String?,
    /** You changed a bundled ingredient. */
    val edited: Boolean,
    /** You added it. */
    val custom: Boolean,
)

data class LibraryUi(
    val rows: List<LibraryRow> = emptyList(),
    val total: Int = 0,
    /** The catalogue has been read: before that, no count and no "Nothing matches". */
    val loaded: Boolean = false,
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
    /** Ingredient names, comma-separated. */
    val swaps: String = "",
    /** An ingredient name, or blank. */
    val replaceWith: String = "",
    /** Its group within the aisle ("Herbs"), or blank. */
    val group: String = "",
    val edited: Boolean = false,
    val custom: Boolean = false,
) {
    val isNew: Boolean get() = id.isBlank()
}

/** Settings › Ingredient library: browse, search, edit and add catalogue ingredients. */
class LibraryViewModel(
    private val c: AppContainer,
) : ViewModel() {
    /**
     * What is typed in the search box. Compose state, read straight back by the
     * field: were it the filtered [ui] (worked out off the main thread), each
     * keystroke would be overwritten by the previous text before it showed.
     */
    var query by mutableStateOf("")
        private set

    /** The aisle chip chosen above the list, or null for all. */
    var aisle by mutableStateOf<AisleCode?>(null)
        private set

    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = messages.receiveAsFlow()

    val ui: StateFlow<LibraryUi> =
        combine(c.library.edits, snapshotFlow { query }, snapshotFlow { aisle }) { edits, q, a ->
            val all = c.catalog.entries
            val key = CatalogKey.of(q)
            val rows =
                all
                    .asSequence()
                    .filter { e -> a == null || e.aisle == a }
                    .filter { e -> key.isEmpty() || (listOf(e.name, e.plural) + e.synonyms).any { CatalogKey.of(it).contains(key) } }
                    .sortedBy { it.name.lowercase() }
                    .map { e -> row(e, edited = e.id in edits && c.library.isBundled(e.id), custom = !c.library.isBundled(e.id)) }
                    .toList()
            LibraryUi(rows, all.size, loaded = true)
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), LibraryUi())

    fun search(text: String) {
        query = text
    }

    fun filterAisle(a: AisleCode?) {
        aisle = a
    }

    /**
     * Ingredient names starting with (then containing) what is being typed, for the
     * Swaps and "Always replace with" fields: up to five, none when it is typed in full.
     */
    fun suggest(typed: String): List<String> {
        val key = CatalogKey.of(typed)
        if (key.isEmpty()) return emptyList()
        val names = c.catalog.entries.asSequence().map { it.name }.filter { CatalogKey.of(it) != key }
        val starts = names.filter { CatalogKey.of(it).startsWith(key) }.sorted().take(SUGGESTIONS).toList()
        if (starts.size == SUGGESTIONS) return starts
        return starts + names.filter { it !in starts && CatalogKey.of(it).contains(key) }.sorted().take(SUGGESTIONS - starts.size)
    }

    private fun row(
        e: CatalogEntry,
        edited: Boolean,
        custom: Boolean,
    ): LibraryRow {
        val bits =
            listOfNotNull(
                e.synonyms.takeIf { it.isNotEmpty() }?.let { "Also: " + it.take(3).joinToString(", ") + if (it.size > 3) "…" else "" },
                e.packNote?.let(Display::compactUnits),
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
            swaps = e.swaps.mapNotNull { c.catalog.byId[it]?.name }.joinToString(", "),
            replaceWith = e.replaceWith?.let { c.catalog.byId[it]?.name }.orEmpty(),
            group = e.group.orEmpty(),
            edited = e.id in c.library.edits.value && c.library.isBundled(e.id),
            custom = !c.library.isBundled(e.id),
        )
    }

    /**
     * [save] for the edit sheet: the work runs in this scope, so a sheet dismissed
     * mid-save still leaves the list re-keyed; the sheet only awaits the answer.
     */
    fun saveFromSheet(f: EntryForm): Deferred<String?> = viewModelScope.async { save(f) }

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
        // Swaps name other ingredients: each must be in the library (add it first if not).
        val swapNames = f.swaps.split(',', '\n').map { it.trim() }.filter { it.isNotEmpty() }
        val swapIds = ArrayList<String>()
        for (n in swapNames) {
            val e = c.catalog.lookup(n) ?: return "No ingredient called \"$n\" — add it to the library first"
            if (e.id != f.id && e.id !in swapIds) swapIds += e.id
        }
        val replacement =
            f.replaceWith.trim().takeIf { it.isNotEmpty() }?.let { n ->
                c.catalog.lookup(n) ?: return "No ingredient called \"$n\" — add it to the library first"
            }
        if (replacement != null && replacement.id == f.id) return "It can't be used instead of itself"
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
                swaps = swapIds,
                replaceWith = replacement?.id,
                group = f.group.trim().take(30).takeIf { it.isNotEmpty() },
            )
        return when (val r = c.library.save(edit)) {
            SaveResult.NoName -> "Give it a name"
            is SaveResult.Taken -> "\"${r.name}\" is already ${r.by}"
            is SaveResult.Saved -> {
                apply(r.id, f.aisle.takeIf { before == null || before.aisle != it }, f.alwaysInStock)
                // "Always use instead": one already on the list is swapped now, too.
                replacement?.let { c.repository.swapKey("cat:${r.id}", it.name) }
                messages.send(UiMessage(if (before == null) "Added ${f.name.trim()}" else "Saved ${f.name.trim()}"))
                null
            }
        }
    }

    /** "Reset to default" for an edited ingredient, or "Delete" for one you added; Undo re-saves the edit. */
    fun reset(id: String) =
        viewModelScope.launch {
            val name = c.catalog.byId[id]?.name ?: return@launch
            val bundled = c.library.isBundled(id)
            val previous = c.library.edits.value[id]
            c.library.reset(id)
            c.repository.rekeyIfCatalogChanged()
            val undo: (suspend () -> Unit)? =
                previous?.let {
                    {
                        c.library.save(it)
                        c.repository.rekeyIfCatalogChanged()
                    }
                }
            messages.send(UiMessage(if (bundled) "Reset $name" else "Deleted $name", undo = undo))
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

    private companion object {
        const val SUGGESTIONS = 5
    }
}
