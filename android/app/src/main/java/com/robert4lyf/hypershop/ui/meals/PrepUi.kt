package com.robert4lyf.hypershop.ui.meals

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import com.robert4lyf.hypershop.data.BuyAgainView
import com.robert4lyf.hypershop.data.HomeRow
import com.robert4lyf.hypershop.data.Regular
import com.robert4lyf.hypershop.ui.common.PillShape
import com.robert4lyf.hypershop.ui.common.SectionLabel
import com.robert4lyf.hypershop.ui.common.label
import com.robert4lyf.hypershop.ui.theme.card

/** Step Z: what the Buy again sheet and the Food at home screen show (the Meals screen and its state went). */
data class PrepUi(
    val loaded: Boolean = false,
    val buyAgain: BuyAgainView = BuyAgainView(),
    val home: List<HomeRow> = emptyList(),
    /** Rows on the list not yet bought or set aside: what a shop would still buy. */
    val listLeft: Int = 0,
) {
    /** Buy again chips not on the list yet: what "Add all" adds. */
    val toAdd: List<Regular> get() = buyAgain.regulars.filter { !it.onList }

    /** At home rows still to buy: what "I have all of these" moves. */
    val toCheck: List<HomeRow> get() = home.filter { !it.have }
}

/** What Buy again and Food at home can do. */
data class PrepActions(
    val onRegular: (Regular) -> Unit = {},
    val onAllRegulars: () -> Unit = {},
    val onRepeatLastShop: () -> Unit = {},
    val onPin: (Regular) -> Unit = {},
    val onHide: (Regular) -> Unit = {},
    val onHave: (List<HomeRow>) -> Unit = {},
    val onNeed: (HomeRow) -> Unit = {},
    /** Y.19: some of it at home ("300 g"), and taking that back. */
    val onHaveSome: (HomeRow, String, (String?) -> Unit) -> Unit = { _, _, done -> done(null) },
    val onClearSome: (HomeRow) -> Unit = {},
)

