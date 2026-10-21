package com.robert4lyf.hypershop.ui.list

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.LocalMall
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material.icons.rounded.Undo
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.ui.common.AmountField
import com.robert4lyf.hypershop.ui.common.EdgeDivider
import com.robert4lyf.hypershop.ui.common.InfoBanner
import com.robert4lyf.hypershop.ui.common.PillShape
import com.robert4lyf.hypershop.ui.common.SectionLabel
import com.robert4lyf.hypershop.ui.common.SheetHeader
import com.robert4lyf.hypershop.ui.common.basilChipColors
import com.robert4lyf.hypershop.ui.common.cardLift
import com.robert4lyf.hypershop.ui.common.rememberOpenLink
import com.robert4lyf.hypershop.ui.theme.card
import com.robert4lyf.hypershop.ui.theme.icon

data class ItemSheetActions(
    val onMove: (AisleCode, Boolean) -> Unit,
    /** False when the text has no amount. */
    val onEditQuantity: (String) -> Boolean,
    /** Takes the item off the list (with Undo on the list). */
    val onDelete: () -> Unit = {},
    /** Swap for this name; true = always use it instead from now on. */
    val onSwap: (String, Boolean) -> Unit = { _, _ -> },
    /** "Tick" / "Untick" at the top of the sheet: the same as the row's round tick. */
    val onToggle: () -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemSheetHost(
    sheet: ItemSheet,
    actions: ItemSheetActions,
    onDismiss: () -> Unit,
) {
    // Opens in full, so the fields near the bottom are reachable, and lifts above the keyboard.
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        ItemSheetContent(sheet, actions, Modifier.imePadding())
    }
}

/**
 * The item sheet (PLAN §3.5): tick, totals per dimension, sources, pack note,
 * swaps, quantity, aisle, and "Remove from list" at the end, labelled and
 * away from the tick so neither is hit by mistake.
 */
@Composable
fun ItemSheetContent(
    sheet: ItemSheet,
    actions: ItemSheetActions,
    modifier: Modifier = Modifier,
) {
    Surface(modifier, color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            SheetHeader(sheet.name, sheet.aisle)
            Button(onClick = actions.onToggle, modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp).testTag("toggle-item")) {
                Icon(if (sheet.done) Icons.Rounded.Undo else Icons.Rounded.Check, contentDescription = null, Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text(if (sheet.done) "Mark not bought" else "Mark bought")
            }
            if (sheet.totals.isNotEmpty()) {
                val amount = sheet.totals.joinToString(" + ")
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.onSurfaceVariant)) { append("On the list: ") }
                        withStyle(MaterialTheme.typography.titleLarge.toSpanStyle()) { append(amount) }
                    },
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("totals"),
                )
            }
            sheet.packNote?.let { InfoBanner("Usually sold as $it", Icons.Rounded.LocalMall) }
            if (sheet.sources.isNotEmpty()) Sources(sheet.sources)
            Swaps(sheet, actions.onSwap)
            EdgeDivider()
            AmountField("Change the amount", "Total needed, e.g. 3 or 500 g", "Set", "edit-qty", actions.onEditQuantity)
            ItemAisle(sheet.name, sheet.aisle, actions.onMove)
            EdgeDivider()
            OutlinedButton(
                onClick = actions.onDelete,
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("delete-item"),
            ) {
                Icon(Icons.Rounded.DeleteOutline, contentDescription = null, Modifier.size(20.dp))
                Spacer(Modifier.size(8.dp))
                Text("Remove from list")
            }
        }
    }
}

