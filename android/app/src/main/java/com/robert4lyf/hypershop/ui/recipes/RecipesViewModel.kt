package com.robert4lyf.hypershop.ui.recipes

import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.search.RecipeSearch
import com.robert4lyf.hypershop.data.UseSummary
import com.robert4lyf.hypershop.data.bookRef
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.importing.ImportInput
import com.robert4lyf.hypershop.importing.ImportResult
import com.robert4lyf.hypershop.importing.PhotoStore
import com.robert4lyf.hypershop.ui.common.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecipesUi(
    val uses: List<UseSummary> = emptyList(),
    val loaded: Boolean = false,
    /** Every recipe you've added, the one added longest ago first; when searching, the matches, best first. */
    val library: List<LibraryRow> = emptyList(),
    /** The library has recipes (the search box shows even when nothing matches). */
    val hasLibrary: Boolean = library.isNotEmpty(),
    /** What is being searched for (chips, then the text being typed); empty when not searching. */
    val terms: List<String> = emptyList(),
    /** The book of the cookbook recipe added most recently: the photo form starts with it. */
    val lastBook: String? = null,
)

/** A saved recipe opened from "Your recipes": its ingredients, under their headings. */
data class RecipeDetail(
    val row: LibraryRow,
    val servings: Int?,
    /** Heading (null for none) and its lines as written, in recipe order. */
    val sections: List<Pair<String?, List<String>>>,
)

/** One recipe in "Your recipes". */
data class LibraryRow(
    val recipeId: Long,
    val key: String,
    val title: String,
    val url: String?,
    val lines: Int,
    val lastAddedAt: Long,
    /** A use of it is on the list now. */
    val onList: Boolean,
    /** Searching: the ingredients that matched ("chestnut mushroom, spinach"). */
    val uses: String? = null,
    /** "Ottolenghi Simple · p. 142" for a cookbook recipe. */
    val bookRef: String? = null,
)

