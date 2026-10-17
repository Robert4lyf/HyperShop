package com.robert4lyf.hypershop.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.unit.dp
import com.robert4lyf.hypershop.core.model.AisleCode

/** Callbacks of the row sheet. */
data class SheetActions(
    val onHaveSome: (String) -> Boolean,
    val onAlwaysInStock: () -> Unit,
    val onSwap: (Long) -> Unit,
    val onMoveAisle: (AisleCode, Boolean) -> Unit,
    val onEditLine: (Long, String) -> Unit,
    val reparse: (String) -> String,
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
                onHaveSome = { vm.haveSome(key, it) },
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
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(sheet.name, style = MaterialTheme.typography.titleLarge)
            Text(sheet.effect, Modifier.testTag("effect"))
            sheet.packNote?.let { Text("Usually sold as $it", style = MaterialTheme.typography.bodyMedium) }
            HorizontalDivider()
            for (line in sheet.lines) key(line.id) { LineEditor(line, actions) }
            HorizontalDivider()
            HaveSome(actions.onHaveSome)
            if (!sheet.isStaple) OutlinedButton(onClick = actions.onAlwaysInStock) { Text("Always in stock") }
            MoveAisle(sheet.aisle, actions.onMoveAisle)
        }
    }
}

@Composable
private fun LineEditor(
    line: SheetLine,
    actions: SheetActions,
) {
    var text by remember(line.id) { mutableStateOf(line.raw) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Source line") },
            modifier = Modifier.fillMaxWidth().testTag("line-${line.id}"),
        )
        Text("Read as: ${if (text == line.raw) line.parsed else actions.reparse(text)}", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (text != line.raw) Button(onClick = { actions.onEditLine(line.id, text) }) { Text("Use this line") }
            line.alternative?.let { alt -> OutlinedButton(onClick = { actions.onSwap(line.id) }) { Text("Use $alt instead") } }
        }
    }
}

@Composable
private fun HaveSome(onHaveSome: (String) -> Boolean) {
    var amount by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = amount,
            onValueChange = {
                amount = it
                bad = false
            },
            label = { Text("I have some, e.g. 200 ml") },
            isError = bad,
            supportingText = if (bad) ({ Text("Enter an amount, like 2 or 200 ml") }) else null,
            modifier = Modifier.weight(1f).testTag("have-some"),
        )
        TextButton(onClick = { bad = !onHaveSome(amount) }, enabled = amount.isNotBlank()) { Text("Subtract") }
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun MoveAisle(
    current: AisleCode,
    onMove: (AisleCode, Boolean) -> Unit,
) {
    var picked by remember { mutableStateOf<AisleCode?>(null) }
    Text("Aisle", style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        for (a in AisleCode.entries) FilterChip(selected = a == current, onClick = {
            if (a != current) picked = a
        }, label = { Text(a.displayName) })
    }
    picked?.let { a ->
        AlertDialog(
            onDismissRequest = { picked = null },
            title = { Text("Always put it in ${a.displayName}?") },
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