/**
 * The aisle picker: a chip per aisle with its icon. A tap asks whether to
 * remember it ("Always"), move only this item ("Just this time"), or not move.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ItemAisle(
    name: String,
    current: AisleCode,
    onMove: (AisleCode, Boolean) -> Unit,
) {
    // The aisle asked about survives a rotation (an enum is Serializable, so it saves as is).
    var picked by rememberSaveable { mutableStateOf<AisleCode?>(null) }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Aisle")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (a in AisleCode.entries) {
                FilterChip(
                    selected = a == current,
                    onClick = { if (a != current) picked = a },
                    label = { Text(a.displayName) },
                    leadingIcon = { Icon(a.icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
                    shape = PillShape,
                    colors = basilChipColors(),
                )
            }
        }
    }
    picked?.let { a ->
        AlertDialog(
            onDismissRequest = { picked = null },
            icon = { Icon(a.icon, contentDescription = null) },
            title = { Text("Move ${name.replaceFirstChar { it.uppercase() }} to ${a.displayName}?") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        picked = null
                        onMove(a, false)
                    }) { Text("Just this time") }
                    TextButton(onClick = {
                        picked = null
                        onMove(a, true)
                    }) { Text("Always") }
                }
            },
            dismissButton = { TextButton(onClick = { picked = null }) { Text("Cancel") } },
        )
    }
}

/**
 * "Swap for": the ingredient's usual swaps as chips, and a field for anything
 * else. A chip asks first, offering "Always" (the library then sends new recipe
 * lines straight to the swap) as well as "Just this time".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Swaps(
    sheet: ItemSheet,
    onSwap: (String, Boolean) -> Unit,
) {
    // Both survive a rotation: the swap being asked about and the name typed.
    var asking by rememberSaveable { mutableStateOf<String?>(null) }
    var other by rememberSaveable { mutableStateOf("") }
    val ask = { if (other.isNotBlank()) asking = other.trim() }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Swap for")
        if (sheet.swaps.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in sheet.swaps) {
                    AssistChip(
                        onClick = { asking = s },
                        label = { Text(s.replaceFirstChar { it.uppercase() }) },
                        leadingIcon = {
                            Icon(
                                Icons.Rounded.SwapHoriz,
                                contentDescription = null,
                                Modifier.size(AssistChipDefaults.IconSize),
                            )
                        },
                        modifier = Modifier.testTag("swap-$s"),
                    )
                }
            }
        }
        Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = other,
                onValueChange = { other = it },
                label = { Text(if (sheet.swaps.isEmpty()) "Swap for, e.g. maple syrup" else "Something else") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { ask() }),
                modifier = Modifier.weight(1f).testTag("swap-other"),
            )
            FilledTonalButton(
                onClick = ask,
                enabled = other.isNotBlank(),
                modifier = Modifier.padding(top = 8.dp),
            ) { Text("Swap") }
        }
    }
    asking?.let { to ->
        AlertDialog(
            onDismissRequest = { asking = null },
            title = { Text("Swap ${sheet.name} for $to?") },
            confirmButton = {
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    TextButton(onClick = {
                        asking = null
                        onSwap(to, false)
                    }) { Text("Just this time") }
                    if (sheet.canAlways && to in sheet.swaps) {
                        TextButton(onClick = {
                            asking = null
                            onSwap(to, true)
                        }) { Text("Always") }
                    }
                }
            },
            dismissButton = { TextButton(onClick = { asking = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun Sources(sources: List<ItemSource>) {
    val open = rememberOpenLink()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("From")
        Surface(Modifier.cardLift(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.card) {
            Column {
                sources.forEachIndexed { i, s ->
                    if (i > 0) EdgeDivider(Modifier.padding(horizontal = 16.dp))
                    val url = s.url
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .then(if (url != null) Modifier.clickable { open(url) }.testTag("source-link") else Modifier)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Text(
                            s.title,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (url != null) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f),
                        )
                        if (url != null) {
                            Icon(
                                Icons.AutoMirrored.Rounded.OpenInNew,
                                contentDescription = "Open recipe",
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(18.dp),
                            )
                        }
                        s.amount?.let { Text(it, style = MaterialTheme.typography.titleSmall) }
                    }
                }
            }
        }
    }
}
