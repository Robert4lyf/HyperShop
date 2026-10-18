package com.robert4lyf.hypershop.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DoneAll
import androidx.compose.material.icons.rounded.ExpandLess
import androidx.compose.material.icons.rounded.ExpandMore
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.ui.common.AisleBadge
import com.robert4lyf.hypershop.ui.common.ScreenHeader
import com.robert4lyf.hypershop.ui.common.TickMark
import com.robert4lyf.hypershop.ui.common.sectionItem
import com.robert4lyf.hypershop.ui.common.sectionPosition
import com.robert4lyf.hypershop.ui.theme.card

data class ListActions(
    val onToggle: (ListRow) -> Unit,
    val onDelete: (ListRow) -> Unit,
    val onFinish: () -> Unit,
    val onClearAll: () -> Unit,
    val onOpenItem: (ListRow) -> Unit = {},
    val onShare: () -> Unit = {},
)

/**
 * The list (PLAN §3.5), stateless: the count and progress, then [header] (the
 * "Add item" field and cards, Step 5.6b), then one card per aisle.
 */
@Composable
fun ListScreen(
    ui: ListUi,
    actions: ListActions,
    header: LazyListScope.() -> Unit = {},
    empty: @Composable () -> Unit = { Text("Nothing on the list", Modifier.padding(24.dp)) },
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            // Pinned: the count, "Finish shopping" and the menu stay in reach on a long list.
            ListTop(ui, actions)
            ListBody(ui, actions, header, empty)
        }
    }
}

@Composable
private fun ListBody(
    ui: ListUi,
    actions: ListActions,
    header: LazyListScope.() -> Unit,
    empty: @Composable () -> Unit,
) {
    val card = MaterialTheme.colorScheme.card
    val delete = MaterialTheme.colorScheme.errorContainer
    // Collapsed aisles, by name: kept across tab switches and rotation.
    var collapsed by rememberSaveable { mutableStateOf(emptyList<String>()) }
    LazyColumn(Modifier.fillMaxSize().testTag("list"), contentPadding = PaddingValues(bottom = 24.dp)) {
        header()
        if (ui.isEmpty) item { empty() }
        for ((aisle, rows) in ui.aisles) {
            val open = aisle.name !in collapsed
            val count = if (open) rows.size + 1 else 1
            item(key = "aisle-${aisle.name}") {
                AisleTitle(
                    aisle,
                    rows,
                    open,
                    onToggle = { collapsed = if (open) collapsed + aisle.name else collapsed - aisle.name },
                    modifier = Modifier.padding(top = 16.dp).sectionItem(sectionPosition(0, count), card),
                )
            }
            if (open) {
                itemsIndexed(rows, key = { _, r -> r.id }) { i, r ->
                    ListRowItem(r, actions, Modifier.sectionItem(sectionPosition(i + 1, count), delete))
                }
            }
        }
    }
}

@Composable
private fun ListTop(
    ui: ListUi,
    actions: ListActions,
) {
    val shopping = ui.loaded && !ui.isEmpty
    val total = ui.left + ui.doneCount
    ScreenHeader(
        title = if (shopping) "${ui.left} left" else "HyperShop",
        overline = if (shopping) "Shopping list" else null,
        subtitle =
            when {
                !shopping -> "Recipes in, shopping list out"
                ui.left == 0 -> "Everything's in the basket"
                else -> "${ui.doneCount} of $total ticked"
            },
        actions = {
            if (ui.doneCount > 0) {
                FilledTonalButton(onClick = actions.onFinish, contentPadding = PaddingValues(horizontal = 14.dp)) {
                    Icon(Icons.Rounded.DoneAll, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(6.dp))
                    Text("Finish shopping")
                }
            }
            var menu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Share list as text") }, enabled = !ui.isEmpty, onClick = {
                        menu = false
                        actions.onShare()
                    })
                    DropdownMenuItem(text = { Text("Clear all") }, enabled = !ui.isEmpty, onClick = {
                        menu = false
                        actions.onClearAll()
                    })
                }
            }
        },
        below = {
            if (shopping) {
                Spacer(Modifier.height(12.dp))
                LinearProgressIndicator(
                    progress = { if (total == 0) 0f else ui.doneCount / total.toFloat() },
                    modifier = Modifier.fillMaxWidth().height(8.dp),
                    strokeCap = StrokeCap.Round,
                    gapSize = 4.dp,
                    drawStopIndicator = {},
                    trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
                )
            }
        },
    )
}

@Composable
private fun AisleTitle(
    aisle: AisleCode,
    rows: List<ListRow>,
    open: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier,
) {
    val left = rows.count { it.state != TickState.DONE }
    Row(
        modifier
            .fillMaxWidth()
            .clickable(onClickLabel = if (open) "Collapse" else "Expand", role = Role.Button, onClick = onToggle)
            .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp)
            .semantics(mergeDescendants = true) {
                heading()
                stateDescription = if (open) "Expanded" else "Collapsed"
            }
            .testTag("aisle-${aisle.name}"),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AisleBadge(aisle)
        Text(aisle.displayName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (left == 0) {
            Icon(
                Icons.Rounded.CheckCircle,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
            Text("Done", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        } else {
            Text("$left left", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Icon(
            if (open) Icons.Rounded.ExpandLess else Icons.Rounded.ExpandMore,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * One row is one TalkBack node: tapping ticks or unticks; "Delete" and "Open"
 * are custom actions, so swipe-to-delete has an accessible equivalent. DONE
 * rows are greyed, struck through and carry a tick (not colour alone).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListRowItem(
    row: ListRow,
    actions: ListActions,
    tile: Modifier,
) {
    val swipe =
        rememberSwipeToDismissBoxState(
            confirmValueChange = {
                if (it == SwipeToDismissBoxValue.EndToStart) actions.onDelete(row)
                false
            },
        )
    val state =
        when (row.state) {
            TickState.DONE -> "Ticked"
            TickState.PARTIAL -> "Partly ticked"
            TickState.OPEN -> "Not ticked"
        }
    val done = row.state == TickState.DONE
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        modifier = tile,
        backgroundContent = {
            Row(
                Modifier.fillMaxSize().padding(horizontal = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                Text("Delete", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onErrorContainer)
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
            }
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.card)
                .heightIn(min = 64.dp)
                .clickable { actions.onToggle(row) }
                .clearAndSetSemantics {
                    testTag = "item-${row.id}"
                    contentDescription = listOfNotNull(row.main, row.sub).joinToString(", ")
                    stateDescription = state
                    onClick(label = if (done) "Untick" else "Tick") {
                        actions.onToggle(row)
                        true
                    }
                    customActions =
                        listOf(
                            CustomAccessibilityAction("Delete") {
                                actions.onDelete(row)
                                true
                            },
                            CustomAccessibilityAction("Details") {
                                actions.onOpenItem(row)
                                true
                            },
                        )
                }.padding(start = 16.dp, end = 4.dp, top = 10.dp, bottom = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            TickMark(done = done, partial = row.state == TickState.PARTIAL)
            Column(Modifier.weight(1f)) {
                Text(
                    row.main.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (done) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                    textDecoration = if (done) TextDecoration.LineThrough else null,
                )
                row.sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            IconButton(onClick = { actions.onOpenItem(row) }) {
                Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