/**
 * Buy again: one tap adds a chip, a chip already on the list says so and does
 * nothing; a long press pins or hides it. "Add all" and "Repeat last shop" are
 * the two quick ways through.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun BuyAgainSection(
    ui: PrepUi,
    actions: PrepActions,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(top = 12.dp).testTag("buy-again")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SectionLabel("Buy again", Modifier.weight(1f), icon = Icons.Rounded.History)
            if (ui.toAdd.size > 1) {
                TextButton(onClick = actions.onAllRegulars, modifier = Modifier.testTag("buy-again-all")) {
                    Text("Add all ${ui.toAdd.size}")
                }
            }
        }
        if (ui.buyAgain.regulars.isNotEmpty()) {
            Text(
                "Hold one to pin it or hide it.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (r in ui.buyAgain.regulars) RegularChip(r, actions)
            }
        }
        if (ui.buyAgain.lastShop.isNotEmpty()) {
            TextButton(
                onClick = actions.onRepeatLastShop,
                modifier = Modifier.padding(top = 4.dp).testTag("repeat-last-shop"),
                contentPadding = PaddingValues(horizontal = 0.dp),
            ) {
                Icon(Icons.Rounded.Replay, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(6.dp))
                val names = ui.buyAgain.lastShop.take(3).joinToString(", ") { it.name }
                Text("Add the rest of last shop: $names" + more(ui.buyAgain.lastShop.size))
            }
        }
    }
}

private fun more(n: Int) = if (n > 3) " +${n - 3}" else ""

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun RegularChip(
    r: Regular,
    actions: PrepActions,
) {
    var menu by remember { mutableStateOf(false) }
    Box {
        Row(
            Modifier
                .heightIn(min = 48.dp)
                .clip(PillShape)
                .background(if (r.onList) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.card)
                .combinedClickable(
                    role = Role.Button,
                    onClickLabel = if (r.onList) null else "Add to the shopping list",
                    onLongClickLabel = "Pin or hide",
                    onLongClick = { menu = true },
                    onClick = { if (!r.onList) actions.onRegular(r) },
                )
                .semantics { stateDescription = if (r.onList) "On the shopping list" else "Not on the shopping list" }
                .padding(horizontal = 14.dp, vertical = 8.dp)
                .testTag("regular-${r.mergeKey}"),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Icon(
                if (r.onList) Icons.Rounded.Check else Icons.Rounded.Add,
                contentDescription = null,
                modifier = Modifier.size(16.dp),
                tint = if (r.onList) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.primary,
            )
            Text(
                r.name.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelLarge,
                color = if (r.onList) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            )
        }
        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
            if (!r.pinned) {
                DropdownMenuItem(text = { Text("Always offer") }, onClick = {
                    menu = false
                    actions.onPin(r)
                })
            }
            DropdownMenuItem(text = { Text("Don't suggest") }, onClick = {
                menu = false
                actions.onHide(r)
            })
        }
    }
}

/** Y.19: how much of [r] is in the cupboard, against what the week's recipes need together. */
@Composable
internal fun HaveSomeDialog(
    r: HomeRow,
    onSet: (String, (String?) -> Unit) -> Unit,
    onDismiss: () -> Unit,
) {
    var text by rememberSaveable(r.mergeKey) {
        mutableStateOf(
            r.exactOnHand?.let(com.robert4lyf.hypershop.ui.list.ItemEdit::text) ?: r.onHand.orEmpty(),
        )
    }
    var invalid by rememberSaveable(r.mergeKey) { mutableStateOf(false) }
    var failure by rememberSaveable(r.mergeKey) { mutableStateOf<String?>(null) }
    var submitting by remember(r.mergeKey) { mutableStateOf(false) }
    val error = failure ?: "Enter a positive amount using the requirement's measure, e.g. 300 g.".takeIf { invalid }
    AlertDialog(
        onDismissRequest = { if (!submitting) onDismiss() },
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("home-some-dialog"),
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("How much ${r.name} is at home?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                r.needed?.let { Text("The recipes need $it together. Only the rest goes on the shopping list.") }
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        invalid = false
                        failure = null
                    },
                    enabled = !submitting,
                    label = { Text("Amount at home") },
                    placeholder = { Text("e.g. 300 g or 1/2 kg") },
                    isError = error != null,
                    supportingText = error?.let { message -> ({ Text(message) }) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag("home-some-amount"),
                )
                // What it will mean, before Set: "300 g at home → buy 200 g". Only for one measure, worked exactly.
                if (error == null) {
                    buyingPreview(text, r.needed)?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.testTag("home-some-preview"),
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(enabled = text.isNotBlank() && !submitting, onClick = {
                val entered = com.robert4lyf.hypershop.core.parse.QuantityInput.parse(text)
                val required = r.needed?.let(com.robert4lyf.hypershop.core.parse.QuantityInput::parse)
                invalid = entered == null || (required != null && entered.keys.any { it !in required.keys })
                if (!invalid) {
                    submitting = true
                    failure = null
                    onSet(text) { message ->
                        submitting = false
                        if (message == null) onDismiss() else failure = message
                    }
                }
            }, modifier = Modifier.testTag("home-some-set")) { Text(if (submitting) "Saving…" else "Set") }
        },
        dismissButton = { TextButton(enabled = !submitting, onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * "300 g at home → buy 200 g": what typing [typed] against [needed] leaves to buy, when both are one amount in the same
 * measure. Null otherwise (nothing typed, an unreadable amount, or measures that do not compare), and no guess is made.
 */
internal fun buyingPreview(
    typed: String,
    needed: String?,
): String? {
    if (typed.isBlank() || needed == null) return null
    val have = com.robert4lyf.hypershop.core.parse.QuantityInput.parse(typed) ?: return null
    val need = com.robert4lyf.hypershop.core.parse.QuantityInput.parse(needed) ?: return null
    val (key, n) = need.parts.entries.singleOrNull() ?: return null
    val h = have.parts.entries.singleOrNull()?.takeIf { it.key == key }?.value ?: return null
    val left = n - h
    return if (left <= com.robert4lyf.hypershop.core.model.Rational.ZERO) {
        "${typed.trim()} at home → nothing to buy"
    } else {
        "${typed.trim()} at home → buy ${com.robert4lyf.hypershop.ui.list.ItemEdit.text(
            com.robert4lyf.hypershop.core.model.QtyBase(mapOf(key to left)),
        ).ifEmpty { left.toString() }}"
    }
}

/** One ingredient, one decision: "Have" or "Need", however many recipes want it. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun HomeRowItem(
    r: HomeRow,
    actions: PrepActions,
) {
    val focus = androidx.compose.ui.platform.LocalFocusManager.current
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.card)
            .heightIn(min = 56.dp)
            .padding(start = 14.dp, end = 6.dp, top = 6.dp, bottom = 6.dp)
            .testTag("home-${r.mergeKey}"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        // One TalkBack node for the name and what it is for; the button stays its own.
        Column(Modifier.semantics(mergeDescendants = true) {}) {
            Text(r.name.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.titleSmall)
            // Step Q: the week's combined need, once per ingredient, then what that means for the list.
            val forWhat = if (r.from.size == 1) r.from.single() else "${r.from.size} meals"
            Text(
                listOfNotNull(r.needed?.let { "Need $it" } ?: "Needed", "for $forWhat").joinToString(" "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            val outcome =
                when {
                    r.have -> "At home · not on your list"
                    r.onHand != null -> listOfNotNull("${r.onHand} at home", r.remaining?.let { "buy $it" }).joinToString(" → ")
                    r.usual -> "Usually at home"
                    else -> null
                }
            outcome?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.testTag("home-outcome-${r.mergeKey}"),
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (r.have) {
                TextButton(onClick = { actions.onNeed(r) }, modifier = Modifier.testTag("home-need-${r.mergeKey}")) { Text("Need it") }
            } else {
                var asking by rememberSaveable(r.mergeKey) { mutableStateOf(false) }
                if (r.needed != null) {
                    TextButton(onClick = {
                        focus.clearFocus()
                        asking = true
                    }, modifier = Modifier.testTag("home-some-${r.mergeKey}")) {
                        Text(if (r.onHand != null) "Change amount" else "Have some")
                    }
                }
                if (r.onHand != null) {
                    TextButton(
                        onClick = { actions.onClearSome(r) },
                        modifier = Modifier.testTag("home-clear-${r.mergeKey}"),
                    ) { Text("Need it") }
                }
                OutlinedButton(onClick = { actions.onHave(listOf(r)) }, modifier = Modifier.testTag("home-have-${r.mergeKey}")) {
                    Text("Have enough")
                }
                if (asking) {
                    HaveSomeDialog(
                        r,
                        onSet = { text, done -> actions.onHaveSome(r, text, done) },
                        onDismiss = { asking = false },
                    )
                }
            }
        }
    }
}

private fun plural(
    n: Int,
    word: String,
) = if (n == 1) "1 $word" else "$n ${word}s"
