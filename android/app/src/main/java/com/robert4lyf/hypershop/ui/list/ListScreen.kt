package com.robert4lyf.hypershop.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckBox
import androidx.compose.material.icons.filled.CheckBoxOutlineBlank
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.IndeterminateCheckBox
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.unit.dp
import com.robert4lyf.hypershop.core.model.AisleCode

data class ListActions(
    val onToggle: (ListRow) -> Unit,
    val onDelete: (ListRow) -> Unit,
    val onFinish: () -> Unit,
    val onClearAll: () -> Unit,
    val onOpenItem: (ListRow) -> Unit = {},
    val onShare: () -> Unit = {},
)

/** The list (PLAN §3.5), stateless. [header] holds the "Add item" field and cards (Step 5.6b). */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListScreen(
    ui: ListUi,
    actions: ListActions,
    header: LazyListScope.() -> Unit = {},
    empty: @Composable () -> Unit = { Text("Nothing on the list", Modifier.padding(24.dp)) },
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(
                title = { Text(if (ui.loaded && !ui.isEmpty) "${ui.left} left" else "HyperShop") },
                actions = {
                    if (ui.doneCount > 0) TextButton(onClick = actions.onFinish) { Text("Finish shopping") }
                    var menu by remember { mutableStateOf(false) }
                    Box {
                        IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
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
            )
            LazyColumn(Modifier.fillMaxSize().testTag("list")) {
                header()
                if (ui.isEmpty) item { empty() }
                for ((aisle, rows) in ui.aisles) {
                    item(key = "aisle-${aisle.name}") { AisleTitle(aisle, rows) }
                    items(rows, key = { it.id }) { ListRowItem(it, actions) }
                }
            }
        }
    }
}

@Composable
private fun AisleTitle(
    aisle: AisleCode,
    rows: List<ListRow>,
) {
    val left = rows.count { it.state != TickState.DONE }
    Text(
        if (left == 0) "${aisle.displayName} ✓" else aisle.displayName,
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 4.dp),
    )
}

/**
 * One row is one TalkBack node: tapping ticks or unticks; "Delete" and "Open"
 * are custom actions, so swipe-to-delete has an accessible equivalent. DONE
 * rows are dimmed and carry a tick mark (not colour alone).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ListRowItem(
    row: ListRow,
    actions: ListActions,
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
    SwipeToDismissBox(
        state = swipe,
        enableDismissFromStartToEnd = false,
        backgroundContent = {
            Box(
                Modifier.fillMaxSize().background(MaterialTheme.colorScheme.errorContainer).padding(16.dp),
                contentAlignment = Alignment.CenterEnd,
            ) {
                Icon(Icons.Filled.Delete, contentDescription = null)
            }
        },
    ) {
        Row(
            Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .heightIn(min = 56.dp)
                .clickable { actions.onToggle(row) }
                .clearAndSetSemantics {
                    testTag = "item-${row.id}"
                    contentDescription = listOfNotNull(row.main, row.sub).joinToString(", ")
                    stateDescription = state
                    onClick(label = if (row.state == TickState.DONE) "Untick" else "Tick") {
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
                }.padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Icon(
                when (row.state) {
                    TickState.DONE -> Icons.Filled.CheckBox
                    TickState.PARTIAL -> Icons.Filled.IndeterminateCheckBox
                    TickState.OPEN -> Icons.Filled.CheckBoxOutlineBlank
                },
                contentDescription = null,
                tint = if (row.state == TickState.OPEN) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp),
            )
            Column(Modifier.weight(1f).alpha(if (row.state == TickState.DONE) 0.55f else 1f)) {
                Text(row.main.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.bodyLarge)
                row.sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
            IconButton(onClick = { actions.onOpenItem(row) }) { Icon(Icons.Filled.MoreVert, contentDescription = null) }
        }
    }
}
