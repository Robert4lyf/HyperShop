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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.robert4lyf.hypershop.ui.common.EdgeDivider
import com.robert4lyf.hypershop.ui.common.EmptyHero
import com.robert4lyf.hypershop.ui.common.PillShape
import com.robert4lyf.hypershop.ui.common.cardLift
import com.robert4lyf.hypershop.ui.common.fieldLift
import com.robert4lyf.hypershop.ui.common.pillFieldColors
import com.robert4lyf.hypershop.ui.theme.Brand
import com.robert4lyf.hypershop.ui.theme.card
import com.robert4lyf.hypershop.ui.theme.heroGlow

/** Callbacks of the list header (Step 5.6b). */
data class HeaderActions(
    val onAdd: (String) -> Unit,
    val suggestions: (String) -> List<String>,
    val onReview: (Long) -> Unit,
    val onSample: () -> Unit,
    /** A suggestion tapped: what was typed (it may start with an amount) and the suggestion. */
    val onPick: (String, String) -> Unit = { _, s -> onAdd(s) },
    /** "Paste a link" on the empty state: imports the link on the clipboard. */
    val onPasteLink: () -> Unit = {},
)

/**
 * "Add item": Enter adds and keeps the keyboard up (focus stays in the field).
 * A paste of several lines adds one item per line. Suggestions sit under the
 * field as plain rows, so TalkBack reads them in order. The draft survives a
 * tab switch and rotation.
 */
@Composable
fun AddItemField(
    onAdd: (String) -> Unit,
    suggestions: (String) -> List<String>,
    onPick: (String, String) -> Unit = { _, s -> onAdd(s) },
) {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = {
        if (text.isNotBlank()) onAdd(text)
        text = ""
    }
    // Filtered once per keystroke, not on every recomposition.
    val shown = remember(text) { suggestions(text) }
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
            // A white pill with a hairline edge, lifted off the page like a card.
            shape = PillShape,
            colors = pillFieldColors(),
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.fillMaxWidth().fieldLift().testTag("add-item"),
        )
        if (shown.isNotEmpty()) {
            Surface(
                Modifier.fillMaxWidth().padding(top = 8.dp).cardLift(),
                shape = MaterialTheme.shapes.large,
                color = MaterialTheme.colorScheme.card,
            ) {
                Column {
                    shown.forEachIndexed { i, s ->
                        if (i > 0) EdgeDivider(Modifier.padding(start = 52.dp))
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable {
                                    onPick(text, s)
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

/** "2 recipes ready to review": one button; with several waiting, it opens the oldest and the card says so. */
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
            .padding(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 8.dp)
            .cardLift(MaterialTheme.shapes.large)
            .clip(MaterialTheme.shapes.large)
            .clickable(onClickLabel = if (n == 1) "Review" else "Review the first", role = Role.Button) { onReview(jobs.first()) }
            .testTag("ready-card"),
        shape = MaterialTheme.shapes.large,
        color = Color.Transparent,
        contentColor = Brand.OnHero,
    ) {
        Row(
            Modifier.background(Brand.WarmCall).heroGlow().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Box(
                Modifier.size(44.dp).clip(CircleShape).background(Color.White),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.AutoMirrored.Rounded.MenuBook, contentDescription = null, tint = Color(0xFFB4471F))
            }
            Column(Modifier.weight(1f)) {
                Text(
                    if (n == 1) "1 recipe ready to review" else "$n recipes ready to review",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            Icon(Icons.Rounded.ChevronRight, contentDescription = null)
        }
    }
}

/** First run: an empty list, a sample to try, and a paste of a copied link. */
@Composable
fun EmptyState(
    onSample: () -> Unit,
    onPasteLink: () -> Unit = {},
) {
    EmptyHero(
        title = "Your list is empty",
        modifier = Modifier.padding(top = 8.dp).testTag("empty"),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onSample) { Text("Try a sample list") }
            OutlinedButton(onClick = onPasteLink, modifier = Modifier.testTag("paste-link")) {
                Icon(Icons.Rounded.ContentPaste, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Paste a link")
            }
        }
    }
}

/** "Onion is already on the list": the button says what goes on ("Add 200 g", or "Add" for one more). */
@Composable
fun PlusOneDialog(
    p: PlusOne,
    onYes: () -> Unit,
    onNo: () -> Unit,
) {
    val amount = p.amount
    AlertDialog(
        onDismissRequest = onNo,
        icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
        title = { Text("${p.name.replaceFirstChar { it.uppercase() }} is already on the list") },
        text = { Text(if (amount == null) "Add one more ${p.name} to it?" else "Add $amount more ${p.name} to it?") },
        confirmButton = { TextButton(onClick = onYes) { Text(if (amount == null) "Add" else "Add $amount") } },
        dismissButton = { TextButton(onClick = onNo) { Text("No") } },
    )
}

/** The header block of the list: field, ready card, empty state, "+1?" dialog. */
fun androidx.compose.foundation.lazy.LazyListScope.listHeader(
    ui: ListUi,
    actions: HeaderActions,
) {
    item(key = "add") { AddItemField(actions.onAdd, actions.suggestions, actions.onPick) }
    item(key = "ready") { ReadyCard(ui.readyJobs, actions.onReview) }
}
