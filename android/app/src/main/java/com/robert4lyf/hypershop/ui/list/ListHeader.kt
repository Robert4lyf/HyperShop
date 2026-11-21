package com.robert4lyf.hypershop.ui.list

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.robert4lyf.hypershop.data.Regular
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
    /** The empty list's "Add last shop's items", and the Add field's "Last shop's N items" chip. */
    val onLastShop: () -> Unit = {},
    /** Step AB: a usual item's chip under the focused, empty Add field: on the list at its usual amount. */
    val onUsual: (Regular) -> Unit = {},
    /** Step AB: "Don't suggest" on a usual item's chip (Buy again's rule: never suggested again). */
    val onDontSuggest: (Regular) -> Unit = {},
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
    /** Above zero to focus the field from outside (the widget's or launcher's Add item, missing 7 of the review). */
    focus: Int = 0,
    /** The request is used up: told once the field has asked for focus, so it is not asked again later. */
    onFocused: () -> Unit = {},
    /** Step AB: chips while the field is focused and empty. */
    usual: UsualItems = UsualItems(),
    onUsual: (Regular) -> Unit = {},
    onLastShop: () -> Unit = {},
    onDontSuggest: (Regular) -> Unit = {},
) {
    var text by rememberSaveable { mutableStateOf("") }
    var focused by remember { mutableStateOf(false) }
    // A chip's menu is a window of its own and takes focus while it is open: the chips stay until it closes.
    var menuOpen by remember { mutableStateOf(false) }
    val requester = remember { FocusRequester() }
    // One request, one focus: a field composed again later (scrolled back, the tab revisited) does not refocus.
    LaunchedEffect(focus) {
        if (focus > 0) {
            runCatching { requester.requestFocus() }
            onFocused()
        }
    }
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
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusRequester(requester)
                    .onFocusChanged { focused = it.isFocused }
                    .fieldLift()
                    .testTag("add-item"),
        )
        if (text.isEmpty() && (focused || menuOpen) && !usual.isEmpty) {
            UsualChips(
                usual,
                onUsual = onUsual,
                onLastShop = onLastShop,
                onDontSuggest = onDontSuggest,
                onMenu = { open ->
                    menuOpen = open
                    // Back to the field, so the next chip or a typed item follows on.
                    if (!open) runCatching { requester.requestFocus() }
                },
            )
        }
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

/**
 * Step AB: what the focused, empty Add field offers: the items added by hand most often that are not on the list (Buy
 * again's regulars), and last shop's hand-added items not on the list. Eight chips at most, the last shop's last.
 */
data class UsualItems(
    val regulars: List<Regular> = emptyList(),
    /** Last shop's hand-added items not on the list. */
    val lastShop: Int = 0,
) {
    val isEmpty: Boolean get() = regulars.isEmpty() && lastShop == 0

    /** The regulars shown: room is kept for the last shop's chip. */
    val shown: List<Regular> get() = regulars.take(if (lastShop > 0) MAX_CHIPS - 1 else MAX_CHIPS)

    companion object {
        const val MAX_CHIPS = 8
    }
}

/**
 * Step AB: the usual items as chips under the Add field. A tap adds one at its usual amount and the field keeps focus
 * (a chip never takes it); the next candidate takes its place as the list changes. A long press, or TalkBack's action,
 * offers "Don't suggest".
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun UsualChips(
    usual: UsualItems,
    onUsual: (Regular) -> Unit,
    onLastShop: () -> Unit,
    onDontSuggest: (Regular) -> Unit,
    onMenu: (Boolean) -> Unit,
) {
    FlowRow(
        Modifier.fillMaxWidth().padding(top = 8.dp).testTag("usual-chips"),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (r in usual.shown) key(r.mergeKey) { UsualChip(r, onUsual, onDontSuggest, onMenu) }
        if (usual.lastShop > 0) {
            ChipShape(
                label = "Last shop's ${usual.lastShop} items",
                icon = Icons.Rounded.Replay,
                onClick = onLastShop,
                modifier = Modifier.testTag("usual-last-shop"),
            )
        }
    }
}

@Composable
private fun UsualChip(
    r: Regular,
    onUsual: (Regular) -> Unit,
    onDontSuggest: (Regular) -> Unit,
    onMenu: (Boolean) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val name = r.name.replaceFirstChar { it.uppercase() }
    Box {
        ChipShape(
            label = name,
            icon = Icons.Rounded.Add,
            onClick = { onUsual(r) },
            onLongClick = {
                menu = true
                onMenu(true)
            },
            modifier =
                Modifier
                    .semantics {
                        customActions =
                            listOf(
                                CustomAccessibilityAction("Don't suggest") {
                                    onDontSuggest(r)
                                    true
                                },
                            )
                    }.testTag("usual-${r.mergeKey}"),
        )
        DropdownMenu(expanded = menu, onDismissRequest = {
            menu = false
            onMenu(false)
        }) {
            DropdownMenuItem(
                text = { Text("Don't suggest") },
                onClick = {
                    menu = false
                    onMenu(false)
                    onDontSuggest(r)
                },
                modifier = Modifier.testTag("dont-suggest"),
            )
        }
    }
}

/** A pill chip that never takes focus from the Add field. */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ChipShape(
    label: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null,
) {
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(PillShape)
            .background(MaterialTheme.colorScheme.card)
            .focusProperties { canFocus = false }
            .combinedClickable(
                role = Role.Button,
                onClickLabel = "Add to the shopping list",
                onLongClickLabel = if (onLongClick != null) "More options" else null,
                onLongClick = onLongClick,
                onClick = onClick,
            ).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp), tint = MaterialTheme.colorScheme.primary)
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurface)
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

/**
 * An empty list. The first time: a sample to try, and a paste of a copied link.
 * Someone who has shopped before can put last shop's household items straight back on.
 */
@Composable
fun EmptyState(
    onSample: () -> Unit,
    onPasteLink: () -> Unit = {},
    prep: PlanPrep = PlanPrep(),
    onLastShop: () -> Unit = {},
) {
    if (prep.returning) {
        EmptyHero(
            title = "Nothing on the list",
            body = "Type an item above or share a recipe link.",
            modifier = Modifier.padding(top = 8.dp).testTag("empty"),
        ) {
            if (prep.lastShop > 0) {
                OutlinedButton(onClick = onLastShop, modifier = Modifier.testTag("empty-last-shop")) {
                    Text("Last shop's ${prep.lastShop} items")
                }
            }
        }
        return
    }
    EmptyHero(
        title = "Your list is empty",
        body = "Type an item above or share a recipe link.",
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

/** The header block of the list: field, ready card, the week's plan card, empty state. */
fun androidx.compose.foundation.lazy.LazyListScope.listHeader(
    ui: ListUi,
    actions: HeaderActions,
) {
    item(key = "ready") { ReadyCard(ui.readyJobs, actions.onReview) }
}

/** Step AA: the Add item field, pinned under the list's header (it no longer scrolls away with the items). */
@Composable
fun ListAddField(
    actions: HeaderActions,
    /** Above zero to focus the Add field (the widget's or launcher's Add item). */
    focusAdd: Int = 0,
    onAddFocused: () -> Unit = {},
    usual: UsualItems = UsualItems(),
) = AddItemField(
    actions.onAdd,
    actions.suggestions,
    actions.onPick,
    focus = focusAdd,
    onFocused = onAddFocused,
    usual = usual,
    onUsual = actions.onUsual,
    onLastShop = actions.onLastShop,
    onDontSuggest = actions.onDontSuggest,
)
