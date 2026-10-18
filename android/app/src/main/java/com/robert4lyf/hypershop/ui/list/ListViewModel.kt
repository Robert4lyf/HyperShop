package com.robert4lyf.hypershop.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robert4lyf.hypershop.catalog.EntryEdit
import com.robert4lyf.hypershop.core.catalog.DisplayPolicy
import com.robert4lyf.hypershop.core.merge.Display
import com.robert4lyf.hypershop.core.merge.ShoppingList
import com.robert4lyf.hypershop.core.merge.UnitsSetting
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.QtyBase
import com.robert4lyf.hypershop.core.model.Rational
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.data.InStockLine
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
import kotlinx.coroutines.flow.map
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
    /** Its group within the aisle ("Herbs"), shown in the categorised view. */
    val group: String? = null,
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
    /** Names it can be swapped for (from the ingredient library). */
    val swaps: List<String> = emptyList(),
    /** It is a catalogue ingredient, so "Always use instead" can be set on it. */
    val canAlways: Boolean = false,
)

/** One "From" row: "Weeknight chilli · 400 g", linked to the recipe's page when it has one. */
data class ItemSource(
    val title: String,
    val amount: String? = null,
    val url: String? = null,
)

/** One ingredient you marked in stock on review, for the recipes on the list. */
data class InStockRow(
    val key: String,
    val name: String,
    /** "2tbsp olive oil", or just "Salt". */
    val main: String,
    /** The recipes that need it. */
    val from: String,
    val aisle: AisleCode,
)

