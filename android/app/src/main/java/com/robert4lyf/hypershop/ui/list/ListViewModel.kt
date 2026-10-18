package com.robert4lyf.hypershop.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robert4lyf.hypershop.core.merge.Display
import com.robert4lyf.hypershop.core.merge.ShoppingList
import com.robert4lyf.hypershop.core.merge.UnitsSetting
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.ui.common.UiMessage
import com.robert4lyf.hypershop.ui.common.webLink
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class TickState { OPEN, PARTIAL, DONE }

data class ListRow(
    val id: Long,
    val name: String,
    /** What to put in the basket. */
    val main: String,
    /** Context or the pack note. */
    val sub: String?,
    val state: TickState,
    val aisle: AisleCode,
)

/** "Onion is on the list. +1?" */
data class PlusOne(
    val itemId: Long,
    val name: String,
    val line: String,
)

/** The item sheet (PLAN §3.5). */
data class ItemSheet(
    val id: Long,
    val name: String,
    /** One line per dimension: "2", "300 g". */
    val totals: List<String>,
    /** The recipes (or "Added by hand") that contributed, with how much each. */
    val sources: List<ItemSource>,
    val packNote: String?,
    val aisle: AisleCode,
)

/** One "From" row: "Weeknight chilli · 400 g", linked to the recipe's page when it has one. */
data class ItemSource(
    val title: String,
    val amount: String? = null,
    val url: String? = null,
)

data class ListUi(
    val aisles: List<Pair<AisleCode, List<ListRow>>> = emptyList(),
    val loaded: Boolean = false,
    /** Imports waiting for review: the "1 recipe ready to review" card. */
    val readyJobs: List<Long> = emptyList(),
    val plusOne: PlusOne? = null,
) {
    val isEmpty: Boolean get() = loaded && aisles.isEmpty()
    val left: Int get() = aisles.sumOf { (_, r) -> r.count { it.state != TickState.DONE } }
    val doneCount: Int get() = aisles.sumOf { (_, r) -> r.count { it.state == TickState.DONE } }
}

