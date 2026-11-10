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
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
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
    details: @Composable () -> Unit = {},
) {
    // Keyed on the item: another row's sheet starts from that row. Survives a rotation.
    var name by rememberSaveable(sheet.id) { mutableStateOf(sheet.name) }
    var amount by rememberSaveable(sheet.id) { mutableStateOf(sheet.amount) }
    var aisle by rememberSaveable(sheet.id) { mutableStateOf(sheet.aisle) }
    var always by rememberSaveable(sheet.id) { mutableStateOf(true) }
    var group by rememberSaveable(sheet.id) { mutableStateOf(sheet.group) }
    var newGroup by rememberSaveable(sheet.id) { mutableStateOf("") }
    var bad by rememberSaveable(sheet.id) { mutableStateOf(false) }

    var pack by rememberSaveable(sheet.id) { mutableStateOf(sheet.preferredPack) }
    var advanced by rememberSaveable(sheet.id) { mutableStateOf(false) }
    var newUnit by rememberSaveable(sheet.id) { mutableStateOf<String?>(null) }
    var unitAmount by rememberSaveable(sheet.id) { mutableStateOf("") }
    var confirmRename by rememberSaveable(sheet.id) { mutableStateOf(false) }

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
                label = { Text("Total required, e.g. 3 or 500 g") },
                isError = bad,
                supportingText = if (bad) ({ Text("Enter an amount, like 2 or 200 ml") }) else null,
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { if (!changes.isEmpty && name.isNotBlank() && !sheet.saving) save() }),
                modifier = Modifier.fillMaxWidth().testTag("edit-qty"),
            )
            val unit = ItemEdit.unitOf(amount)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (u in ItemEdit.UNITS) {
                    FilterChip(
                        enabled = !sheet.saving,
                        selected = u == unit,
                        onClick = {
                            val converted = ItemEdit.withUnit(amount, u)
                            if (converted != null) {
                                amount = converted
                            } else {
                                newUnit = u
                                unitAmount =
                                    com.robert4lyf.hypershop.core.parse.RuleParser()
                                        .parse(amount + " hypershopquantity").quantity?.toString().orEmpty()
                            }
                            bad = false
                        },
                        label = { Text(u) },
                        shape = PillShape,
                        colors = basilChipColors(),
                        modifier = Modifier.testTag("unit-$u"),
                    )
                }
            }

            Text(
                "This edits the total requirement, including amounts already bought or at home. Source contributions are shown below.",
                style = MaterialTheme.typography.bodySmall,
            )
            TextButton(onClick = { advanced = !advanced }, modifier = Modifier.testTag("item-preferences")) {
                Text(if (advanced) "Hide aisle, category and lasting preferences" else "Aisle, category and lasting preferences")
            }
            if (advanced) {
                OutlinedTextField(
                    value = pack,
                    enabled = !sheet.saving,
                    onValueChange = { pack = it },
                    label = { Text("Usual pack at ${sheet.storeName}, e.g. 400 g") },
                    supportingText = {
                        Text(
                            "Optional purchase suggestion. Leave blank to forget it; requirements and purchases stay separate.",
                        )
                    },
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
        val proposed = if (unit == ItemEdit.EACH) unitAmount else "$unitAmount $unit"
        val valid = com.robert4lyf.hypershop.core.parse.QuantityInput.parse(proposed) != null
        AlertDialog(
            onDismissRequest = { newUnit = null },
            title = { Text("Change the measure to $unit?") },
            text = {
                Column {
                    Text(
                        "There is no known conversion from this measure. Enter the amount you " +
                            "intend; pack sizes and densities are not assumed.",
                    )
                    Text("New total: $proposed")
                    Text("Use this as a new amount, then edit the number if needed. No conversion is implied.")
                }
            },
            confirmButton = {
                TextButton(enabled = valid, onClick = {
                    amount = proposed
                    newUnit = null
                }) { Text("Use amount") }
            },
            dismissButton = { TextButton(onClick = { newUnit = null }) { Text("Cancel") } },
        )
    }
}
