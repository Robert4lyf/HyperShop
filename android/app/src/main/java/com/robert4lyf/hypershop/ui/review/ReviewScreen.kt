package com.robert4lyf.hypershop.ui.review

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.invisibleToUser
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.testTag
import androidx.compose.ui.semantics.toggleableState
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.data.bookRef
import com.robert4lyf.hypershop.ui.common.AisleHeading
import com.robert4lyf.hypershop.ui.common.BottomActionBar
import com.robert4lyf.hypershop.ui.common.InfoBanner
import com.robert4lyf.hypershop.ui.common.SectionPosition
import com.robert4lyf.hypershop.ui.common.Tone
import com.robert4lyf.hypershop.ui.common.rememberOpenLink
import com.robert4lyf.hypershop.ui.common.sectionItem
import com.robert4lyf.hypershop.ui.common.sectionPosition
import com.robert4lyf.hypershop.ui.common.siteName
import com.robert4lyf.hypershop.ui.theme.card
import com.robert4lyf.hypershop.ui.theme.style

/** Changes to the recipe itself: its name, its book and page, and an ingredient added by hand. */
data class ReviewEdits(
    val onTitle: (String) -> Unit = {},
    /** A cookbook recipe's book and page. */
    val onBook: (String, String) -> Unit = { _, _ -> },
    val onAddLine: (String) -> Unit = {},
    /** Step AA: the other side of an "A or B" line (the sheet's swap). */
    val onChoose: (Long) -> Unit = {},
    /** Step AC: one reading of a line's amount chosen on its row (a backwards range). */
    val onSetAmount: (Long, String) -> Unit = { _, _ -> },
)

/**
 * Step AA: an "A or B" line as its two options, the one the line reads now selected. Choosing the other is the swap; a
 * second swap goes back. Each is its own button, apart from the row it sits in.
 */