data class ListUi(
    val aisles: List<Pair<AisleCode, List<ListRow>>> = emptyList(),
    /** The "In stock" section, below the aisles. */
    val inStock: List<InStockRow> = emptyList(),
    val loaded: Boolean = false,
    /** Imports waiting for review: the "1 recipe ready to review" card. */
    val readyJobs: List<Long> = emptyList(),
    val plusOne: PlusOne? = null,
    /** Rows grouped within each aisle, with a label per group. */
    val categorised: Boolean = false,
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

    /** The walking order and the categorised switch. */
    private val view = combine(order, c.settings.listCategorised) { o, cat -> o to cat }

    /** The in-stock lines, read again on every list change (an import, a removed recipe, "Need it"). */
    private val inStockLines = c.repository.list.map { c.repository.inStockOnList(it) }

    val ui: StateFlow<ListUi> =
        combine(
            combine(c.repository.list, c.settings.units, view, c.importJobs.ready, plusOne) { list, units, (ord, cat), ready, p ->
                lastList = list
                lastUnits = units
                build(list, units, ord, cat).copy(readyJobs = ready.map { it.id }, plusOne = p) to units
            },
            inStockLines,
        ) { (ui, units), lines -> ui.copy(inStock = inStockRows(lines, units)) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.Eagerly, ListUi())

    private fun inStockRows(
        lines: List<InStockLine>,
        units: UnitsSetting,
    ): List<InStockRow> =
        lines
            .groupBy { it.target.mergeKey }
            .map { (key, ls) ->
                val t = ls.first().target
                val entry = t.catalogId?.let { c.catalog.byId[it] }
                val name = entry?.name ?: t.displayName
                val qty = ls.fold(QtyBase.PRESENCE) { acc, l -> acc + l.qty }
                val presence = entry?.displayPolicy == DisplayPolicy.PRESENCE && units != UnitsSetting.AS_WRITTEN
                val amount =
                    if (presence) {
                        null
                    } else {
                        Display.amount(
                            qty,
                            units,
                            t.aisle == AisleCode.PRODUCE,
                            Display.hintOf(ls.map { it.unitHint }),
                            entry?.gramsPerCup,
                            entry?.displayPolicy,
                        )
                    }
                val from = ls.map { it.recipeTitle }.distinct().joinToString(", ")
                InStockRow(key, name, listOfNotNull(amount, name).joinToString(" "), from, t.aisle)
            }.sortedBy { it.name.lowercase() }

    /** "Need it": the in-stock lines go on the list after all, under their recipes. */
    fun needIt(row: InStockRow) =
        viewModelScope.launch {
            if (c.repository.needAfterAll(row.key) > 0) messages.send(UiMessage("Added ${row.name} to the list"))
        }

    private fun build(
        list: ShoppingList,
        units: UnitsSetting,
        ord: List<AisleCode>,
        categorised: Boolean = false,
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
                ListRow(item.id, item.displayName, text.main, text.sub, state, item.aisle, entry?.group) to item.position
            }
        // Categorised: by group (the catalogue's order), then as usual within it.
        val order =
            if (categorised) {
                compareBy<Pair<ListRow, Int>>(
                    { c.catalog.groupRank(it.first.aisle, it.first.group) },
                    { it.first.state == TickState.DONE },
                    { it.second },
                )
            } else {
                compareBy({ it.first.state == TickState.DONE }, { it.second })
            }
        val grouped =
            rows.groupBy { it.first.aisle }
                .toList()
                .sortedBy { (a, _) -> ord.indexOf(a).let { if (it < 0) Int.MAX_VALUE else it } }
                .map { (a, rs) -> a to rs.sortedWith(order).map { it.first } }
        return ListUi(grouped, loaded = true, categorised = categorised)
    }

    /** The header's switch between the order added and grouped within each aisle. */
    fun toggleCategorised() =
        viewModelScope.launch {
            c.settings.setListCategorised(!c.settings.listCategorised.first())
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
            val hint = Display.hintOf(list.contributionsOf(id).map { it.unitHint })
            val totals =
                list.total(id).parts.filterValues { it > Rational.ZERO }.mapNotNull { (k, v) ->
                    Display.amount(QtyBase(mapOf(k to v)), units, produce, hint, entry?.gramsPerCup, entry?.displayPolicy)
                }
            val byUse = list.contributionsOf(id).groupBy { it.useId }
            val links = c.repository.useLinks(list, byUse.keys)
            val sources =
                byUse.mapNotNull { (u, cs) ->
                    val link = links[u] ?: return@mapNotNull null
                    val useHint = Display.hintOf(cs.map { it.unitHint })
                    val qty = cs.fold(QtyBase.PRESENCE) { acc, x -> acc + x.qty }
                    val amount = Display.amount(qty, units, produce, useHint, entry?.gramsPerCup, entry?.displayPolicy)
                    ItemSource(link.title, amount, webLink(link.url))
                }
            // Main thread from here on: a cancel (newer open, close) can't slip in after this check.
            ensureActive()
            val swaps = entry?.swaps.orEmpty().mapNotNull { c.catalog.byId[it]?.name }
            val pack = entry?.packNote?.let(Display::compactUnits)
            _sheet.value = ItemSheet(id, entry?.name ?: item.displayName, totals, sources, pack, item.aisle, swaps, entry != null)
        }.also { openJob = it }
    }

    /**
     * "Swap for": the item becomes [to] (merging into it if it's on the list).
     * [always] also sets "always use instead" on the ingredient in the library,
     * so later recipes ask for [to] straight away.
     */
    fun swapItem(
        id: Long,
        to: String,
        always: Boolean,
    ) = viewModelScope.launch {
        val item = lastList.items[id] ?: c.repository.snapshot().items[id] ?: return@launch
        val from = item.catalogId?.let { c.catalog.byId[it] }
        val name = c.repository.swapItem(id, to) ?: return@launch
        closeSheetOf(id)
        val target = c.catalog.lookup(to)
        if (always && from != null && target != null) {
            c.library.save(EntryEdit.of(from).copy(replaceWith = target.id))
            c.repository.rekeyIfCatalogChanged()
        }
        val was = from?.name ?: item.displayName
        messages.send(UiMessage(if (always) "Swapped $was for $name, from now on" else "Swapped $was for $name"))
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
