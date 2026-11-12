package com.robert4lyf.hypershop.ui.meals

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robert4lyf.hypershop.data.BuyAgainView
import com.robert4lyf.hypershop.data.HaveSome
import com.robert4lyf.hypershop.data.HomeRow
import com.robert4lyf.hypershop.data.Regular
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.ui.common.UiMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Step Q: Buy again and the food-at-home check, as their own screens use them: one write at a time ([writing]),
 * every change with Undo on [messages]. Shared by [MealsViewModel] and [PrepViewModel], so the rules live once.
 */
class ShopPrepActions(
    private val c: AppContainer,
    private val scope: CoroutineScope,
    private val writing: Mutex,
    private val messages: Channel<UiMessage>,
    /** Buy again as shown now: what "Add all" and "the rest of last shop" add. */
    private val buyAgain: () -> BuyAgainView,
) {
    private fun <T> written(block: suspend () -> T) =
        c.applicationScope.async(start = CoroutineStart.UNDISPATCHED) { writing.withLock { block() } }

    private fun plural(
        n: Int,
        word: String,
    ) = if (n == 1) "1 $word" else "$n ${word}s"

    /** One Buy again chip, or "Add all", or "Repeat last shop": by hand, never doubling what is there. */
    fun addRegulars(
        rs: List<Regular>,
        what: String,
    ) {
        val write = written { c.buyAgain.add(rs) }
        scope.launch {
            val added = write.await()
            if (added.isEmpty()) {
                messages.send(UiMessage("Already on the shopping list"))
                return@launch
            }
            messages.send(UiMessage("Added $what", undo = { writing.withLock { c.buyAgain.undo(added) } }))
        }
    }

    fun addRegular(r: Regular) = addRegulars(listOf(r), r.name)

    fun addAllRegulars() =
        buyAgain().regulars.filter { !it.onList }.takeIf { it.isNotEmpty() }?.let { addRegulars(it, plural(it.size, "item")) }

    fun repeatLastShop() = buyAgain().lastShop.takeIf { it.isNotEmpty() }?.let { addRegulars(it, plural(it.size, "item")) }

    fun pin(r: Regular) =
        scope.launch {
            c.buyAgain.pin(r)
            messages.send(UiMessage("${r.name.replaceFirstChar { it.uppercase() }} is always offered"))
        }

    fun hide(r: Regular) =
        scope.launch {
            c.buyAgain.hide(r)
            // Undo gives back what there was: a pin, or the plain rules.
            messages.send(UiMessage("Won't suggest ${r.name}", undo = { if (r.pinned) c.buyAgain.pin(r) else c.buyAgain.reset(r) }))
        }

    /** At home check: "Have" (off the list for this shop) or "Need" (back on it), for one or several. */
    fun have(rows: List<HomeRow>) {
        val write = written { c.homeCheck.have(rows.map { it.mergeKey }) }
        scope.launch {
            val lines = write.await()
            if (lines.isEmpty()) return@launch
            val what = rows.singleOrNull()?.name ?: plural(rows.size, "item")
            messages.send(UiMessage("$what at home", undo = { writing.withLock { c.homeCheck.needLines(lines) } }))
        }
    }

    fun need(row: HomeRow) {
        val write = written { c.homeCheck.need(listOf(row.mergeKey)) }
        scope.launch {
            val lines = write.await()
            if (lines.isEmpty()) return@launch
            messages.send(UiMessage("${row.name} back on the shopping list", undo = { writing.withLock { c.homeCheck.haveLines(lines) } }))
        }
    }

    /**
     * Y.19 "Have some": [text] of the row at home, out of what all the week's recipes need; only the rest stays to buy.
     * As much as is needed is "Have it".
     */
    fun haveSome(
        row: HomeRow,
        text: String,
        onResult: (String?) -> Unit = {},
    ) {
        val write = written { c.homeCheck.changeSome(row.mergeKey, text) }
        scope.launch {
            val name = row.name.replaceFirstChar { it.uppercase() }
            val change =
                try {
                    write.await()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    val error = "Could not save the amount; your entry is still here."
                    onResult(error)
                    messages.send(UiMessage(error))
                    return@launch
                }
            val undo: suspend () -> Unit = {
                change.undo?.let { token ->
                    if (!writing.withLock {
                            c.homeCheck.undoSome(token)
                        }
                    ) {
                        messages.send(UiMessage("The list changed since then, so Undo kept the newer changes."))
                    }
                }
            }
            val message =
                when (change.result) {
                    HaveSome.SET ->
                        UiMessage("$name: ${text.trim()} at home, the rest stays on the shopping list", undo = undo)
                    HaveSome.ENOUGH ->
                        UiMessage("$name: that covers it, at home", undo = undo)
                    HaveSome.ALREADY_ALLOCATED ->
                        UiMessage(
                            "Some home food already covers earlier recipe amounts. Keep that allocation " +
                                "or correct the source first.",
                        )
                    HaveSome.NOT_AN_AMOUNT -> UiMessage("Enter an amount, like 300 g")
                    HaveSome.DIFFERENT_MEASURE -> UiMessage("Enter it as the list measures it: ${row.needed ?: "the same unit"}")
                }
            onResult(if (change.result in setOf(HaveSome.SET, HaveSome.ENOUGH)) null else message.text)
            messages.send(message)
        }
    }

    /** Takes back "Have some": all of it to buy again. */
    fun clearSome(row: HomeRow) {
        written { c.homeCheck.clearSome(row.mergeKey) }
    }
}

/**
 * Step Q: what the Buy again sheet and the Food at home screen need, and nothing else: Buy again, this week's
 * home check and the list's count. Light, so each opens at once; a [MealsViewModel] would first work out the
 * whole week (meals, previews, the recipe library) before showing anything.
 */
class PrepViewModel(
    c: AppContainer,
) : ViewModel() {
    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = messages.receiveAsFlow()

    val ui: StateFlow<MealsUi> =
        combine(c.buyAgain.observe(), c.homeCheck.observe(), c.repository.list) { buy, home, list ->
            MealsUi(
                loaded = true,
                buyAgain = buy,
                home = home,
                listHasItems = list.liveItems.isNotEmpty(),
                listLeft = list.liveItems.count { !list.isDone(it.id) && !list.isUnavailable(it.id) },
            )
        }.flowOn(Dispatchers.Default).stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), MealsUi())

    val actions = ShopPrepActions(c, viewModelScope, Mutex(), messages) { ui.value.buyAgain }
}
