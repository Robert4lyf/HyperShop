package com.robert4lyf.hypershop.ui.list

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robert4lyf.hypershop.core.merge.Display
import com.robert4lyf.hypershop.core.merge.ShoppingList
import com.robert4lyf.hypershop.core.merge.UnitsSetting
import com.robert4lyf.hypershop.core.model.AisleCode
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

data class ListUi(
    val aisles: List<Pair<AisleCode, List<ListRow>>> = emptyList(),
    val loaded: Boolean = false,
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

    val ui: StateFlow<ListUi> =
        combine(c.repository.observe(), c.settings.units, order) { list, units, ord -> build(list, units, ord) }
            .stateIn(viewModelScope, SharingStarted.Eagerly, ListUi())

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

    fun clearAll() =
        viewModelScope.launch {
            val stamp = c.repository.clearAll()
            messages.send(UiMessage("List cleared", undo = { c.repository.undoDelete(stamp) }))
        }
}
