package com.robert4lyf.hypershop.ui.meals

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robert4lyf.hypershop.core.merge.ApplyResult
import com.robert4lyf.hypershop.data.UseSummary
import com.robert4lyf.hypershop.data.bookRef
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.ui.common.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A recipe on the list: one of the week's meals. */
data class Meal(
    val useId: Long,
    val title: String,
    /** The use's servings, when the recipe says how many it serves. */
    val servings: Int?,
    /** What is still on the list for it (0 once everything is bought). */
    val itemsLeft: Int,
    val atHome: Int,
    /** "Ottolenghi Simple · p. 142" or the site. */
    val source: String?,
)

/** A saved recipe that could be a meal. */
data class Pick(
    val recipeId: Long,
    val key: String,
    val title: String,
    val lines: Int,
    /** Already one of the week's meals. */
    val onList: Boolean,
    val source: String?,
)

data class MealsUi(
    val loaded: Boolean = false,
    val meals: List<Meal> = emptyList(),
    /** Every saved recipe, the one added longest ago first; filtered by [query] when set. */
    val picks: List<Pick> = emptyList(),
    val hasLibrary: Boolean = false,
    val query: String = "",
) {
    val itemsLeft: Int get() = meals.sumOf { it.itemsLeft }
}

/**
 * "Meals this week" (docs/design/simplify.md phase 7): the recipes on the list
 * *are* the week's meals, so there is no plan to keep in step with the list.
 * Picking a saved recipe puts it straight on the list as the review would with
 * nothing changed (staples at home); servings rescale the use; removing a meal
 * is "Remove from list". Everything goes through the repository's existing
 * operations, and every change has Undo.
 */
class MealsViewModel(
    private val c: AppContainer,
) : ViewModel() {
    var query by mutableStateOf("")
        private set

    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = messages.receiveAsFlow()

    /** Adds run one at a time: two taps on two recipes land in order, each on a list that holds the other. */
    private val adding = Mutex()

    val ui: StateFlow<MealsUi> =
        combine(
            c.repository.list.map { c.repository.usesOnList(it) },
            c.repository.observeLibrary(),
            snapshotFlow { query },
        ) { uses, lib, q ->
            val meals = uses.filter { it.recipeId != null }.map { it.meal() }
            val onList = uses.mapNotNull { it.libraryKey }.toSet()
            val term = q.trim().lowercase()
            val picks =
                lib
                    .map {
                            r ->
                        Pick(r.recipeId, r.key, r.title, r.lines, r.key in onList, r.url?.let { siteOf(it) } ?: bookRef(r.book, r.page))
                    }
                    .filter { term.isEmpty() || it.title.lowercase().contains(term) }
            MealsUi(loaded = true, meals = meals, picks = picks, hasLibrary = lib.isNotEmpty(), query = q)
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MealsUi())

    private fun UseSummary.meal() = Meal(useId, title, servings, itemCount, inStockCount, url?.let { siteOf(it) } ?: bookRef)

    private fun siteOf(url: String): String? =
        com.robert4lyf.hypershop.ui.common.webLink(url)?.let {
            com.robert4lyf.hypershop.ui.common.siteName(it)
        }

    fun search(text: String) {
        query = text
    }

    /** A saved recipe becomes a meal: on the list now, with Undo. */
    fun add(pick: Pick) =
        viewModelScope.launch {
            val r = adding.withLock { c.repository.addSavedRecipe(pick.recipeId) }
            when (r) {
                is ApplyResult.Applied ->
                    messages.send(
                        UiMessage(
                            "Added ${pick.title}" + if (r.added == 0) " (everything at home)" else " · ${plural(r.added, "item")}",
                            undo = { c.repository.undoImport(r.useId) },
                        ),
                    )
                ApplyResult.Stale -> messages.send(UiMessage("The list changed; try ${pick.title} again"))
                null -> messages.send(UiMessage("${pick.title} has no ingredients to add"))
            }
        }

    /** The stepper: the meal's items rescale on the list. */
    fun setServings(
        meal: Meal,
        n: Int,
    ) = viewModelScope.launch { if (n in 1..99) c.repository.rescaleUse(meal.useId, n) }

    /** "Remove": the meal's items come off the list; the saved recipe stays. Undo puts them back. */
    fun remove(meal: Meal) =
        viewModelScope.launch {
            val removed = c.repository.removeUse(meal.useId) ?: return@launch
            messages.send(
                UiMessage(
                    "Removed ${meal.title}",
                    undo = { if (!c.repository.restoreUse(removed)) messages.send(UiMessage("Can't undo: the recipe is gone")) },
                ),
            )
        }

    private fun plural(
        n: Int,
        word: String,
    ) = if (n == 1) "1 $word" else "$n ${word}s"
}
