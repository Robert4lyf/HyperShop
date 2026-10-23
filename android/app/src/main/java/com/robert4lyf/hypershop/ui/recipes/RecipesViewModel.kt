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
import com.robert4lyf.hypershop.importing.LineIdentity
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

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
    /** Each saved recipe's photo, by library key: a card on the list shows its recipe's. */
    val photos: Map<String, String> = emptyMap(),
    /** Each saved recipe's cooking notes, by library key: shown with it wherever it is on the list. */
    val notes: Map<String, String> = emptyMap(),
    /** How "Saved" is ordered (while not searching: then it is best match first). */
    val sort: LibrarySort = LibrarySort.RECENT,
    /** Only 4★ and 5★ recipes. */
    val highlyRated: Boolean = false,
)

/** A saved recipe opened from "Your recipes": its ingredients, under their headings. */
data class RecipeDetail(
    val row: LibraryRow,
    val servings: Int?,
    /** Heading (null for none) and its lines as written, in recipe order. */
    val sections: List<Pair<String?, List<String>>>,
)

/** A recipe on the list, opened from its card: its lines as written, the in-stock ones marked. */
data class UseDetail(
    val use: UseSummary,
    /** What the recipe is written for, when the list holds it at a different size. */
    val writtenFor: Int?,
    /** Heading (null for none) and its lines, each with whether it was left out as in stock. */
    val sections: List<Pair<String?, List<Pair<String, Boolean>>>>,
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
    /** Your stars out of 5, or null. */
    val rating: Int? = null,
    /** Your cooking notes ("10 min longer, half the chilli"), or null. */
    val notes: String? = null,
    /** The recipe's photo on this phone, or null. */
    val photo: String? = null,
    /** How many meals it has been planned as. */
    val timesPlanned: Int = 0,
) {
    val facts get() = RecipeFacts(key, title, lastAddedAt, rating, timesPlanned)
}

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

    /** The chips and the term being typed, and whether there is one being typed (it matches word starts). */
    private val terms =
        combine(chips, snapshotFlow { searchText }) { cs, t ->
            val typed = t.trim().takeIf { it.isNotEmpty() }
            (cs + listOfNotNull(typed)) to (typed != null)
        }

    /** What the search index was built from: the library's recipes, and the catalogue (its synonyms are in it). */
    private data class IndexKey(
        val recipeIds: List<Long>,
        val catalogVersion: String,
    )

    private val order = MutableStateFlow(LibrarySort.RECENT to false)

    /** "Sort": the order of "Saved". Only the screen's: nothing stored or planned changes. */
    fun sortBy(sort: LibrarySort) = order.update { it.copy(first = sort) }

    /** "4★ & up" on or off. */
    fun showHighlyRated(on: Boolean) = order.update { it.copy(second = on) }

    /** The search index, rebuilt when the library's recipes or the catalogue change. */
    private var index: Pair<IndexKey, List<RecipeSearch.Recipe>>? = null

    init {
        // Photos known but not on this phone (after a restore onto another one, or an offer made offline):
        // fetched once, quietly, when the recipes are first shown.
        viewModelScope.launch {
            val missing =
                c.repository.observeRecipeNotes().first().values.filter { it.photoUrl != null && c.recipePhotos.file(it.photo) == null }
            if (missing.isNotEmpty()) c.applicationScope.launch { c.recipePhotos.fill(missing) }
        }
    }

    /** From the shared list read; only while the tab is shown (and 5 s after, across a rotation). */
    val ui: StateFlow<RecipesUi> =
        combine(
            c.repository.list.map { c.repository.usesOnList(it) },
            combine(c.repository.observeLibrary(), c.repository.observeTimesPlanned()) { l, t -> l to t },
            c.repository.observeRecipeNotes(),
            c.recipePhotos.paths(),
            combine(terms, order) { t, o -> t to o },
        ) { uses, (lib, planned), notes, photos, (search, view) ->
            val (q, typing) = search
            val (sort, highly) = view
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
                        rating = notes[it.key]?.rating,
                        notes = notes[it.key]?.notes,
                        photo = photos[it.key],
                        timesPlanned = planned[it.key] ?: 0,
                    )
                }
            val shown =
                if (q.isEmpty()) {
                    LibraryOrder.sort(rows, sort) { it.facts }
                } else {
                    // Searching: best match first, whatever the sort.
                    val byId = rows.associateBy { it.recipeId }
                    RecipeSearch.search(indexFor(lib), q, typing).mapNotNull { h ->
                        byId[h.id]?.copy(uses = h.uses.takeIf { it.isNotEmpty() }?.joinToString(", "))
                    }
                }.filter { !highly || LibraryOrder.highlyRated(it.rating) }
            val lastBook = lib.filter { it.book != null }.maxByOrNull { it.lastAddedAt }?.book
            RecipesUi(
                uses,
                loaded = true,
                library = shown,
                hasLibrary = rows.isNotEmpty(),
                terms = q,
                lastBook = lastBook,
                photos = photos,
                notes = notes.mapNotNull { (k, n) -> n.notes?.let { k to it } }.toMap(),
                sort = sort,
                highlyRated = highly,
            )
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
                        val entry = c.repository.catalogEntry(p.name)
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
    fun addFromLibrary(row: LibraryRow) = openSaved(row, ImportResult.LIBRARY)

    /** "Edit" on a saved recipe: the review screen, saving back to the library and leaving the list alone. */
    fun editSaved(row: LibraryRow) = openSaved(row, ImportResult.LIBRARY_EDIT)

    private fun openSaved(
        row: LibraryRow,
        method: String,
    ) = viewModelScope.launch {
        val (r, stored) = c.repository.librarySource(row.recipeId) ?: return@launch
        if (stored.isEmpty()) return@launch
        val (lines, raw) = LineIdentity.reread(stored)
        val result =
            ImportResult(
                url = r.url,
                title = r.title,
                servings = r.servingsOriginal,
                locale = UnitLocale.valueOf(r.unitLocale),
                method = method,
                lines = lines,
                // An edit adds nothing, so no "already on your list?" and nothing ticked as a staple.
                preTick = method != ImportResult.LIBRARY_EDIT,
                parsedBy = runCatching { ParsedBy.valueOf(r.parsedBy) }.getOrDefault(ParsedBy.RULES),
                model = r.model,
                rawLines = raw,
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

    /** Stars out of 5; the same star again takes the rating away. */
    fun rate(
        row: LibraryRow,
        stars: Int,
    ) = viewModelScope.launch { c.repository.setRating(row.key, stars.takeIf { it != row.rating }) }

    /** Cooking notes for the recipe; blank clears them. */
    fun saveNotes(
        row: LibraryRow,
        notes: String,
    ) = c.applicationScope.launch { c.repository.setCookingNotes(row.key, notes) }

    /** A photo chosen from the phone's photos for the recipe. */
    fun choosePhoto(
        row: LibraryRow,
        uri: Uri,
    ) = c.applicationScope.launch {
        if (!c.recipePhotos.pick(row.key, uri)) messages.send(UiMessage("Couldn't use that photo"))
    }

    /** The recipe page's own photo, for a recipe saved before photos were kept (or after one was removed). */
    fun pagePhoto(row: LibraryRow) =
        c.applicationScope.launch {
            val url = row.url ?: return@launch
            if (!c.recipePhotos.fromPage(row.key, url)) messages.send(UiMessage("Couldn't get a photo from the recipe's page"))
        }

    fun removePhoto(row: LibraryRow) = c.applicationScope.launch { c.recipePhotos.remove(row.key) }

    fun closeDetail() {
        _detail.value = null
    }

    private val _useDetail = MutableStateFlow<UseDetail?>(null)

    /** The recipe on the list whose ingredients are open, or null. */
    val useDetail: StateFlow<UseDetail?> = _useDetail

    /** A card tapped: the recipe's lines, or the hand-added items, in a sheet. */
    fun openUse(use: UseSummary) =
        viewModelScope.launch {
            if (use.recipeId == null) {
                val lines = c.repository.manualLines(use.useId) ?: return@launch
                _useDetail.value = UseDetail(use, null, listOf(null to lines.map { it to false }))
                return@launch
            }
            val src = c.repository.editSource(use.useId) ?: return@launch
            val inStock = src.inStockLines.toSet()
            val sections = ArrayList<Pair<String?, MutableList<Pair<String, Boolean>>>>()
            src.lines.forEachIndexed { i, l ->
                if (sections.isEmpty() || sections.last().first != l.section) sections += l.section to mutableListOf()
                sections.last().second += l.raw to (i in inStock)
            }
            val written = src.recipe.servingsOriginal?.takeIf { it != src.servings }
            _useDetail.value = UseDetail(use, written, sections)
        }

    fun closeUse() {
        _useDetail.value = null
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

    /**
     * "Remove from list"; Undo puts back what it took. What was bought stays bought:
     * then only what is still needed comes off, and the bought rows stay on the list.
     */
    fun remove(use: UseSummary) =
        viewModelScope.launch {
            val removed = c.repository.removeFromList(use.useId) ?: return@launch
            messages.send(
                UiMessage(
                    "Removed ${use.title} from the list",
                    undo = {
                        // The recipe copy can go meanwhile (the start-up purge); the use can't come back without it.
                        if (!c.repository.undoRemoveFromList(removed)) messages.send(UiMessage("Can't undo: the list has changed since"))
                    },
                ),
            )
        }

    /** The pencil on a card: the recipe everywhere it shows, or "Added by hand" itself. */
    fun rename(
        use: UseSummary,
        title: String,
    ) = viewModelScope.launch { c.repository.renameUse(use.useId, title) }

    /** "Rename" on a saved recipe, and any copy of it on the list. */
    fun renameSaved(
        row: LibraryRow,
        title: String,
    ) = viewModelScope.launch { c.repository.renameLibrary(row.key, title) }

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
            val (lines, raw) = LineIdentity.reread(src.lines)
            val result =
                ImportResult(
                    url = r.url,
                    title = r.title,
                    servings = r.servingsOriginal,
                    targetServings = src.servings.takeIf { r.servingsOriginal != null },
                    locale = UnitLocale.valueOf(r.unitLocale),
                    method = "EDIT",
                    lines = lines,
                    parsedBy = runCatching { ParsedBy.valueOf(r.parsedBy) }.getOrDefault(ParsedBy.RULES),
                    model = r.model,
                    rawLines = raw,
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
        // The camera's own copy (a photo of someone's cookbook) is not left in the cache.
        File(File(c.context.cacheDir, "camera"), "shot.jpg").delete()
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
