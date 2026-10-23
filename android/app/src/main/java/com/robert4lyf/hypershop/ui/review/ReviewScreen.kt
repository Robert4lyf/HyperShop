package com.robert4lyf.hypershop.ui.review

import androidx.compose.foundation.BorderStroke
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
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.MenuBook
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.HourglassTop
import androidx.compose.material.icons.rounded.Info
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Remove
import androidx.compose.material.icons.rounded.ReportProblem
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.data.bookRef
import com.robert4lyf.hypershop.ui.common.AisleBadge
import com.robert4lyf.hypershop.ui.common.BottomActionBar
import com.robert4lyf.hypershop.ui.common.InfoBanner
import com.robert4lyf.hypershop.ui.common.SectionPosition
import com.robert4lyf.hypershop.ui.common.Tone
import com.robert4lyf.hypershop.ui.common.rememberOpenLink
import com.robert4lyf.hypershop.ui.common.sectionItem
import com.robert4lyf.hypershop.ui.common.sectionPosition
import com.robert4lyf.hypershop.ui.common.siteName
import com.robert4lyf.hypershop.ui.theme.Brand
import com.robert4lyf.hypershop.ui.theme.card
import com.robert4lyf.hypershop.ui.theme.lowPoly
import com.robert4lyf.hypershop.ui.theme.style

/** Changes to the recipe itself: its name, its book and page, and an ingredient added by hand. */
data class ReviewEdits(
    val onTitle: (String) -> Unit = {},
    /** A cookbook recipe's book and page. */
    val onBook: (String, String) -> Unit = { _, _ -> },
    val onAddLine: (String) -> Unit = {},
)

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
            TopBar(onCancel, onClearInStock.takeUnless { ui.savedOnly }, onAddIngredient = { focusAdd++ })
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
                            onAisleInStock.takeUnless { ui.savedOnly },
                            Modifier.padding(top = 12.dp).sectionItem(sectionPosition(0, count), aisle.style.container),
                        )
                    }
                    itemsIndexed(rows, key = { _, r -> r.key }) { i, r ->
                        ReviewRowItem(r, onInStock.takeUnless { ui.savedOnly }, onRow, sectionPosition(i + 1, count), card)
                    }
                }
                item(key = "add-line") { AddLine(edits.onAddLine, enabled = !ui.busy, focus = addFocus) }
            }
            BottomActionBar {
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
                            ui.addCount == 1 -> "Add 1 item"
                            else -> "Add ${ui.addCount} items"
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
) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        // Cancel discards the import, so it says so in words (system Back keeps it ready to review).
        TextButton(onClick = onCancel) {
            Icon(Icons.Rounded.Close, contentDescription = null, modifier = Modifier.size(18.dp))
            Spacer(Modifier.size(6.dp))
            Text("Cancel")
        }
        Spacer(Modifier.weight(1f))
        var menu by rememberSaveable { mutableStateOf(false) }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Add an ingredient") }, onClick = {
                    menu = false
                    onAddIngredient()
                })
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

