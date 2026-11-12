package com.robert4lyf.hypershop.ui.list

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.ui.common.PillShape
import com.robert4lyf.hypershop.ui.common.SectionLabel
import com.robert4lyf.hypershop.ui.common.basilChipColors
import com.robert4lyf.hypershop.ui.theme.icon

/**
 * The item sheet's edit section: name, amount and unit, aisle and category, all
 * on one form with one Save, so a row is put right in one go rather than one
 * field and one dialog at a time. What is not touched is left as it is.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ItemEditor(
    sheet: ItemSheet,
    onSave: (ItemChanges) -> Boolean,
    modifier: Modifier = Modifier,
    onDirty: (Boolean) -> Unit = {},
    /** A new value drops the draft: the editor starts again from [sheet] (edits discarded on the sheet). */
    draft: Int = 0,
    details: @Composable () -> Unit = {},
) {
    // Keyed on the item: another row's sheet starts from that row. Survives a rotation.
    var name by rememberSaveable(sheet.id, draft) { mutableStateOf(sheet.name) }
    var amount by rememberSaveable(sheet.id, draft) { mutableStateOf(sheet.amount) }
    var aisle by rememberSaveable(sheet.id, draft) { mutableStateOf(sheet.aisle) }
    var always by rememberSaveable(sheet.id, draft) { mutableStateOf(true) }
    var group by rememberSaveable(sheet.id, draft) { mutableStateOf(sheet.group) }
    var newGroup by rememberSaveable(sheet.id, draft) { mutableStateOf("") }
    var bad by rememberSaveable(sheet.id, draft) { mutableStateOf(false) }

    var pack by rememberSaveable(sheet.id, draft) { mutableStateOf(sheet.preferredPack) }
    var advanced by rememberSaveable(sheet.id, draft) { mutableStateOf(false) }
    var newUnit by rememberSaveable(sheet.id, draft) { mutableStateOf<String?>(null) }
    var unitAmount by rememberSaveable(sheet.id, draft) { mutableStateOf("") }
    var confirmRename by rememberSaveable(sheet.id, draft) { mutableStateOf(false) }
    var allUnits by rememberSaveable(sheet.id, draft) { mutableStateOf(false) }

    val changes =
        ItemChanges(
            name = name.trim().takeIf { it.isNotEmpty() && !it.equals(sheet.name, ignoreCase = true) },
            amount = amount.trim().takeIf { it != sheet.amount },
            aisle = aisle.takeIf { it != sheet.aisle },
            alwaysAisle = always,
            groupChanged = group != sheet.group,
            group = group,
            packChanged = pack != sheet.preferredPack,
            pack = pack.takeIf { it.isNotBlank() },
        )

    val dirty =
        pack != sheet.preferredPack || name != sheet.name || amount != sheet.amount ||
            aisle != sheet.aisle || group != sheet.group || newGroup.isNotBlank()
    SideEffect { onDirty(dirty) }
    val save = {
        if (changes.name != null) confirmRename = true else bad = !onSave(changes)
    }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            SectionLabel("Edit item")
            OutlinedTextField(
                value = name,
                enabled = !sheet.saving,
                onValueChange = { name = it },
                label = { Text("Name") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth().testTag("edit-name"),
            )
            OutlinedTextField(
                value = amount,
                enabled = !sheet.saving,
                onValueChange = {
                    amount = it
                    bad = false
                },
                label = { Text("Total needed, e.g. 3 or 500 g") },
                isError = bad,
                supportingText = if (bad) ({ Text("Enter an amount, like 2 or 200 ml") }) else null,
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (!changes.isEmpty && name.isNotBlank() && !sheet.saving) save() }),
                modifier = Modifier.fillMaxWidth().testTag("edit-qty"),
            )
            val unit = ItemEdit.unitOf(amount)
            // The current unit and its exact alternatives first; the rest behind "More units".
            val common = ItemEdit.commonUnits(unit)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (u in if (allUnits) ItemEdit.UNITS else common) {
                    FilterChip(
                        enabled = !sheet.saving,
                        selected = u == unit,
                        onClick = {
                            if (u != unit) {
                                val converted = ItemEdit.withUnit(amount, u)
                                if (converted != null) {
                                    amount = converted
                                } else {
                                    // No conversion: the amount is asked for, never carried over as if it were one.
                                    newUnit = u
                                    unitAmount = ""
                                }
                                bad = false
                            }
                        },
                        label = { Text(u) },
                        shape = PillShape,
                        colors = basilChipColors(),
                        modifier = Modifier.testTag("unit-$u"),
                    )
                }
                TextButton(
                    onClick = { allUnits = !allUnits },
                    enabled = !sheet.saving,
                    modifier = Modifier.heightIn(min = 48.dp).testTag("more-units"),
                ) { Text(if (allUnits) "Fewer units" else "More units") }
            }

            Text(
                "Edits the total needed, including anything bought or at home. Sources are below.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.testTag("item-preferences")) {
                Text(if (advanced) "Hide aisle, category and pack" else "Aisle, category and usual pack")
            }
            if (advanced) {
                OutlinedTextField(
                    value = pack,
                    enabled = !sheet.saving,
                    onValueChange = { pack = it },
                    label = { Text("Usual pack at ${sheet.storeName}, e.g. 400 g") },
                    supportingText = { Text("A buying suggestion only; the amount needed is unchanged. Blank forgets it.") },
                    modifier = Modifier.fillMaxWidth().testTag("preferred-pack"),
                )
                SectionLabel("Aisle")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (a in AisleCode.entries) {
                        FilterChip(
                            enabled = !sheet.saving,
                            selected = a == aisle,
                            onClick = {
                                if (a != aisle) {
                                    aisle = a
                                    // A category is the aisle's: one from the old aisle does not follow it.
                                    if (group != null && group !in sheet.groups[a].orEmpty()) group = null
                                }
                            },
                            label = { Text(a.displayName) },
                            leadingIcon = {
                                Icon(
                                    a.icon,
                                    contentDescription = null,
                                    modifier = Modifier.size(FilterChipDefaults.IconSize),
                                )
                            },
                            shape = PillShape,
                            colors = basilChipColors(),
                            modifier = Modifier.testTag("aisle-${a.name}"),
                        )
                    }
                }
                if (aisle != sheet.aisle) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            enabled = !sheet.saving,
                            checked = always,
                            onCheckedChange = { always = it },
                            modifier = Modifier.testTag("always-aisle"),
                        )
                        Text(
                            "Always put ${name.trim().ifEmpty { sheet.name }} in ${aisle.displayName}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }

                SectionLabel("Category")
                val groups = (sheet.groups[aisle].orEmpty() + listOfNotNull(group)).distinct()
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(
                        enabled = !sheet.saving,
                        selected = group == null,
                        onClick = { group = null },
                        label = { Text("None") },
                        shape = PillShape,
                        colors = basilChipColors(),
                        modifier = Modifier.testTag("group-none"),
                    )
                    for (g in groups) {
                        FilterChip(
                            enabled = !sheet.saving,
                            selected = g == group,
                            onClick = { group = g },
                            label = { Text(g) },
                            shape = PillShape,
                            colors = basilChipColors(),
                            modifier = Modifier.testTag("group-$g"),
                        )
                    }
                }
                val addGroup = {
                    newGroup.trim().takeIf { it.isNotEmpty() }?.let {
                        group = it.replaceFirstChar { c -> c.uppercase() }
                        newGroup = ""
                    }
                }
                Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = newGroup,
                        enabled = !sheet.saving,
                        onValueChange = { newGroup = it },
                        label = { Text("New category") },
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                        keyboardActions = KeyboardActions(onDone = { addGroup() }),
                        modifier = Modifier.weight(1f).testTag("new-group"),
                    )
                    FilledTonalButton(
                        onClick = { addGroup() },
                        enabled = newGroup.isNotBlank() && !sheet.saving,
                        modifier = Modifier.padding(top = 8.dp),
                    ) {
                        Text("Add")
                    }
                }
                if (changes.groupChanged) {
                    Text(
                        "The category is kept for next time too.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            details()
        }
        sheet.saveError?.let { Text(it, color = MaterialTheme.colorScheme.error, modifier = Modifier.testTag("item-save-error")) }
        Button(
            onClick = save,
            enabled = !changes.isEmpty && name.isNotBlank() && !sheet.saving,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("save-item"),
        ) { Text(if (sheet.saving) "Saving…" else "Save changes") }
    }
    if (confirmRename) {
        AlertDialog(
            onDismissRequest = { confirmRename = false },
            title = { Text("Rename ${sheet.name}?") },
            text = {
                Text(
                    "If this ingredient is already on the list, their contributions and " +
                        "purchases will be combined. A combined amount must be edited after merging.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirmRename = false
                    bad = !onSave(changes)
                }) { Text("Rename and save") }
            },
            dismissButton = { TextButton(onClick = { confirmRename = false }) { Text("Cancel") } },
        )
    }
    newUnit?.let { unit ->
        UnitAmountDialog(
            from = ItemEdit.unitOf(amount),
            unit = unit,
            typed = unitAmount,
            onType = { unitAmount = it },
            onConfirm = { text ->
                // The draft only: Save still persists it.
                amount = text
                newUnit = null
                unitAmount = ""
                bad = false
            },
            onCancel = {
                newUnit = null
                unitAmount = ""
            },
        )
    }
}

