package com.robert4lyf.hypershop.ui.review

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.ui.common.AisleBadge
import com.robert4lyf.hypershop.ui.common.BottomActionBar
import com.robert4lyf.hypershop.ui.common.InfoBanner
import com.robert4lyf.hypershop.ui.common.SectionPosition
import com.robert4lyf.hypershop.ui.common.Tone
import com.robert4lyf.hypershop.ui.common.rememberOpenLink
import com.robert4lyf.hypershop.ui.common.sectionItem
import com.robert4lyf.hypershop.ui.common.sectionPosition
import java.net.URI

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
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TopBar(onCancel, onClearInStock)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 16.dp)) {
                item(key = "hero") { Hero(ui, onServings, onRetryClaude) }
                ui.notice?.let { n ->
                    item(key = "notice") {
                        InfoBanner(
                            n,
                            Icons.Rounded.Info,
                            Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp).testTag("notice"),
                            Tone.PRIMARY,
                        )
                    }
                }
                for ((aisle, rows) in ui.aisles) {
                    val count = rows.size + 1
                    item(key = "aisle-${aisle.name}") {
                        AisleHeader(aisle, rows, onAisleInStock, Modifier.padding(top = 12.dp).sectionItem(sectionPosition(0, count)))
                    }
                    itemsIndexed(rows, key = { _, r -> r.key }) { i, r ->
                        ReviewRowItem(r, onInStock, onRow, sectionPosition(i + 1, count))
                    }
                }
            }
            BottomActionBar {
                Button(
                    onClick = onAdd,
                    enabled = ui.addCount > 0 && !ui.busy,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("add"),
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(Modifier.size(8.dp))
                    Text(
                        if (ui.addCount == 1) "Add 1 item" else "Add ${ui.addCount} items",
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    onCancel: () -> Unit,
    onClearInStock: () -> Unit,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        IconButton(onClick = onCancel) { Icon(Icons.Rounded.Close, contentDescription = "Cancel") }
        Text("Review ingredients", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        var menu by remember { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Clear in stock") }, onClick = {
                    menu = false
                    onClearInStock()
                })
            }
        }
    }
}

/** The recipe: who read it, its title and site, and how many it serves. */
@Composable
private fun Hero(
    ui: ReviewUi,
    onServings: (Int) -> Unit,
    onRetryClaude: () -> Unit,
) {
    val open = rememberOpenLink()
    Column(
        Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            when (ui.badge) {
                Badge.CLAUDE -> Badge("Parsed by Claude", Icons.Rounded.AutoAwesome)
                Badge.CLAUDE_PENDING -> {
                    Badge("Claude pending", Icons.Rounded.HourglassTop)
                    TextButton(onClick = onRetryClaude, enabled = !ui.busy) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Retry with Claude")
                    }
                }
                Badge.RULES -> Badge("Parsed offline", Icons.Rounded.CloudOff)
            }
            if (ui.busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
        Text(ui.title, style = MaterialTheme.typography.headlineMedium, maxLines = 3)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            ui.servings?.let { s -> Servings(s, onServings) }
            Spacer(Modifier.weight(1f))
            ui.url?.let { url ->
                TextButton(onClick = { open(url) }, modifier = Modifier.testTag("open-recipe")) {
                    Text(siteName(url) ?: "Open recipe", maxLines = 1)
                    Spacer(Modifier.size(6.dp))
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Open recipe", modifier = Modifier.size(18.dp))
                }
            }
        }
    }
}

/** "recipetineats.com" from the recipe's address. */
internal fun siteName(url: String): String? = runCatching { URI(url).host?.removePrefix("www.") }.getOrNull()?.takeIf { it.isNotBlank() }

@Composable
private fun Badge(
    text: String,
    icon: ImageVector,
) {
    AssistChip(
        onClick = {},
        label = { Text(text) },
        leadingIcon = { Icon(icon, contentDescription = null, modifier = Modifier.size(AssistChipDefaults.IconSize)) },
        shape = RoundedCornerShape(50),
        colors =
            AssistChipDefaults.assistChipColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer,
                labelColor = MaterialTheme.colorScheme.onSecondaryContainer,
                leadingIconContentColor = MaterialTheme.colorScheme.onSecondaryContainer,
            ),
        border = null,
    )
}

/** − Serves 4 + */
@Composable
private fun Servings(
    s: Int,
    onServings: (Int) -> Unit,
) {
    Surface(shape = RoundedCornerShape(50), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(
                onClick = { onServings(s - 1) },
                enabled = s > 1,
            ) { Icon(Icons.Rounded.Remove, contentDescription = "Fewer servings") }
            Text("Serves $s", Modifier.testTag("servings"), style = MaterialTheme.typography.titleSmall)
            IconButton(onClick = { onServings(s + 1) }) { Icon(Icons.Rounded.Add, contentDescription = "More servings") }
        }
    }
}

@Composable
private fun AisleHeader(
    aisle: AisleCode,
    rows: List<ReviewRow>,
    onAisleInStock: (AisleCode) -> Unit,
    modifier: Modifier,
) {
    var menu by remember { mutableStateOf(false) }
    val adding = rows.count { !it.inStock }
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AisleBadge(aisle)
        Text(
            aisle.displayName,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Text(
            if (adding == 0) "All in stock" else "$adding to add",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "${aisle.displayName} options") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Mark this aisle in stock") }, onClick = {
                    menu = false
                    onAisleInStock(aisle)
                })
            }
        }
    }
}

/**
 * One row, one TalkBack node: name, quantity and the in-stock toggle (at most
 * 3 elements). Tapping the row opens its sheet; the pill toggles in stock. In
 * stock rows stay put, greyed and struck through (not colour alone).
 */
@Composable
private fun ReviewRowItem(
    row: ReviewRow,
    onInStock: (String, Boolean) -> Unit,
    onRow: (String) -> Unit,
    position: SectionPosition,
) {
    val struck = if (row.inStock) TextDecoration.LineThrough else TextDecoration.None
    val faded = if (row.inStock) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(
        Modifier
            .sectionItem(position)
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button) { onRow(row.key) }
            .semantics(mergeDescendants = true) {}
            .testTag("row-${row.key}")
            .padding(start = 16.dp, end = 12.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (row.check) {
                    Icon(
                        Icons.Rounded.ReportProblem,
                        contentDescription = "Check this line",
                        tint = MaterialTheme.colorScheme.tertiary,
                        modifier = Modifier.size(18.dp),
                    )
                }
                Text(
                    row.name.replaceFirstChar { it.uppercase() },
                    style = MaterialTheme.typography.bodyLarge,
                    color = if (row.check && !row.inStock) MaterialTheme.colorScheme.tertiary else faded,
                    textDecoration = struck,
                )
            }
            row.quantity?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = struck,
                )
            }
        }
        InStockPill(row.inStock) { onInStock(row.key, it) }
    }
}

/** "In stock" as a pill: outlined when off, filled with a tick when on. */
@Composable
private fun InStockPill(
    on: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Surface(
        shape = shape,
        color = if (on) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerHighest,
        contentColor = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (on) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.clip(shape).toggleable(value = on, role = Role.Checkbox, onValueChange = onChange),
    ) {
        Row(
            Modifier.heightIn(min = 40.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (on) Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("In stock", style = MaterialTheme.typography.labelLarge)
        }
    }
}
