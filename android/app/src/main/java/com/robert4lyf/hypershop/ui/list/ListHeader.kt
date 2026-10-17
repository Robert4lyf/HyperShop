package com.robert4lyf.hypershop.ui.list

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/** Callbacks of the list header (Step 5.6b). */
data class HeaderActions(
    val onAdd: (String) -> Unit,
    val suggestions: (String) -> List<String>,
    val onReview: (Long) -> Unit,
    val onSample: () -> Unit,
    /** Null until the share flow lands (Step 5.9). */
    val onPasteLink: (() -> Unit)? = null,
)

/**
 * "Add item": Enter adds and keeps the keyboard up (focus stays in the field).
 * A paste of several lines adds one item per line. Suggestions sit under the
 * field as plain rows, so TalkBack reads them in order.
 */
@Composable
fun AddItemField(
    onAdd: (String) -> Unit,
    suggestions: (String) -> List<String>,
) {
    var text by remember { mutableStateOf("") }
    val submit = {
        if (text.isNotBlank()) onAdd(text)
        text = ""
    }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                // A paste with line breaks is a list: add it straight away.
                if (it.contains('\n') && it.trim().contains('\n')) {
                    onAdd(it)
                    text = ""
                } else {
                    text = it.replace("\n", "")
                }
            },
            label = { Text("Add item") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().testTag("add-item"),
        )
        for (s in suggestions(text)) {
            Text(
                s,
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        onAdd(s)
                        text = ""
                    }.padding(vertical = 10.dp, horizontal = 4.dp)
                    .testTag("suggestion-$s"),
            )
        }
    }
}

/** "2 recipes ready to review". */
@Composable
fun ReadyCard(
    jobs: List<Long>,
    onReview: (Long) -> Unit,
) {
    if (jobs.isEmpty()) return
    val n = jobs.size
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clickable { onReview(jobs.first()) }.testTag("ready-card"),
    ) {
        Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (n == 1) "1 recipe ready to review" else "$n recipes ready to review",
                Modifier.weight(1f),
                style = MaterialTheme.typography.titleMedium,
            )
            Text("Review", color = MaterialTheme.colorScheme.primary)
        }
    }
}

/** First run: the two-step picture and a sample list. */
@Composable
fun EmptyState(
    onSample: () -> Unit,
    onPasteLink: (() -> Unit)?,
) {
    Column(
        Modifier.fillMaxWidth().padding(24.dp).testTag("empty"),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Your list is empty", style = MaterialTheme.typography.titleLarge)
        Text("1. Share a recipe from your browser to HyperShop.", style = MaterialTheme.typography.bodyLarge)
        Text("2. Check the ingredients, then shop aisle by aisle.", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Or type items into the field above.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSample) { Text("Try a sample list") }
            onPasteLink?.let { OutlinedButton(onClick = it) { Text("Paste link") } }
        }
    }
}

@Composable
fun PlusOneDialog(
    p: PlusOne,
    onYes: () -> Unit,
    onNo: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onNo,
        title = { Text("${p.name.replaceFirstChar { it.uppercase() }} is already on the list") },
        text = { Text("Add \"${p.line}\" to it?") },
        confirmButton = { TextButton(onClick = onYes) { Text("+1") } },
        dismissButton = { TextButton(onClick = onNo) { Text("No") } },
    )
}

/** The header block of the list: field, ready card, empty state, "+1?" dialog. */
fun androidx.compose.foundation.lazy.LazyListScope.listHeader(
    ui: ListUi,
    actions: HeaderActions,
) {
    item(key = "add") { AddItemField(actions.onAdd, actions.suggestions) }
    item(key = "ready") { ReadyCard(ui.readyJobs, actions.onReview) }
}
