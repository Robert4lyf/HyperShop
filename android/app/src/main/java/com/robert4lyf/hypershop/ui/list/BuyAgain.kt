package com.robert4lyf.hypershop.ui.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.robert4lyf.hypershop.data.BuyAgainView
import com.robert4lyf.hypershop.data.Regular
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.ui.common.PillShape
import com.robert4lyf.hypershop.ui.common.SectionLabel
import com.robert4lyf.hypershop.ui.common.UiMessage
import com.robert4lyf.hypershop.ui.theme.card
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Step Z: Buy again's sheet (the Meals screen and the food-at-home check, which shared this, went). */
data class BuyAgainUi(
    val loaded: Boolean = false,
    val buyAgain: BuyAgainView = BuyAgainView(),
) {
    /** Buy again chips not on the list yet: what "Add all" adds. */
    val toAdd: List<Regular> get() = buyAgain.regulars.filter { !it.onList }
}

/** What Buy again can do. */
data class BuyAgainActions(
    val onRegular: (Regular) -> Unit = {},
    val onAllRegulars: () -> Unit = {},
    val onRepeatLastShop: () -> Unit = {},
    val onPin: (Regular) -> Unit = {},
    val onHide: (Regular) -> Unit = {},
)

/**
 * Buy again (your usual things and last shop) for the sheet over the list: light, so it opens at once. Writes go one
 * at a time, every change with Undo.
 */
class BuyAgainViewModel(
    private val c: AppContainer,
) : ViewModel() {
    private val messages = Channel<UiMessage>(Channel.BUFFERED)
    val events: Flow<UiMessage> = messages.receiveAsFlow()
    private val writing = Mutex()
    private val scope get() = viewModelScope

    val ui: StateFlow<BuyAgainUi> =
        c.buyAgain.observe()
            .map { BuyAgainUi(loaded = true, buyAgain = it) }
            .flowOn(Dispatchers.Default)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BuyAgainUi())

    private fun buyAgain() = ui.value.buyAgain

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
}

/**
 * Buy again: one tap adds a chip, a chip already on the list says so and does
 * nothing; a long press pins or hides it. "Add all" and "Repeat last shop" are
 * the two quick ways through.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BuyAgainSection(
    ui: BuyAgainUi,
    actions: BuyAgainActions,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp).testTag("buy-again")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Buy again", Modifier.weight(1f), icon = Icons.Rounded.History)
            if (ui.toAdd.size > 1) {
                TextButton(onClick = actions.onAllRegulars, modifier = Modifier.testTag("buy-again-all")) {
                    Text("Add all ${ui.toAdd.size}")
                }
            }
        }
        if (ui.buyAgain.regulars.isNotEmpty()) {
            Text(
                "Hold one to pin it or hide it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in ui.buyAgain.regulars) RegularChip(r, actions)
            }
        }
        if (ui.buyAgain.lastShop.isNotEmpty()) {
            TextButton(
                onClick = actions.onRepeatLastShop,
                modifier = Modifier.padding(top = 4.dp).testTag("repeat-last-shop"),
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                Icon(Icons.Rounded.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                val names = ui.buyAgain.lastShop.take(3).joinToString(", ") { it.name }
                Text("Add the rest of last shop: $names" + more(ui.buyAgain.lastShop.size))
            }
        }
    }
}

private fun more(n: Int) = if (n > 3) " +${n - 3}" else ""

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RegularChip(
    r: Regular,
    actions: BuyAgainActions,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .clip(PillShape)
                .background(if (r.onList) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.card)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = if (r.onList) null else "Add to the shopping list",
                    onLongClickLabel = "Pin or hide",
                    onLongClick = { menu = true },
                    onClick = { if (!r.onList) actions.onRegular(r) },
                )
                .semantics { stateDescription = if (r.onList) "On the shopping list" else "Not on the shopping list" }
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .testTag("regular-${r.mergeKey}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                if (r.onList) Icons.Rounded.Check else Icons.Rounded.Add,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (r.onList) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
            )
            Text(
                r.name.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelLarge,
                color = if (r.onList) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (!r.pinned) {
                DropdownMenuItem(text = { Text("Always offer") }, onClick = {
                    menu = false
                    actions.onPin(r)
                })
            }
            DropdownMenuItem(text = { Text("Don't suggest") }, onClick = {
                menu = false
                actions.onHide(r)
            })
        }
    }
}
