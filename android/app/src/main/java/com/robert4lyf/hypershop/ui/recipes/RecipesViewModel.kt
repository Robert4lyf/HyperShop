package com.robert4lyf.hypershop.ui.recipes

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.data.UseSummary
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.importing.ImportInput
import com.robert4lyf.hypershop.importing.ImportResult
import com.robert4lyf.hypershop.ui.common.UiMessage
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class RecipesUi(
    val uses: List<UseSummary> = emptyList(),
    val loaded: Boolean = false,
)

/** The Recipes tab (PLAN §3.5): the uses on the list. */
class RecipesViewModel(
    private val c: AppContainer,
) : ViewModel() {
    val ui: StateFlow<RecipesUi> =
        c.repository.observe()
            .map { RecipesUi(c.repository.usesOnList(), loaded = true) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, RecipesUi())

    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = messages.receiveAsFlow()

    private val reviews = Channel<Long>(Channel.BUFFERED)

    /** Job ids to open on the review screen. */
    val openReview: Flow<Long> = reviews.receiveAsFlow()

    fun remove(use: UseSummary) =
        viewModelScope.launch {
            c.repository.removeUse(use.useId)
            messages.send(UiMessage("Removed ${use.title} from the list"))
        }

    /** "Add the in-stock items now": the review screen again, full amounts, nothing pre-ticked. */
    fun addInStockNow(use: UseSummary) =
        viewModelScope.launch {
            val (recipe, lines) = c.repository.inStockLines(use.useId) ?: return@launch
            if (lines.isEmpty()) return@launch
            val result =
                ImportResult(
                    url = recipe.url,
                    title = recipe.title,
                    servings = recipe.servingsOriginal,
                    targetServings = use.servings,
                    locale = UnitLocale.valueOf(recipe.unitLocale),
                    method = "IN_STOCK",
                    lines = lines,
                    rawLines = lines.map { it.raw },
                    preTick = false,
                )
            reviews.send(c.importJobs.enqueueReady(ImportInput(recipe.url ?: "", recipe.title), result))
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
