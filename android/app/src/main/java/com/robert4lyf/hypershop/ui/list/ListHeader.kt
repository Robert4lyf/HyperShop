package com.robert4lyf.hypershop.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.IosShare
import androidx.compose.material.icons.rounded.ShoppingBasket
import androidx.compose.material.icons.rounded.Storefront
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.robert4lyf.hypershop.ui.common.EmptyHero
import com.robert4lyf.hypershop.ui.theme.card

/** Callbacks of the list header (Step 5.6b). */
data class HeaderActions(
    val onAdd: (String) -> Unit,
    val suggestions: (String) -> List<String>,
    val onReview: (Long) -> Unit,
    val onSample: () -> Unit,
    /** Null unless the clipboard says it holds text (its content is read only on tap). */
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
    val shown = suggestions(text)
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
            placeholder = { Text("e.g. 2 lemons") },
            leadingIcon = { Icon(Icons.Rounded.Add, contentDescription = null) },
            trailingIcon =
                if (text.isNotBlank()) {
                    { TextButton(onClick = submit) { Text("Add") } }
                } else {
                    null
                },
            singleLine = true,
            shape = RoundedCornerShape(50),
            colors =
                OutlinedTextFieldDefaults.colors(
                    unfocusedContainerColor = MaterialTheme.colorScheme.card,
                    focusedContainerColor = MaterialTheme.colorScheme.card,
                    unfocusedBorderColor = MaterialTheme.colorScheme.outline,
                ),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().testTag("add-item"),
        )
        if (shown.isNotEmpty()) {
            Surface(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.card,
            ) {
                Column {
                    shown.forEachIndexed { i, s ->
                        if (i > 0) HorizontalDivider(Modifier.padding(start = 52.dp), color = MaterialTheme.colorScheme.outlineVariant)
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onAdd(s)
                                    text = ""
                                }.padding(horizontal = 16.dp, vertical = 14.dp)
                                .testTag("suggestion-$s"),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(16.dp),
                        ) {
                            Icon(
                                Icons.Rounded.History,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(20.dp),
                            )
                            Text(s, style = MaterialTheme.typography.bodyLarge)
                        }
                    }
                }
            }
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
    Surface(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .clip(MaterialTheme.shapes.large)
            .clickable { onReview(jobs.first()) }
            .testTag("ready-card"),
        shape = MaterialTheme.shapes.large,
        color = MaterialTheme.colorScheme.primaryContainer,
        contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
    ) {
        Row(
            Modifier.padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Rounded.MenuBook, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (n == 1) "1 recipe ready to review" else "$n recipes ready to review",
                    style = MaterialTheme.typography.titleMedium,
                )
                Text("Check the ingredients before they go on the list", style = MaterialTheme.typography.bodySmall)
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null)
        }
    }
}

/** First run: what the app does, in two steps, and a sample list to try. */
@Composable
fun EmptyState(
    onSample: () -> Unit,
    onPasteLink: (() -> Unit)?,
) {
    EmptyHero(
        icon = Icons.Rounded.ShoppingBasket,
        title = "Your list is empty",
        modifier = Modifier.padding(top = 8.dp).testTag("empty"),
    ) {
        Spacer(Modifier.size(4.dp))
        Step(Icons.Rounded.IosShare, "Share a recipe", "From your browser, share any recipe page to HyperShop.")
        Step(Icons.Rounded.Storefront, "Shop aisle by aisle", "Check the ingredients, then tick them off as you go.")
        Text(
            "Or type items into the field above.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Button(onClick = onSample) { Text("Try a sample list") }
            onPasteLink?.let {
                OutlinedButton(onClick = it) {
                    Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.size(8.dp))
                    Text("Paste link")
                }
            }
        }
    }
}

@Composable
private fun Step(
    icon: ImageVector,
    title: String,
    body: String,
) {
    Surface(Modifier.fillMaxWidth(), shape = MaterialTheme.shapes.large, color = MaterialTheme.colorScheme.card) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Box(
                Modifier.size(40.dp).clip(CircleShape).background(MaterialTheme.colorScheme.secondaryContainer),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onSecondaryContainer,
                    modifier = Modifier.size(22.dp),
                )
            }
            Column {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
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
        icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
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
    actions.onPasteLink?.let { paste ->
        if (!ui.isEmpty) {
            item(key = "paste") {
                AssistChip(
                    onClick = paste,
                    label = { Text("Paste link") },
                    leadingIcon = {
                        Icon(
                            Icons.Rounded.ContentPaste,
                            contentDescription = null,
                            Modifier.size(AssistChipDefaults.IconSize),
                        )
                    },
                    shape = RoundedCornerShape(50),
                    modifier = Modifier.padding(start = 16.dp).testTag("paste-chip"),
                )
            }
        }
    }
    item(key = "ready") { ReadyCard(ui.readyJobs, actions.onReview) }
}