/** The shopping list (PLAN §3.5): grouped by aisle in store order, DONE rows sink within their aisle. */
class ListViewModel(
    private val c: AppContainer,
) : ViewModel() {
    /** Re-read on every list change, so a new walking order from Settings shows straight away. */
    private val order = c.repository.observeAisleOrder()
    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = messages.receiveAsFlow()

    private val plusOne = MutableStateFlow<PlusOne?>(null)
    private val _sheet = MutableStateFlow<ItemSheet?>(null)
    val sheet: StateFlow<ItemSheet?> = _sheet

    // The list and units behind the latest [ui], written off the main thread.
    @Volatile private var lastList: ShoppingList = ShoppingList()

    @Volatile private var lastUnits: UnitsSetting? = null

    /** The item sheet being loaded: a newer open, or a close, cancels it. */
    private var openJob: Job? = null

    val ui: StateFlow<ListUi> =
        combine(c.repository.list, c.settings.units, order, c.importJobs.ready, plusOne) { list, units, ord, ready, p ->
            lastList = list
            lastUnits = units
            build(list, units, ord).copy(readyJobs = ready.map { it.id }, plusOne = p)
        }.flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, ListUi())

    private fun build(
        list: ShoppingList,
        units: UnitsSetting,
        ord: List<AisleCode>,
    ): ListUi {
        val rows =
            list.liveItems.map { item ->
                val entry = item.catalogId?.let { c.catalog.byId[it] }
                val text = Display.row(list, item, entry, units)
                val state =
                    when {
                        list.isDone(item.id) -> TickState.DONE
                        list.isPartial(item.id) -> TickState.PARTIAL
                        else -> TickState.OPEN
                    }
                ListRow(item.id, item.displayName, text.main, text.sub, state, item.aisle) to item.position
            }
        val grouped =
            rows.groupBy { it.first.aisle }
                .toList()
                .sortedBy { (a, _) -> ord.indexOf(a).let { if (it < 0) Int.MAX_VALUE else it } }
                .map { (a, rs) -> a to rs.sortedWith(compareBy({ it.first.state == TickState.DONE }, { it.second })).map { it.first } }
        return ListUi(grouped, loaded = true)
    }

    /** Tap: tick, or untick a DONE row. No snackbar: tapping again is the undo. */
    fun toggle(row: ListRow) =
        viewModelScope.launch {
            c.repository.toggle(row.id)
        }

    /** "Delete" on the item card: closes it, with Undo on the list. */
    fun delete(
        id: Long,
        name: String,
    ) = viewModelScope.launch {
        openJob?.cancel()
        _sheet.value = null
        val stamp = c.repository.softDelete(id)
        messages.send(UiMessage("Deleted $name", undo = { c.repository.undoDelete(stamp) }))
    }

    fun finishShopping() =
        viewModelScope.launch {
            // Counted inside the write: a tick still on its way to the screen is included.
            val (stamp, n) = c.repository.finish()
            messages.send(UiMessage("Finished: removed $n ticked items", undo = { c.repository.undoDelete(stamp) }))
        }

    // ---- the item sheet (5.7) ---------------------------------------------------------------

    /** Opens the sheet for [id]. Opening another, or closing, before it loads wins. */
    fun openItem(id: Long): Job {
        openJob?.cancel()
        return viewModelScope.launch {
            // The list on screen; a read only when the item isn't in it yet.
            val list = lastList.takeIf { id in it.items } ?: c.repository.snapshot()
            val item = list.items[id] ?: return@launch
            val entry = item.catalogId?.let { c.catalog.byId[it] }
            val units = lastUnits ?: c.settings.units.first()
            val produce = item.aisle == AisleCode.PRODUCE
            val hint = list.contributionsOf(id).mapNotNull { it.unitHint }.distinct().singleOrNull()
            val totals =
                list.total(id).parts.filterValues { it > Rational.ZERO }.mapNotNull { (k, v) ->
                    Display.amount(QtyBase(mapOf(k to v)), units, produce, hint, entry?.gramsPerCup, entry?.displayPolicy)
                }
            val byUse = list.contributionsOf(id).groupBy { it.useId }
            val links = c.repository.useLinks(list, byUse.keys)
            val sources =
                byUse.mapNotNull { (u, cs) ->
                    val link = links[u] ?: return@mapNotNull null
                    val useHint = cs.mapNotNull { it.unitHint }.distinct().singleOrNull()
                    val qty = cs.fold(QtyBase.PRESENCE) { acc, x -> acc + x.qty }
                    val amount = Display.amount(qty, units, produce, useHint, entry?.gramsPerCup, entry?.displayPolicy)
                    ItemSource(link.title, amount, webLink(link.url))
                }
            // Main thread from here on: a cancel (newer open, close) can't slip in after this check.
            ensureActive()
            _sheet.value = ItemSheet(id, entry?.name ?: item.displayName, totals, sources, entry?.packNote, item.aisle)
        }.also { openJob = it }
    }

    fun closeItem() {
        openJob?.cancel()
        _sheet.value = null
    }

    /** After a change made from [id]'s sheet: closes it, unless another item's sheet is open by now. */
    private fun closeSheetOf(id: Long) = _sheet.update { if (it?.id == id) null else it }

    fun moveItem(
        id: Long,
        aisle: AisleCode,
        always: Boolean,
    ) = viewModelScope.launch {
        c.repository.moveAisle(id, aisle, always)
        closeSheetOf(id)
    }

    /** Edit quantity: the new total; the difference becomes a manual contribution. False if [text] has no amount. */
    fun editQuantity(
        id: Long,
        text: String,
    ): Boolean {
        val parsed = c.ruleParser.parse(text.trim() + " x")
        val q = parsed.shopBase(UnitLocale.METRIC)
        if (q.isPresence) return false
        viewModelScope.launch {
            c.repository.editQuantity(id, q)
            closeSheetOf(id)
        }
        return true
    }

    // ---- the "Add item" field (5.6b) ----------------------------------------------------

    /** Autocomplete: recent items first, then catalogue names; at most 6. */
    fun suggestions(query: String): List<String> {
        val q = query.trim().lowercase()
        if (q.length < 2) return emptyList()
        val recent = lastList.items.values.sortedByDescending { it.id }.map { it.displayName }
        val catalogue = c.catalog.entries.map { it.name }
        return (recent + catalogue)
            .distinct()
            .filter { it.lowercase().startsWith(q) || it.lowercase().contains(" $q") }
            .take(6)
    }

    /**
     * Enter in the field. Typed items are rule-parsed (no Claude) and skip
     * review. Several lines (a paste) add one item per line. A single item
     * already on the list asks "+1?" instead.
     */
    fun addTyped(text: String) =
        viewModelScope.launch {
            val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.size == 1) {
                val p = c.ruleParser.parse(lines.single())
                val existing = lastList.itemByKey(c.repository.target(p.name).mergeKey)
                if (existing != null) {
                    plusOne.value = PlusOne(existing.id, existing.displayName, lines.single())
                    return@launch
                }
            }
            for (l in lines) addLine(l)
            if (lines.size > 1) messages.send(UiMessage("Added ${lines.size} items"))
        }

    private suspend fun addLine(line: String) {
        val p = c.ruleParser.parse(line)
        if (p.name.isBlank()) return
        c.repository.manualAdd(p.name, p.shopBase(UnitLocale.METRIC), p.shopUnit)
    }

    /** "+1?" accepted: the typed amount (or one more) goes on as a manual contribution. */
    fun confirmPlusOne() =
        viewModelScope.launch {
            val p = plusOne.value ?: return@launch
            plusOne.value = null
            val parsed = c.ruleParser.parse(p.line)
            // As a typed add would: grams over spoons, the top of a range; one more when no amount was typed.
            val typed = parsed.shopBase(UnitLocale.METRIC)
            val q = if (parsed.quantity == null || typed.isPresence) QtyBase.of(Rational.ONE, null, UnitLocale.METRIC) else typed
            c.repository.manualAdd(parsed.name, q, parsed.shopUnit.takeIf { parsed.quantity != null })
        }

    fun dismissPlusOne() {
        plusOne.value = null
    }

    /** "Try a sample list". */
    fun addSample() =
        viewModelScope.launch {
            for (l in SAMPLE) addLine(l)
        }

    /** "Share list as text": what is left, by aisle. */
    fun shareText(): String {
        val ui = ui.value
        return buildString {
            append("Shopping list\n")
            for ((aisle, rows) in ui.aisles) {
                val open = rows.filter { it.state != TickState.DONE }
                if (open.isEmpty()) continue
                append("\n").append(aisle.displayName).append('\n')
                for (r in open) append("- ").append(r.main.replaceFirstChar { it.uppercase() }).append('\n')
            }
        }.trimEnd()
    }

    fun clearAll() =
        viewModelScope.launch {
            val stamp = c.repository.clearAll()
            messages.send(UiMessage("List cleared", undo = { c.repository.undoDelete(stamp) }))
        }

    companion object {
        val SAMPLE =
            listOf(
                "2 onions",
                "1 bulb garlic",
                "500g beef mince",
                "400g tin chopped tomatoes",
                "1 pint milk",
                "6 eggs",
                "1 loaf bread",
                "250g butter",
            )
    }
}
