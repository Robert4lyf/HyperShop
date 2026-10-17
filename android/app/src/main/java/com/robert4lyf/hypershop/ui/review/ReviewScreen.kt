package com.robert4lyf.hypershop.ui.review

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.robert4lyf.hypershop.core.model.AisleCode

/** The review screen's default view (PLAN §3.1), stateless. */
@Composable
fun ReviewScreen(
    ui: ReviewUi,
    onInStock: (String, Boolean) -> Unit,
    onRow: (String) -> Unit,
    onServings: (Int) -> Unit,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
    onRetryClaude: () -> Unit,
    onAisleInStock: (AisleCode) -> Unit,
    onClearInStock: () -> Unit,
) {
    Column(Modifier.fillMaxSize()) {
        Header(ui, onServings, onAdd, onCancel, onClearInStock)
        BadgeRow(ui, onRetryClaude)
        ui.notice?.let {
            Text(it, Modifier.padding(horizontal = 16.dp, vertical = 4.dp).testTag("notice"), color = MaterialTheme.colorScheme.primary)
        }
        LazyColumn(Modifier.fillMaxSize()) {
            for ((aisle, rows) in ui.aisles) {
                item(key = "aisle-${aisle.name}") { AisleHeader(aisle, onAisleInStock) }
                items(rows, key = { it.key }) { ReviewRowItem(it, onInStock, onRow) }
            }
        }
    }
}

@Composable
private fun Header(
    ui: ReviewUi,
    onServings: (Int) -> Unit,
    onAdd: () -> Unit,
    onCancel: () -> Unit,
    onClearInStock: () -> Unit,
) {
    Surface(tonalElevation = 2.dp) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(ui.title, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "More") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Clear in stock") }, onClick = {
                            menu = false
                            onClearInStock()
                        })
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ui.servings?.let { s ->
                    IconButton(
                        onClick = { onServings(s - 1) },
                        enabled = s > 1,
                    ) { Icon(Icons.Filled.Remove, contentDescription = "Fewer servings") }
                    Text("Serves $s", Modifier.testTag("servings"))
                    IconButton(onClick = { onServings(s + 1) }) { Icon(Icons.Filled.Add, contentDescription = "More servings") }
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onCancel) { Text("Cancel") }
                Button(onClick = onAdd, enabled = ui.addCount > 0 && !ui.busy, modifier = Modifier.testTag("add")) {
                    Text(if (ui.addCount == 1) "Add 1 item" else "Add ${ui.addCount} items")
                }
            }
        }
    }
}

@Composable
private fun BadgeRow(
    ui: ReviewUi,
    onRetryClaude: () -> Unit,
) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        when (ui.badge) {
            Badge.CLAUDE -> AssistChip(onClick = {}, label = { Text("Parsed by Claude") })
            Badge.CLAUDE_PENDING -> {
                AssistChip(onClick = {}, label = { Text("Claude pending") })
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = onRetryClaude, enabled = !ui.busy) { Text("Retry with Claude") }
            }
            Badge.RULES -> AssistChip(onClick = {}, label = { Text("Parsed offline") })
        }
        if (ui.busy) CircularProgressIndicator(Modifier.padding(start = 8.dp).width(20.dp))
    }
}

@Composable
private fun AisleHeader(
    aisle: AisleCode,
    onAisleInStock: (AisleCode) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth().padding(start = 16.dp, top = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            aisle.displayName,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f),
        )
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, contentDescription = "${aisle.displayName} options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Mark this aisle in stock") }, onClick = {
                    menu = false
                    onAisleInStock(aisle)
                })
            }
        }
    }
    HorizontalDivider()
}

/**
 * One row, one TalkBack node: name, quantity and the in-stock box (at most 3
 * elements). Tapping the row opens its sheet; the box toggles in stock. In
 * stock rows stay put, greyed and struck through (not colour alone).
 */
@Composable
private fun ReviewRowItem(
    row: ReviewRow,
    onInStock: (String, Boolean) -> Unit,
    onRow: (String) -> Unit,
) {
    val struck = if (row.inStock) TextDecoration.LineThrough else TextDecoration.None
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button) { onRow(row.key) }
            .semantics(mergeDescendants = true) {}
            .testTag("row-${row.key}")
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).alpha(if (row.inStock) 0.6f else 1f)) {
            Text(
                row.name + if (row.check) " ⚠" else "",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (row.check) FontWeight.SemiBold else FontWeight.Normal,
                textDecoration = struck,
            )
            row.quantity?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = struck,
                )
            }
        }
        Text("In stock", style = MaterialTheme.typography.labelMedium)
        Checkbox(checked = row.inStock, onCheckedChange = { onInStock(row.key, it) })
    }
}