/**
 * A unit with no exact conversion from the current one (g to ml, a count to kg): the amount is typed for the new
 * unit. Nothing is suggested, so no density or pack size is implied. Use stays off until the amount is a positive
 * number; what is typed is kept, with what to fix, until it is.
 */
@Composable
private fun UnitAmountDialog(
    from: String?,
    unit: String,
    typed: String,
    onType: (String) -> Unit,
    onConfirm: (String) -> Unit,
    onCancel: () -> Unit,
) {
    val proposal = ItemEdit.propose(typed, unit)
    var tried by rememberSaveable(unit) { mutableStateOf(false) }
    val showError = proposal.error != null && (typed.isNotBlank() || tried)
    val focus = remember { FocusRequester() }
    LaunchedEffect(unit) { runCatching { focus.requestFocus() } }
    val unitName = if (unit == ItemEdit.EACH) "a count" else unit
    val fromName =
        when (from) {
            null -> "this amount"
            ItemEdit.EACH -> "a count"
            else -> from
        }
    AlertDialog(
        onDismissRequest = onCancel,
        // As the home amount dialog (N.8): a text field in a platform-width dialog never settles its layout.
        modifier = Modifier.fillMaxWidth().padding(horizontal = 24.dp).testTag("unit-amount-dialog"),
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = { Text("Change to $unitName") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("There is no conversion from $fromName to $unitName. Enter the amount you need.")
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = typed,
                        onValueChange = onType,
                        label = { Text("Amount") },
                        isError = showError,
                        supportingText = if (showError) ({ Text(proposal.error!!) }) else null,
                        singleLine = true,
                        shape = MaterialTheme.shapes.medium,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text, imeAction = ImeAction.Done),
                        keyboardActions =
                            KeyboardActions(onDone = {
                                val text = proposal.text
                                if (text != null) onConfirm(text) else tried = true
                            }),
                        modifier = Modifier.weight(1f).focusRequester(focus).testTag("unit-amount"),
                    )
                    if (unit != ItemEdit.EACH) {
                        Text(unit, style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("unit-amount-unit"))
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = proposal.text != null,
                onClick = { proposal.text?.let(onConfirm) },
                modifier = Modifier.testTag("unit-amount-use"),
            ) { Text(proposal.text?.let { "Use $it" } ?: "Use amount") }
        },
        dismissButton = { TextButton(onClick = onCancel, modifier = Modifier.testTag("unit-amount-cancel")) { Text("Cancel") } },
    )
}