@Composable
internal fun ChoiceButtons(
    choice: Choice,
    onChoose: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(modifier.testTag("choice-${choice.lineId}"), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((label, chosen) in listOf(choice.chosen to true, choice.other to false)) {
            FilterChip(
                selected = chosen,
                onClick = { if (!chosen) onChoose(choice.lineId) },
                label = { Text(label.replaceFirstChar { it.uppercase() }, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                modifier = Modifier.testTag(if (chosen) "chosen-${choice.lineId}" else "other-${choice.lineId}"),
            )
        }
    }
}

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
    edits: ReviewEdits = ReviewEdits(),
    onStopRetry: () -> Unit = {},
    /** X.11: keep the recipe without planning it; only for a new recipe (not an edit, not typed-in items). */
    onSaveForLater: () -> Unit = {},
    /** "Later": back to the tab with the import kept on the ready card; null for an edit, where there is no card. */
    onLater: (() -> Unit)? = null,
) {
    val list = rememberLazyListState()
    val addFocus = remember { FocusRequester() }
    // "Add an ingredient" in the menu: the field is the last item, so scroll it in, then focus it.
    var focusAdd by remember { mutableIntStateOf(0) }
    val itemCount = 1 + (if (ui.notice != null) 1 else 0) + ui.aisles.sumOf { it.second.size + 1 } + 1
    LaunchedEffect(focusAdd) {
        if (focusAdd == 0) return@LaunchedEffect
        list.animateScrollToItem(itemCount - 1)
        runCatching { addFocus.requestFocus() }
    }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize().imePadding()) {
            TopBar(
                onCancel,
                onClearInStock.takeUnless { ui.savedOnly },
                onAddIngredient = { focusAdd++ },
                onLater = onLater,
                onSaveOnly = onSaveForLater.takeIf { !ui.editing && !ui.byHand && !ui.savedOnly && !ui.busy },
            )
            val card = MaterialTheme.colorScheme.card
            LazyColumn(Modifier.weight(1f), state = list, contentPadding = PaddingValues(bottom = 16.dp)) {
                item(key = "hero") { Hero(ui, onServings, onRetryClaude, onStopRetry, edits.onTitle, edits.onBook) }
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
                        AisleHeader(
                            aisle,
                            rows,
                            ui.savedOnly,
                            Modifier.padding(top = 4.dp).sectionItem(sectionPosition(0, count), card),
                        )
                    }
                    itemsIndexed(rows, key = { _, r -> r.key }) { i, r ->
                        ReviewRowItem(
                            r,
                            onInStock.takeUnless { ui.savedOnly },
                            onRow,
                            edits.onChoose,
                            edits.onSetAmount,
                            sectionPosition(i + 1, count),
                            card,
                        )
                    }
                }
                item(key = "add-line") { AddLine(edits.onAddLine, enabled = !ui.busy, focus = addFocus) }
            }
            BottomActionBar {
                // Step Z: one button; "Save recipe only" is in the menu.
                Button(
                    onClick = onAdd,
                    // An edit can be saved with nothing to add (everything now in stock).
                    enabled = (ui.addCount > 0 || ui.editing) && !ui.busy,
                    modifier = Modifier.weight(1f).heightIn(min = 56.dp).testTag("add"),
                ) {
                    Icon(
                        if (ui.editing) Icons.Rounded.Check else Icons.Rounded.Add,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(Modifier.size(8.dp))
                    Text(
                        when {
                            ui.editing -> "Save changes"
                            ui.addCount == 0 && ui.inStockCount > 0 -> "Nothing to add — all at home"
                            ui.addCount == 0 -> "Nothing to add"
                            ui.addCount == 1 -> "Add to list · 1 item"
                            else -> "Add to list · ${ui.addCount} items"
                        },
                        style = MaterialTheme.typography.titleMedium,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun TopBar(
    onCancel: () -> Unit,
    /** Null when there is nothing "at home" to clear (a saved recipe being edited). */
    onClearInStock: (() -> Unit)?,
    onAddIngredient: () -> Unit,
    onLater: (() -> Unit)? = null,
    /** "Save recipe only": keeps the recipe, adds nothing (X.11); null where it does not apply. */
    onSaveOnly: (() -> Unit)? = null,
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        if (onLater != null) {
            // UX review 18: leaving keeps the import on the card, and says so; discarding is a deliberate choice in the menu.
            TextButton(onClick = onLater, modifier = Modifier.testTag("later")) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                // Step AC: says what happens (the recipe waits on the "ready to review" card), not only "Later".
                Text("Review later")
            }
        } else {
            // An edit: Cancel leaves the saved recipe as it was, so it says so in words.
            TextButton(onClick = onCancel) {
                Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                Text("Cancel")
            }
        }
        Spacer(Modifier.weight(1f))
        var menu by rememberSaveable { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menu = true }, modifier = Modifier.testTag("review-more")) {
                Icon(Icons.Rounded.MoreVert, contentDescription = "More")
            }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                onSaveOnly?.let { save ->
                    DropdownMenuItem(text = { Text("Save recipe only") }, onClick = {
                        menu = false
                        save()
                    }, modifier = Modifier.testTag("save-for-later"))
                }
                DropdownMenuItem(text = { Text("Add an ingredient") }, onClick = {
                    menu = false
                    onAddIngredient()
                })
                if (onLater != null) {
                    DropdownMenuItem(text = { Text("Discard import") }, onClick = {
                        menu = false
                        onCancel()
                    }, modifier = Modifier.testTag("discard-import"))
                }
                onClearInStock?.let { clear ->
                    DropdownMenuItem(text = { Text("Clear \"at home\"") }, onClick = {
                        menu = false
                        clear()
                    })
                }
            }
        }
    }
}

