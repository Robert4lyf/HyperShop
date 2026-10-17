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
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
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
    private val order = MutableStateFlow(AisleCode.entries.toList())
    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = messages.receiveAsFlow()

    private val plusOne = MutableStateFlow<PlusOne?>(null)
    private var lastList: ShoppingList = ShoppingList()

    val ui: StateFlow<ListUi> =
        combine(c.repository.observe(), c.settings.units, order, c.importJobs.ready, plusOne) { list, units, ord, ready, p ->
            lastList = list
            build(list, units, ord).copy(readyJobs = ready.map { it.id }, plusOne = p)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ListUi())

    init {
        viewModelScope.launch { order.value = c.repository.aisleOrder() }
    }

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

    /** Tap: tick, or untick a DONE row. Both offer Undo. */
    fun toggle(row: ListRow) =
        viewModelScope.launch {
            if (row.state == TickState.DONE) {
                if (c.repository.untick(row.id)) messages.send(UiMessage("Unticked ${row.name}", undo = { c.repository.tick(row.id) }))
            } else if (c.repository.tick(row.id)) {
                messages.send(UiMessage("Ticked ${row.name}", undo = { c.repository.untick(row.id) }, seconds = 4))
            }
        }

    fun delete(row: ListRow) =
        viewModelScope.launch {
            val stamp = c.repository.softDelete(row.id)
            messages.send(UiMessage("Deleted ${row.name}", undo = { c.repository.undoDelete(stamp) }))
        }

    fun finishShopping() =
        viewModelScope.launch {
            val n = ui.value.doneCount
            val stamp = c.repository.finishShopping()
            messages.send(UiMessage("Finished: removed $n ticked items", undo = { c.repository.undoDelete(stamp) }))
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
        c.repository.manualAdd(p.name, QtyBase.of(p.quantityMax ?: p.quantity, p.unit, UnitLocale.METRIC), p.unit)
    }

    /** "+1?" accepted: the typed amount (or one more) goes on as a manual contribution. */
    fun confirmPlusOne() =
        viewModelScope.launch {
            val p = plusOne.value ?: return@launch
            plusOne.value = null
            val parsed = c.ruleParser.parse(p.line)
            val q = QtyBase.of(parsed.quantity ?: Rational.ONE, parsed.unit.takeIf { parsed.quantity != null }, UnitLocale.METRIC)
            c.repository.manualAdd(parsed.name, q, parsed.unit)
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
