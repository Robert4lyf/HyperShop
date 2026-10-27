package com.robert4lyf.hypershop.ui.recipes

import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.data.UseSummary
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.importing.ImportInput
import com.robert4lyf.hypershop.importing.ImportResult
import com.robert4lyf.hypershop.importing.LineIdentity
import com.robert4lyf.hypershop.ui.common.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * What can be done to a recipe on the shopping list ("Added by hand" too) from any screen that shows one: its
 * ingredients in a sheet, Edit (the review screen, replacing this use), Rename, Remove from list. One place for
 * it (cut 1 of the review of 2026-10-27): the Shopping tab's recipe view has these on its cards, and the Recipes
 * tab is the recipe book alone.
 */
class UseActions(
    private val c: AppContainer,
    private val scope: CoroutineScope,
    private val messages: Channel<UiMessage>,
    /** Job ids to open on the review screen. */
    private val reviews: Channel<Long>,
) {
    private val _detail = MutableStateFlow<UseDetail?>(null)

    /** The recipe on the list whose ingredients are open, or null. */
    val detail: StateFlow<UseDetail?> = _detail

    /** A card tapped: the recipe's lines, or the hand-added items, in a sheet. */
    fun open(use: UseSummary) =
        scope.launch {
            if (use.recipeId == null) {
                val lines = c.repository.manualLines(use.useId) ?: return@launch
                _detail.value = UseDetail(use, null, listOf(null to lines.map { it to false }))
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
            _detail.value = UseDetail(use, written, sections)
        }

    fun closeDetail() {
        _detail.value = null
    }

    /**
     * "Remove from list"; Undo puts back what it took. What was bought stays bought:
     * then only what is still needed comes off, and the bought rows stay on the list.
     */
    fun remove(use: UseSummary) =
        scope.launch {
            val removed = c.repository.removeFromList(use.useId) ?: return@launch
            messages.send(
                UiMessage(
                    "Removed ${use.title} from the shopping list",
                    undo = {
                        // The recipe copy can go meanwhile (the start-up purge); the use can't come back without it.
                        if (!c.repository.undoRemoveFromList(removed)) {
                            messages.send(UiMessage("Can't undo: the shopping list has changed since"))
                        }
                    },
                ),
            )
        }

    /** "Rename": the recipe everywhere it shows, or "Added by hand" itself. */
    fun rename(
        use: UseSummary,
        title: String,
    ) = scope.launch { c.repository.renameUse(use.useId, title) }

    /**
     * "Edit": the recipe back on the review screen as it went on the list (its lines,
     * servings and in-stock rows). Saving replaces this use; leaving changes nothing.
     */
    fun edit(use: UseSummary) =
        scope.launch {
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
}
