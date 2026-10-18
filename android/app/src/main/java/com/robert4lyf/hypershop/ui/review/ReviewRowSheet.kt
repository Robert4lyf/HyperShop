package com.robert4lyf.hypershop.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.TrendingFlat
import androidx.compose.material.icons.rounded.Inventory
import androidx.compose.material.icons.rounded.LocalMall
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.ui.common.InfoBanner
import com.robert4lyf.hypershop.ui.common.SectionLabel
import com.robert4lyf.hypershop.ui.common.SheetHeader
import com.robert4lyf.hypershop.ui.common.Tone
import com.robert4lyf.hypershop.ui.theme.icon

/** Callbacks of the row sheet. */
data class SheetActions(
    val onAlwaysInStock: () -> Unit,
    val onSwap: (Long) -> Unit,
    val onMoveAisle: (AisleCode, Boolean) -> Unit,
    val onEditLine: (Long, String) -> Unit,
    val reparse: (String) -> String,
    /** A new name, for this recipe only. */
    val onRename: (String) -> Unit = {},
    /** "Remove from recipe": the line goes from this recipe. */
    val onRemoveLine: (Long) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReviewRowSheetHost(
    vm: ReviewViewModel,
    key: String,
    onDismiss: () -> Unit,
) {
    val sheet = vm.sheet(key) ?: return onDismiss()
    ModalBottomSheet(onDismissRequest = onDismiss) {
        ReviewRowSheet(
            sheet,
            SheetActions(
                onAlwaysInStock = {
                    vm.alwaysInStock(key)
                    onDismiss()
                },
                onSwap = {
                    vm.swap(it)
                    onDismiss()
                },
                onMoveAisle = { a, always ->
                    vm.moveAisle(key, a, always)
                    onDismiss()
                },
                onEditLine = { id, t ->
                    vm.editLine(id, t)
                    onDismiss()
                },
                reparse = vm::reparse,
                onRename = {
                    vm.rename(key, it)
                    onDismiss()
                },
                onRemoveLine = {
                    vm.removeLine(it)
                    onDismiss()
                },
            ),
        )
    }
}

/** The sheet's content, stateless apart from its text fields (PLAN §3.1 row detail sheet). */
@Composable
fun ReviewRowSheet(
    sheet: RowSheet,
    actions: SheetActions,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SheetHeader(sheet.name, sheet.aisle)
            InfoBanner(sheet.effect, Icons.AutoMirrored.Rounded.TrendingFlat, Modifier.testTag("effect"), Tone.PRIMARY)
            sheet.packNote?.let { InfoBanner("Usually sold as $it", Icons.Rounded.LocalMall) }
            key(sheet.key) { Rename(sheet.name, actions.onRename) }
            SectionLabel(if (sheet.lines.size == 1) "From the recipe" else "From the recipe (${sheet.lines.size} lines)")
            for (line in sheet.lines) key(line.id) { LineEditor(line, actions) }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (!sheet.isStaple) {
                FilledTonalButton(onClick = actions.onAlwaysInStock) {
                    Icon(Icons.Rounded.Inventory, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Always in stock")
                }
            }
            MoveAisle(sheet.aisle, actions.onMoveAisle)
        }
    }
}

/** The item's name for this recipe only; the library keeps its own. */
@Composable
private fun Rename(
    current: String,
    onRename: (String) -> Unit,
) {
    var text by remember { mutableStateOf(current.replaceFirstChar { it.uppercase() }) }
    val changed = text.isNotBlank() && !text.trim().equals(current, ignoreCase = true)
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Name") },
            singleLine = true,
            shape = RoundedCornerShape(16.dp),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (changed) onRename(text) }),
            modifier = Modifier.fillMaxWidth().testTag("rename"),
        )
        Text(
            "Just for this recipe. To change it everywhere, use Settings › Ingredient library.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        if (changed) Button(onClick = { onRename(text) }, modifier = Modifier.testTag("rename-go")) { Text("Rename") }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LineEditor(
    line: SheetLine,
    actions: SheetActions,
) {
    var text by remember(line.id) { mutableStateOf(line.raw) }
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Source line") },
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth().testTag("line-${line.id}"),
        )
        Text(
            "Read as: ${if (text == line.raw) line.parsed else actions.reparse(text)}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (text != line.raw) Button(onClick = { actions.onEditLine(line.id, text) }) { Text("Use this line") }
            TextButton(onClick = { actions.onRemoveLine(line.id) }, modifier = Modifier.testTag("remove-${line.id}")) {
                Text("Remove from recipe")
            }
            line.alternative?.let { alt ->
                OutlinedButton(onClick = { actions.onSwap(line.id) }) {
                    Icon(Icons.Rounded.SwapHoriz, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Use $alt instead")
                }
            }
        }
    }
}

/** The aisle picker, shared with the list's item sheet: a chip per aisle with its icon. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun MoveAisle(
    current: AisleCode,
    onMove: (AisleCode, Boolean) -> Unit,
) {
    var picked by remember { mutableStateOf<AisleCode?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Aisle")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (a in AisleCode.entries) {
                FilterChip(
                    selected = a == current,
                    onClick = { if (a != current) picked = a },
                    label = { Text(a.displayName) },
                    leadingIcon = { Icon(a.icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
                    shape = RoundedCornerShape(50),
                )
            }
        }
    }
    picked?.let { a ->
        AlertDialog(
            onDismissRequest = { picked = null },
            icon = { Icon(a.icon, contentDescription = null) },
            title = { Text("Always put it in ${a.displayName}?") },
            text = { Text("\"Always\" remembers this for next time. \"Just this time\" moves only this item.") },
            confirmButton = {
                TextButton(onClick = {
                    picked = null
                    onMove(a, true)
                }) { Text("Always") }
            },
            dismissButton = {
                TextButton(onClick = {
                    picked = null
                    onMove(a, false)
                }) { Text("Just this time") }
            },
        )
    }
}