/** The recipe: who read it, its title and site, what will happen, and how many it serves. */
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
    var explainOffline by rememberSaveable { mutableStateOf(false) }
    if (explainOffline) {
        AlertDialog(
            onDismissRequest = { explainOffline = false },
            icon = { Icon(Icons.Rounded.CloudOff, contentDescription = null) },
            title = { Text("Parsed offline") },
            text = { Text(ui.claudeNote ?: "Read without Claude.") },
            confirmButton = { TextButton(onClick = { explainOffline = false }) { Text("OK") } },
        )
    }
    var sourcing by rememberSaveable { mutableStateOf(false) }
    if (sourcing) BookDialog(ui.book, ui.page, onDismiss = { sourcing = false }, onSave = onBook)
    val open = rememberOpenLink()
    val onHero = ButtonDefaults.textButtonColors(contentColor = Brand.OnHero)
    CompositionLocalProvider(LocalContentColor provides Brand.OnHero) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 4.dp)
                .clip(RoundedCornerShape(24.dp))
                // The recipe's own mosaic, the same as its card on the Recipes tab.
                .lowPoly(ui.title)
                .padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // Wraps rather than squeezing: the spinner must stay visible on narrow phones.
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                val centred = Modifier.align(Alignment.CenterVertically)
                when (ui.badge) {
                    Badge.CLAUDE -> Badge("Read by Claude", Icons.Rounded.AutoAwesome, centred)
                    Badge.CLAUDE_PENDING -> Badge("Waiting for Claude", Icons.Rounded.HourglassTop, centred)
                    // Typed by hand: there is nothing for Claude to have read.
                    Badge.RULES ->
                        if (!ui.byHand) {
                            Badge(
                                "Read offline",
                                Icons.Rounded.CloudOff,
                                centred,
                                onClick = { explainOffline = true },
                            )
                        }
                }
                // With a key: a second opinion is one tap away, whether Claude could not run or was not needed.
                if (ui.canAskClaude && !ui.retrying) {
                    TextButton(onClick = onRetryClaude, enabled = !ui.busy, colors = onHero, modifier = centred.testTag("ask-claude")) {
                        Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text(if (ui.badge == Badge.CLAUDE_PENDING) "Retry with Claude" else "Ask Claude")
                    }
                }
                if (ui.retrying) {
                    CircularProgressIndicator(centred.size(20.dp), color = Brand.OnHero, strokeWidth = 2.dp)
                    Text("Asking Claude…", centred.progressSemantics(), style = MaterialTheme.typography.labelLarge)
                    TextButton(onClick = onStopRetry, colors = onHero, modifier = centred.testTag("stop-retry")) { Text("Stop") }
                } else if (ui.busy) {
                    CircularProgressIndicator(centred.size(20.dp), color = Brand.OnHero, strokeWidth = 2.dp)
                }
            }
            if (ui.badge != Badge.CLAUDE) {
                ui.claudeNote?.let {
                    Text(it, Modifier.testTag("claude-note"), style = MaterialTheme.typography.bodySmall, color = Brand.OnHero)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f, fill = false)) {
                    Text(
                        ui.title,
                        style = MaterialTheme.typography.headlineMedium,
                        color = Brand.OnHero,
                        modifier = Modifier.semantics { heading() },
                    )
                    if (ui.summary.isNotEmpty()) {
                        Text(ui.summary, Modifier.testTag("summary"), style = MaterialTheme.typography.bodyMedium, color = Brand.OnHero)
                    }
                }
                if (!ui.byHand) {
                    IconButton(onClick = { renaming = true }, modifier = Modifier.testTag("edit-title")) {
                        Icon(Icons.Rounded.Edit, contentDescription = "Rename recipe", tint = Brand.OnHero)
                    }
                }
            }
            if (ui.cookbook) {
                TextButton(
                    onClick = { sourcing = true },
                    colors = onHero,
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
                if (!ui.byHand && !ui.savedOnly) {
                    Servings(
                        ui.servings,
                        ui.originalServings,
                        onServings,
                        Modifier.align(Alignment.CenterVertically),
                    )
                }
                ui.url?.let { url ->
                    TextButton(
                        onClick = { open(url) },
                        modifier = Modifier.align(Alignment.CenterVertically).testTag("open-recipe"),
                        colors = onHero,
                    ) {
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

/** A chip-shaped label. Only a chip with something to do (an explanation) is a button. */
@Composable
private fun Badge(
    text: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
) {
    val content: @Composable () -> Unit = {
        Row(
            Modifier.heightIn(min = 32.dp).padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(text, style = MaterialTheme.typography.labelLarge)
        }
    }
    if (onClick != null) {
        Surface(onClick = onClick, modifier = modifier, shape = RoundedCornerShape(50), color = Color.White, contentColor = HERO_INK) {
            content()
        }
    } else {
        Surface(modifier, shape = RoundedCornerShape(50), color = Color.White, contentColor = HERO_INK) { content() }
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
        Surface(shape = RoundedCornerShape(50), color = Color.White, contentColor = HERO_INK) {
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

@Composable
private fun AisleHeader(
    aisle: AisleCode,
    rows: List<ReviewRow>,
    /** Null for a saved recipe being edited: a count, and no in-stock menu. */
    onAisleInStock: ((AisleCode) -> Unit)?,
    modifier: Modifier,
) {
    var menu by rememberSaveable { mutableStateOf(false) }
    // The same rule as the Add button: a row already covered by the list adds nothing.
    val adding = rows.count { !it.inStock && it.adds }
    Row(
        modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        AisleBadge(aisle, size = 36.dp)
        Text(
            aisle.displayName,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        Surface(
            shape = RoundedCornerShape(50),
            color = MaterialTheme.colorScheme.surfaceContainerLowest,
            contentColor = aisle.style.content,
        ) {
            Text(
                when {
                    onAisleInStock == null -> "${rows.size}"
                    adding > 0 -> "$adding to add"
                    rows.all { it.inStock } -> "All at home"
                    else -> "Nothing to add"
                },
                Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelMedium,
            )
        }
        if (onAisleInStock != null) {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "${aisle.displayName} options") }
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Mark this aisle as at home") }, onClick = {
                        menu = false
                        onAisleInStock(aisle)
                    })
                }
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
    /** Null for a saved recipe being edited: no "At home" pill. */
    onInStock: ((String, Boolean) -> Unit)?,
    onRow: (String) -> Unit,
    position: SectionPosition,
    card: Color,
) {
    val struck = if (row.inStock) TextDecoration.LineThrough else TextDecoration.None
    val faded = if (row.inStock) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    val name = row.name.replaceFirstChar { it.uppercase() }
    Row(
        Modifier
            .sectionItem(position, card)
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClickLabel = "Edit") { onRow(row.key) }
            .semantics(mergeDescendants = true) {}
            .testTag("row-${row.key}")
            .padding(start = 16.dp, end = 4.dp, top = 8.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (row.check) {
                    // Decorative: the "Check: …" line below says it, so the row is not read twice.
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
                    color = if (row.check && !row.inStock) MaterialTheme.colorScheme.tertiary else faded,
                    textDecoration = struck,
                )
            }
            // What this row does: "+200 ml" goes on, "needs 200 ml" is at home, or the list has it already.
            val sub =
                when {
                    row.inStock -> row.quantity?.let { "needs $it" }
                    !row.adds -> "Already on your list"
                    else -> row.quantity?.let { "+$it" }
                }
            sub?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textDecoration = struck,
                )
            }
            if (row.check && !row.inStock) {
                Text(
                    row.checkReason?.let { "Check: ${ReviewViewModel.plainReason(it)}" } ?: "Check this line",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary,
                )
                // The line as the recipe wrote it, so the reading can be judged without opening the sheet.
                row.raw?.let {
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
        onInStock?.let { toggle -> InStockPill(row.inStock, name) { toggle(row.key, it) } }
        Icon(
            Icons.Rounded.ChevronRight,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.outline,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** "At home" (you already have it): a pill, outlined when off, filled with a tick when on. A 48 dp target either way. */
@Composable
private fun InStockPill(
    on: Boolean,
    name: String,
    onChange: (Boolean) -> Unit,
) {
    val shape = RoundedCornerShape(50)
    Surface(
        shape = shape,
        color = if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
        contentColor = if (on) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
        border = if (on) null else BorderStroke(1.dp, MaterialTheme.colorScheme.outline),
        modifier =
            Modifier
                .minimumInteractiveComponentSize()
                .clip(shape)
                .toggleable(value = on, role = Role.Checkbox, onValueChange = onChange)
                .semantics {
                    contentDescription = "At home: $name"
                    stateDescription = if (on) "Already at home" else "Need to buy"
                },
    ) {
        Row(
            Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (on) Icon(Icons.Rounded.Check, contentDescription = null, modifier = Modifier.size(18.dp))
            Text("At home", style = MaterialTheme.typography.labelLarge)
        }
    }
}

/** Text on the hero's white chips: near-black green, readable on white whatever the recipe's hue. */
private val HERO_INK = Color(0xFF12301F)
