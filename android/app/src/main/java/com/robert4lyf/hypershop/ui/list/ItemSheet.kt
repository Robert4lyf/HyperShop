package com.robert4lyf.hypershop.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.ui.common.rememberOpenLink
import com.robert4lyf.hypershop.ui.review.MoveAisle

data class ItemSheetActions(
    val onMove: (AisleCode, Boolean) -> Unit,
    /** False when the text has no amount. */
    val onEditQuantity: (String) -> Boolean,
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
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(
            Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(sheet.name.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleLarge)
            if (sheet.totals.isNotEmpty()) {
                Text("On the list: ${sheet.totals.joinToString(" + ")}", Modifier.testTag("totals"))
            }
            sheet.packNote?.let { Text("Usually sold as $it", style = MaterialTheme.typography.bodyMedium) }
            if (sheet.sources.isNotEmpty()) {
                Text("From", style = MaterialTheme.typography.labelLarge)
                val open = rememberOpenLink()
                for (s in sheet.sources) {
                    Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        val url = s.url
                        if (url == null) {
                            Text(s.title, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        } else {
                            Text(
                                s.title,
                                style = MaterialTheme.typography.bodyMedium.copy(textDecoration = TextDecoration.Underline),
                                color = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.weight(1f).clickable { open(url) }.testTag("source-link"),
                            )
                        }
                        s.amount?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
                    }
                }
            }
            HorizontalDivider()
            EditQuantity(actions.onEditQuantity)
            MoveAisle(sheet.aisle, actions.onMove)
        }
    }
}

@Composable
private fun EditQuantity(onEdit: (String) -> Boolean) {
    var amount by remember { mutableStateOf("") }
    var bad by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = amount,
            onValueChange = {
                amount = it
                bad = false
            },
            label = { Text("Total needed, e.g. 3 or 500 g") },
            isError = bad,
            supportingText = if (bad) ({ Text("Enter an amount, like 2 or 200 ml") }) else null,
            singleLine = true,
            modifier = Modifier.weight(1f).testTag("edit-qty"),
        )
        TextButton(onClick = { bad = !onEdit(amount) }, enabled = amount.isNotBlank()) { Text("Set") }
    }
}
