package com.robert4lyf.hypershop.ui.meals

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robert4lyf.hypershop.core.merge.ApplyResult
import com.robert4lyf.hypershop.core.merge.UseProgress
import com.robert4lyf.hypershop.data.UseSummary
import com.robert4lyf.hypershop.data.bookRef
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.ui.common.UiMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** A recipe on the list: one of the week's meals. */
data class Meal(
    val useId: Long,
    val title: String,
    /** The use's servings, when the recipe says how many it serves. */
    val servings: Int?,
    /** Its shopping ([UseProgress]): what is still to buy, bought, or at home. */
    val progress: UseProgress,
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
    val itemsLeft: Int get() = meals.sumOf { it.progress.toBuy }
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

    /**
     * Servings asked for and not yet announced, by use: the stepper shows these at
     * once, so taps faster than the database count from the last tap, not from
     * whatever the list said a frame ago. Main thread only.
     */
    private val asked = MutableStateFlow<Map<Long, Int>>(emptyMap())

    /** What each burst of taps started from, for its one Undo. */
    private val burstFrom = HashMap<Long, Int>()
    private val announce = HashMap<Long, Job>()
    private val rescaling = Mutex()

    val ui: StateFlow<MealsUi> =
        combine(
            c.repository.list.map { c.repository.usesOnList(it) },
            c.repository.observeLibrary(),
            snapshotFlow { query },
            asked,
        ) { uses, lib, q, asked ->
            val meals =
                uses.filter { it.recipeId != null }.map {
                        u ->
                    u.meal().let { m -> asked[m.useId]?.let { m.copy(servings = it) } ?: m }
                }
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

    private fun UseSummary.meal() = Meal(useId, title, servings, progress, inStockCount, url?.let { siteOf(it) } ?: bookRef)

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
                null -> messages.send(UiMessage("${pick.title} couldn't be added; it may have just changed. Try again."))
            }
        }

    /** The stepper: one more or one fewer serving, counted from the last tap. */
    fun stepServings(
        meal: Meal,
        delta: Int,
    ) {
        val from = asked.value[meal.useId] ?: meal.servings ?: return
        setServings(meal, from + delta)
    }

    /**
     * The meal's items rescale on the list at once; a burst of taps is one
     * message with one Undo, sent once the taps stop, back to where it began.
     */
    fun setServings(
        meal: Meal,
        n: Int,
    ) {
        val start = asked.value[meal.useId] ?: meal.servings ?: return
        val target = n.coerceIn(1, MAX_SERVINGS)
        if (target == start) return
        burstFrom.putIfAbsent(meal.useId, start)
        asked.update { it + (meal.useId to target) }
        announce.remove(meal.useId)?.cancel()
        announce[meal.useId] =
            viewModelScope.launch {
                val ok = rescaling.withLock { c.repository.rescaleUse(meal.useId, target) }
                if (!ok) {
                    settle(meal.useId)
                    messages.send(UiMessage("Couldn't change ${meal.title}: it is no longer on the list"))
                    return@launch
                }
                delay(SETTLE_MS)
                val was = settle(meal.useId) ?: return@launch
                if (was == target) return@launch
                messages.send(
                    UiMessage(
                        "${meal.title} serves $target",
                        undo = { rescaling.withLock { c.repository.rescaleUse(meal.useId, was) } },
                    ),
                )
            }
    }

    /** The burst is over: the list now shows what was asked. Returns where it began. */
    private fun settle(useId: Long): Int? {
        asked.update { it - useId }
        announce.remove(useId)
        return burstFrom.remove(useId)
    }

    /** "Remove": the meal's items come off the list; the saved recipe stays. Undo puts them back. */
    fun remove(meal: Meal) =
        viewModelScope.launch {
            // A servings change still settling is not announced over the removal, and lands first.
            settle(meal.useId)
            val removed = rescaling.withLock { c.repository.removeUse(meal.useId) } ?: return@launch
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

    companion object {
        const val MAX_SERVINGS = 99

        /** How long the stepper waits for another tap before saying what changed. */
        const val SETTLE_MS = 1_500L
    }
}
