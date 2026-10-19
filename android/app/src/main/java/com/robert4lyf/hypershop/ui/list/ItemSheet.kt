package com.robert4lyf.hypershop.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.LocalMall
import androidx.compose.material.icons.rounded.SwapHoriz
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.ui.common.AmountField
import com.robert4lyf.hypershop.ui.common.InfoBanner
import com.robert4lyf.hypershop.ui.common.SectionLabel
import com.robert4lyf.hypershop.ui.common.SheetHeader
import com.robert4lyf.hypershop.ui.common.rememberOpenLink
import com.robert4lyf.hypershop.ui.review.MoveAisle
import com.robert4lyf.hypershop.ui.theme.card

data class ItemSheetActions(
    val onMove: (AisleCode, Boolean) -> Unit,
    /** False when the text has no amount. */
    val onEditQuantity: (String) -> Boolean,
    /** Takes the item off the list (with Undo on the list). */
    val onDelete: () -> Unit = {},
    /** Swap for this name; true = always use it instead from now on. */
    val onSwap: (String, Boolean) -> Unit = { _, _ -> },
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ItemSheetHost(
    sheet: ItemSheet,
    actions: ItemSheetActions,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) { ItemSheetContent(sheet, actions) }
}

/** The item sheet (PLAN §3.5): totals per dimension, sources, pack note, aisle, quantity. */
@Composable
fun ItemSheetContent(
    sheet: ItemSheet,
    actions: ItemSheetActions,
) {
    Surface(color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Row(verticalAlignment = Alignment.Top) {
                Box(Modifier.weight(1f)) { SheetHeader(sheet.name, sheet.aisle) }
                IconButton(onClick = actions.onDelete, modifier = Modifier.testTag("delete-item")) {
                    Icon(
                        Icons.Rounded.DeleteOutline,
                        contentDescription = "Delete ${sheet.name}",
                        tint = MaterialTheme.colorScheme.error,
                    )
                }
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
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            AmountField("Change the amount", "Total needed, e.g. 3 or 500 g", "Set", "edit-qty", actions.onEditQuantity)
            MoveAisle(sheet.aisle, actions.onMove)
        }
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
    var asking by remember { mutableStateOf<String?>(null) }
    var other by remember { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("Swap for")
        if (sheet.swaps.isNotEmpty()) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
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
                modifier = Modifier.weight(1f).testTag("swap-other"),
            )
            FilledTonalButton(
                onClick = { asking = other.trim() },
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
                TextButton(onClick = {
                    asking = null
                    onSwap(to, false)
                }) { Text("Just this time") }
            },
            dismissButton = {
                if (sheet.canAlways && to in sheet.swaps) {
                    TextButton(onClick = {
                        asking = null
                        onSwap(to, true)
                    }) { Text("Always") }
                } else {
                    TextButton(onClick = { asking = null }) { Text("Cancel") }
                }
            },
        )
    }
}

@Composable
private fun Sources(sources: List<ItemSource>) {
    val open = rememberOpenLink()
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        SectionLabel("From")
        Surface(shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.card) {
            Column {
                sources.forEachIndexed { i, s ->
                    if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.outlineVariant)
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