/** The Recipes tab (PLAN §3.5): the uses on the list. */
class RecipesViewModel(
    private val c: AppContainer,
) : ViewModel() {
    /** The search box's text: Compose state, read straight back by the field. */
    var searchText by mutableStateOf("")
        private set

    /** Terms already turned into chips. */
    private val chips = MutableStateFlow<List<String>>(emptyList())

    val searchChips: StateFlow<List<String>> = chips

    private val terms =
        combine(chips, snapshotFlow { searchText }) { cs, t -> cs + listOfNotNull(t.trim().takeIf { it.isNotEmpty() }) }

    /** What the search index was built from: the library's recipes, and the catalogue (its synonyms are in it). */
    private data class IndexKey(
        val recipeIds: List<Long>,
        val catalogVersion: String,
    )

    /** The search index, rebuilt when the library's recipes or the catalogue change. */
    private var index: Pair<IndexKey, List<RecipeSearch.Recipe>>? = null

    /** From the shared list read; only while the tab is shown (and 5 s after, across a rotation). */
    val ui: StateFlow<RecipesUi> =
        combine(c.repository.list.map { c.repository.usesOnList(it) }, c.repository.observeLibrary(), terms) { uses, lib, q ->
            val onList = uses.mapNotNull { it.libraryKey }.toSet()
            val rows =
                lib.map {
                    LibraryRow(
                        it.recipeId,
                        it.key,
                        it.title,
                        it.url,
                        it.lines,
                        it.lastAddedAt,
                        it.key in onList,
                        bookRef = bookRef(it.book, it.page),
                    )
                }
            val shown =
                if (q.isEmpty()) {
                    rows
                } else {
                    val byId = rows.associateBy { it.recipeId }
                    RecipeSearch.search(indexFor(lib), q).mapNotNull { h ->
                        byId[h.id]?.copy(uses = h.uses.takeIf { it.isNotEmpty() }?.joinToString(", "))
                    }
                }
            val lastBook = lib.filter { it.book != null }.maxByOrNull { it.lastAddedAt }?.book
            RecipesUi(uses, loaded = true, library = shown, hasLibrary = rows.isNotEmpty(), terms = q, lastBook = lastBook)
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), RecipesUi())

    private suspend fun indexFor(lib: List<com.robert4lyf.hypershop.data.LibraryRecipe>): List<RecipeSearch.Recipe> {
        val key = IndexKey(lib.map { it.recipeId }, c.catalog.version)
        index?.takeIf { it.first == key }?.let { return it.second }
        val lines = c.repository.ingredientsOf(key.recipeIds)
        val built =
            lib.map { r ->
                RecipeSearch.Recipe(
                    r.recipeId,
                    // The book's name too: "Ottolenghi" finds everything from it.
                    listOfNotNull(r.title, r.book).joinToString(" "),
                    lines[r.recipeId].orEmpty().map { p ->
                        val entry = c.repository.target(p.name).catalogId?.let { c.catalog.byId[it] }
                        RecipeSearch.Ingredient(
                            entry?.name ?: p.name,
                            listOf(p.name) + listOfNotNull(entry?.name, entry?.plural) + entry?.synonyms.orEmpty(),
                        )
                    },
                )
            }
        index = key to built
        return built
    }

    /** Typing in the search box; a comma ends a term, as Enter does. */
    fun onSearchText(text: String) {
        if (',' in text) {
            text.split(',').dropLast(1).forEach { addChip(it) }
            searchText = text.substringAfterLast(',')
        } else {
            searchText = text
        }
    }

    /** Enter: what was typed becomes a chip, so another can be added ("mushrooms" + "spinach"). */
    fun commitSearch() {
        addChip(searchText)
        searchText = ""
    }

    private fun addChip(t: String) {
        val term = t.trim()
        if (term.isNotEmpty() && chips.value.none { it.equals(term, ignoreCase = true) }) chips.value = chips.value + term
    }

    fun removeChip(term: String) {
        chips.value = chips.value - term
    }

    fun clearSearch() {
        chips.value = emptyList()
        searchText = ""
    }

    /**
     * "Add to list" in Your recipes: the recipe on the review screen as it was saved,
     * at its own servings, staples ticked as usual. It then becomes the newest.
     */
    fun addFromLibrary(row: LibraryRow) =
        viewModelScope.launch {
            val (r, lines) = c.repository.librarySource(row.recipeId) ?: return@launch
            if (lines.isEmpty()) return@launch
            val result =
                ImportResult(
                    url = r.url,
                    title = r.title,
                    servings = r.servingsOriginal,
                    locale = UnitLocale.valueOf(r.unitLocale),
                    method = "LIBRARY",
                    lines = lines,
                    parsedBy = runCatching { ParsedBy.valueOf(r.parsedBy) }.getOrDefault(ParsedBy.RULES),
                    model = r.model,
                    rawLines = lines.map { it.raw },
                    libraryKey = row.key,
                    book = r.book,
                    page = r.page,
                )
            reviews.send(c.importJobs.enqueueReady(ImportInput(r.url ?: "", r.title), result))
        }

    private val _detail = MutableStateFlow<RecipeDetail?>(null)

    /** The saved recipe being looked at, or null. */
    val detail: StateFlow<RecipeDetail?> = _detail

    /** Tap on a saved recipe: its ingredients. */
    fun openDetail(row: LibraryRow) =
        viewModelScope.launch {
            val (r, lines) = c.repository.librarySource(row.recipeId) ?: return@launch
            val sections = ArrayList<Pair<String?, MutableList<String>>>()
            for (l in lines) {
                if (sections.isEmpty() || sections.last().first != l.section) sections += l.section to mutableListOf()
                sections.last().second += l.raw
            }
            _detail.value = RecipeDetail(row, r.servingsOriginal, sections)
        }

    fun closeDetail() {
        _detail.value = null
    }

    /** "Remove from your recipes" (what's on the list stays); Undo brings it back. */
    fun removeFromLibrary(row: LibraryRow) =
        viewModelScope.launch {
            val copies = c.repository.removeFromLibrary(row.key)
            messages.send(UiMessage("Removed ${row.title} from your recipes", undo = { c.repository.restoreToLibrary(copies) }))
        }

    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = messages.receiveAsFlow()

    private val reviews = Channel<Long>(Channel.BUFFERED)

    /** Job ids to open on the review screen. */
    val openReview: Flow<Long> = reviews.receiveAsFlow()

    /** "Remove from list"; Undo puts the use and its items back as they were. */
    fun remove(use: UseSummary) =
        viewModelScope.launch {
            val removed = c.repository.removeUse(use.useId) ?: return@launch
            messages.send(
                UiMessage(
                    "Removed ${use.title} from the list",
                    undo = {
                        // The recipe copy can go meanwhile (the start-up purge); the use can't come back without it.
                        if (!c.repository.restoreUse(removed)) messages.send(UiMessage("Can't undo: the recipe is gone"))
                    },
                ),
            )
        }

    /**
     * "Edit": the recipe back on the review screen as it went on the list (its lines,
     * servings and in-stock rows). Saving replaces this use; leaving changes nothing.
     */
    fun edit(use: UseSummary) =
        viewModelScope.launch {
            if (use.recipeId == null) {
                editByHand(use)
                return@launch
            }
            val src = c.repository.editSource(use.useId) ?: return@launch
            val r = src.recipe
            val result =
                ImportResult(
                    url = r.url,
                    title = r.title,
                    servings = r.servingsOriginal,
                    targetServings = src.servings.takeIf { r.servingsOriginal != null },
                    locale = UnitLocale.valueOf(r.unitLocale),
                    method = "EDIT",
                    lines = src.lines,
                    parsedBy = runCatching { ParsedBy.valueOf(r.parsedBy) }.getOrDefault(ParsedBy.RULES),
                    model = r.model,
                    rawLines = src.lines.map { it.raw },
                    preTick = false,
                    editsUse = use.useId,
                    inStockLines = src.inStockLines,
                    libraryKey = r.libraryKey,
                    book = r.book,
                    page = r.page,
                )
            reviews.send(c.importJobs.enqueueReady(ImportInput(r.url ?: "", r.title), result))
        }

    /** "Edit" on "Added by hand": its items as lines on the review screen; saving puts them back by hand. */
    private suspend fun editByHand(use: UseSummary) {
        val raw = c.repository.manualLines(use.useId)?.takeIf { it.isNotEmpty() } ?: return
        val result =
            ImportResult(
                url = null,
                title = use.title,
                servings = null,
                locale = UnitLocale.METRIC,
                method = ImportResult.BY_HAND,
                lines = c.ruleParser.parseLines(raw),
                rawLines = raw,
                preTick = false,
                editsUse = use.useId,
            )
        reviews.send(c.importJobs.enqueueReady(ImportInput("", use.title), result))
    }

    /**
     * "+ › Photo of a recipe": the photo is saved, scaled down, and reviewed like a
     * shared page; the review screen asks Claude to read it once it is open.
     */
    fun addPhoto(
        uri: Uri,
        book: String = "",
        page: String = "",
    ) = viewModelScope.launch {
        val file = PhotoStore.save(c.context, uri)
        if (file == null) {
            messages.send(UiMessage("Couldn't open that photo"))
            return@launch
        }
        val result =
            ImportResult(
                url = null,
                title = null,
                servings = null,
                locale = UnitLocale.METRIC,
                method = "PHOTO",
                claudePending = true,
                photo = file.path,
                book = book.trim().ifEmpty { null },
                page = page.trim().ifEmpty { null },
            )
        reviews.send(c.importJobs.enqueueReady(ImportInput("", "Photo of a recipe"), result))
    }

    /** "+ › Paste a link": the same path as sharing a page to the app (the review screen opens when it is ready). */
    fun addLink(text: String) {
        c.applicationScope.launch { c.importShared(text, null) }
    }

    /**
     * "+ › Type it in": the lines are rule-parsed here, never sent to Claude; the
     * review screen calls Claude (with a key) once it is open, as for a shared page.
     */
    fun addManual(
        title: String,
        servings: Int?,
        ingredients: String,
    ) = viewModelScope.launch {
        val lines = ingredients.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val name = title.trim()
        if (lines.isEmpty() || name.isEmpty()) return@launch
        val parsed = c.importer.fromLines(lines, name, allowClaude = false, model = c.settings.model.first())
        reviews.send(c.importJobs.enqueueReady(ImportInput("", name), parsed.copy(servings = servings)))
    }
}