/**
 * The recipe, plainly (Step AC: no picture behind it): its title with the pencil, what will happen ("4 to add · 1 at
 * home"), the servings and its site. When Claude failed or was skipped, one line says why, with Retry, and can be put
 * away. Compact, so the ingredients start on the first screen of a small phone.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Hero(
    ui: ReviewUi,
    onServings: (Int) -> Unit,
    onRetryClaude: () -> Unit,
    onStopRetry: () -> Unit,
    onTitle: (String) -> Unit,
    onBook: (String, String) -> Unit,
) {
    var renaming by rememberSaveable { mutableStateOf(false) }
    if (renaming) TitleDialog(ui.title, onDismiss = { renaming = false }) { onTitle(it) }
    var sourcing by rememberSaveable { mutableStateOf(false) }
    if (sourcing) BookDialog(ui.book, ui.page, onDismiss = { sourcing = false }, onSave = onBook)
    val open = rememberOpenLink()
    var noteAway by rememberSaveable(ui.claudeNote) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 0.dp, bottom = 4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f, fill = false).testTag("hero-text")) {
                Text(
                    ui.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier =
                        Modifier.semantics {
                            heading()
                            contentDescription = ui.title
                        },
                )
            }
            if (!ui.byHand) {
                IconButton(onClick = { renaming = true }, modifier = Modifier.testTag("edit-title")) {
                    Icon(Icons.Rounded.Edit, contentDescription = "Rename recipe")
                }
            }
        }
        // Step AD.3: what it adds and the servings on one line, so the ingredients start higher.
        Row(Modifier.padding(end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                ui.summary,
                Modifier.weight(1f).testTag("summary"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (!ui.byHand && !ui.savedOnly) Servings(ui.servings, ui.originalServings, onServings)
        }
        if (ui.cookbook) {
            TextButton(
                onClick = { sourcing = true },
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 4.dp),
                modifier = Modifier.testTag("edit-book"),
            ) {
                Icon(Icons.AutoMirrored.Rounded.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(8.dp))
                Text(bookRef(ui.book, ui.page) ?: "Add book and page", maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        FlowRow(
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            ui.url?.let { url ->
                TextButton(onClick = { open(url) }, modifier = Modifier.align(Alignment.CenterVertically).testTag("open-recipe")) {
                    Text(
                        siteName(url) ?: "Open recipe",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    Spacer(Modifier.size(6.dp))
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = "Open recipe", modifier = Modifier.size(18.dp))
                }
            }
        }
        // Claude's status only when it failed or was skipped: the reason, Retry, and a way to put the line away.
        if (ui.retrying) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CircularProgressIndicator(Modifier.size(20.dp).progressSemantics(), strokeWidth = 2.dp)
                Spacer(Modifier.size(8.dp))
                Text("Reading with Claude…", Modifier.weight(1f), style = MaterialTheme.typography.bodySmall)
                TextButton(onClick = onStopRetry, modifier = Modifier.testTag("stop-retry")) { Text("Stop") }
            }
        } else if (ui.claudeNote != null && !noteAway) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    ui.claudeNote,
                    Modifier.weight(1f).testTag("claude-note"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (ui.canAskClaude) {
                    TextButton(
                        onClick = onRetryClaude,
                        enabled = !ui.busy,
                        modifier = Modifier.semantics { contentDescription = "Retry with Claude" }.testTag("ask-claude"),
                    ) { Text("Retry") }
                }
                IconButton(onClick = { noteAway = true }, modifier = Modifier.testTag("claude-note-close")) {
                    Icon(Icons.Rounded.Close, contentDescription = "Hide this note")
                }
            }
        } else if (ui.busy) {
            CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
        }
    }
}

@Composable
internal fun TitleDialog(
    current: String,
    onDismiss: () -> Unit,
    heading: String = "Rename recipe",
    label: String = "Recipe name",
    onSave: (String) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(current) }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val save = {
        if (text.isNotBlank()) {
            onSave(text)
            onDismiss()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(heading) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text(label) },
                singleLine = true,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("title-field"),
            )
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank(), onClick = save, modifier = Modifier.testTag("title-save")) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** The cookbook a recipe came from: either field may be left empty. */
@Composable
private fun BookDialog(
    book: String?,
    page: String?,
    onDismiss: () -> Unit,
    onSave: (String, String) -> Unit,
) {
    var b by rememberSaveable { mutableStateOf(book.orEmpty()) }
    var p by rememberSaveable { mutableStateOf(page.orEmpty()) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Book and page") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(
                    value = b,
                    onValueChange = { b = it },
                    label = { Text("Book") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Words),
                    modifier = Modifier.fillMaxWidth().testTag("book-field"),
                )
                OutlinedTextField(
                    value = p,
                    onValueChange = { p = it },
                    label = { Text("Page") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("page-field"),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(b, p)
                onDismiss()
            }, modifier = Modifier.testTag("book-save")) { Text("Save") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** "Add an ingredient": typed as a recipe line ("2 tbsp honey"), read like the others. */
@Composable
private fun AddLine(
    onAdd: (String) -> Unit,
    enabled: Boolean,
    focus: FocusRequester,
) {
    var text by rememberSaveable { mutableStateOf("") }
    val submit = {
        if (text.isNotBlank()) {
            onAdd(text)
            text = ""
        }
    }
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 20.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Add an ingredient") },
            placeholder = { Text("e.g. 2 tbsp honey") },
            singleLine = true,
            shape = RoundedCornerShape(50),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { submit() }),
            modifier = Modifier.weight(1f).focusRequester(focus).testTag("add-line"),
        )
        FilledTonalButton(
            onClick = submit,
            enabled = enabled && text.isNotBlank(),
            modifier = Modifier.testTag("add-line-go"),
        ) { Text("Add") }
    }
}

/** − Serves 4 +, with the number tappable to type one; "Serves ?" when the recipe gives none. */
@Composable
private fun Servings(
    s: Int?,
    original: Int?,
    onServings: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    var typing by rememberSaveable { mutableStateOf(false) }
    if (typing) ServingsDialog(s, onDismiss = { typing = false }, onSet = onServings)
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.secondaryContainer,
            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(
                    onClick = { onServings((s ?: 1) - 1) },
                    enabled = s != null && s > 1,
                ) { Icon(Icons.Rounded.Remove, contentDescription = "Fewer servings") }
                Text(
                    if (s == null) "Serves ?" else "Serves $s",
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .clickable(onClickLabel = "Type the servings") { typing = true }
                        .heightIn(min = 48.dp)
                        .wrapContentHeight()
                        .padding(horizontal = 6.dp, vertical = 8.dp)
                        .testTag("servings"),
                    style = MaterialTheme.typography.titleSmall,
                )
                IconButton(onClick = { onServings(if (s == null) 1 else s + 1) }) {
                    Icon(Icons.Rounded.Add, contentDescription = "More servings")
                }
            }
        }
        if (s != null && original != null && s != original) {
            Text("(recipe: $original)", Modifier.testTag("servings-original"), style = MaterialTheme.typography.labelMedium)
        }
    }
}

@Composable
private fun ServingsDialog(
    current: Int?,
    onDismiss: () -> Unit,
    onSet: (Int) -> Unit,
) {
    var text by rememberSaveable { mutableStateOf(current?.toString().orEmpty()) }
    val value = text.trim().toIntOrNull()?.takeIf { it in 1..99 }
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    val save = {
        value?.let {
            onSet(it)
            onDismiss()
        }
        Unit
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Servings") },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it.filter { ch -> ch.isDigit() }.take(2) },
                label = { Text("Serves") },
                supportingText = { Text("1 to 99") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { save() }),
                modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("servings-field"),
            )
        },
        confirmButton = {
            TextButton(enabled = value != null, onClick = save, modifier = Modifier.testTag("servings-save")) { Text("Set") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Step AD.3: the list's own slim aisle row (icon, name, count), in the card's colour, not a tall coloured block. The
 * count is what the aisle adds ("2 to add"); a saved recipe being edited adds nothing, so it counts its lines.
 */
@Composable
private fun AisleHeader(
    aisle: AisleCode,
    rows: List<ReviewRow>,
    savedOnly: Boolean,
    modifier: Modifier,
) {
    // The same rule as the Add button: a row already covered by the list adds nothing.
    val adding = rows.count { !it.inStock && it.adds }
    AisleHeading(
        aisle,
        when {
            savedOnly -> if (rows.size == 1) "1 item" else "${rows.size} items"
            adding > 0 -> "$adding to add"
            rows.all { it.inStock } -> "All at home"
            else -> "Nothing to add"
        },
        modifier.semantics(mergeDescendants = true) { heading() }.testTag("review-aisle-${aisle.name}"),
        // Not tapped here (nothing collapses on the review), so no 48 dp target to keep.
        minHeight = 40.dp,
    )
}

/**
 * Step AA: one row, a leading checkbox and one TalkBack node. Ticked means "add this to the list"; an unticked row is
 * at home (the decision underneath is unchanged: in stock), greyed and saying so. Tapping the row opens its sheet. The
 * box is for the finger only: TalkBack reads the row (its name, its amount, and whether it will be added) and toggles
 * it with the row's own action.
 */
@OptIn(androidx.compose.ui.ExperimentalComposeUiApi::class)
@Composable
private fun ReviewRowItem(
    row: ReviewRow,
    /** Null for a saved recipe being edited: nothing is added, so no box. */
    onInStock: ((String, Boolean) -> Unit)?,
    onRow: (String) -> Unit,
    onChoose: (Long) -> Unit,
    /** Step AC: a reading chosen for a line ("2 cloves"): its amount, as the sheet's field sets it. */
    onAmount: (Long, String) -> Unit,
    position: SectionPosition,
    card: Color,
) {
    val faded = if (row.inStock) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val name = row.name.replaceFirstChar { it.uppercase() }
    Row(
        Modifier
            .sectionItem(position, card)
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .padding(start = 4.dp, end = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onInStock != null) {
            Checkbox(
                checked = !row.inStock,
                onCheckedChange = { add -> onInStock(row.key, !add) },
                modifier =
                    Modifier.clearAndSetSemantics {
                        testTag = "add-${row.key}"
                        toggleableState = ToggleableState(!row.inStock)
                        invisibleToUser()
                    },
            )
        }
        Row(
            Modifier
                .weight(1f)
                .heightIn(min = 56.dp)
                .clickable(role = Role.Button, onClickLabel = "Edit") { onRow(row.key) }
                .semantics(mergeDescendants = true) {
                    if (onInStock != null) {
                        stateDescription = if (row.inStock) "At home, not added" else "Will be added"
                        customActions =
                            listOf(
                                CustomAccessibilityAction(if (row.inStock) "Add to list" else "Mark at home") {
                                    onInStock(row.key, !row.inStock)
                                    true
                                },
                            )
                    }
                }.testTag("row-${row.key}")
                .padding(start = if (onInStock != null) 4.dp else 12.dp, top = 4.dp, bottom = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (row.check) {
                        // Step AC: the icon alone marks the row (decorative: the line below says what was assumed).
                        Icon(
                            Icons.Rounded.ReportProblem,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.tertiary,
                            modifier = Modifier.size(18.dp),
                        )
                    }
                    Text(
                        name,
                        style = MaterialTheme.typography.titleMedium,
                        color = faded,
                    )
                }
                // What this row does: the amount that goes on (and what the list will hold with it), "At home", or the
                // list has it already.
                val sub =
                    when {
                        row.inStock -> listOfNotNull("At home", row.quantity?.let { "needs $it" }).joinToString(" · ")
                        // Step AC: a backwards range says what was taken and what the recipe says; buttons below.
                        row.check && row.range != null -> listOfNotNull(row.quantity, "recipe says ${row.range.said}").joinToString(" · ")
                        !row.adds -> "Already on your shopping list"
                        row.withList != null -> row.withList
                        else -> row.quantity
                    }.let { s -> if (row.optional && !row.inStock) listOfNotNull(s, "optional").joinToString(" · ") else s }
                sub?.let {
                    Text(it, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                row.choice?.takeIf { !row.inStock }?.let { ChoiceButtons(it, onChoose, Modifier.padding(top = 4.dp)) }
                if (row.check && !row.inStock) {
                    val reason = row.checkReason
                    when {
                        // Step AC: what was assumed, and a way to change it on the row; no "Check:", no red text.
                        row.range != null ->
                            Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                for (reading in row.range.readings) {
                                    OutlinedButton(
                                        onClick = { onAmount(row.range.lineId, reading) },
                                        modifier = Modifier.heightIn(min = 48.dp).testTag("reading-${row.key}-$reading"),
                                    ) { Text(reading) }
                                }
                            }
                        ReviewViewModel.noAmount(reason) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "No amount found",
                                    Modifier.weight(1f, fill = false),
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                TextButton(
                                    onClick = { onRow(row.key) },
                                    modifier = Modifier.testTag("set-amount-${row.key}"),
                                ) { Text("Set amount") }
                            }
                        else ->
                            Text(
                                reason?.let { ReviewViewModel.plainReason(it) } ?: "Read with a guess; tap to check it",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                    }
                    // The line as the recipe wrote it, only where it helps choose.
                    row.raw?.takeIf { ReviewViewModel.quoteHelps(reason) }?.let {
                        Text(
                            "“$it”",
                            style = MaterialTheme.typography.bodySmall,
                            fontStyle = FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Icon(
                Icons.Rounded.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.outline,
                modifier = Modifier.size(22.dp),
            )
        }
    }
}

/** Text on the hero's white chips: near-black green, readable on white whatever the recipe's hue. */
