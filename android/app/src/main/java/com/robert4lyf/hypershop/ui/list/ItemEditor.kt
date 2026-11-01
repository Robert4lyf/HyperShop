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
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
) {
    // Keyed on the item: another row's sheet starts from that row. Survives a rotation.
    var name by rememberSaveable(sheet.id) { mutableStateOf(sheet.name) }
    var amount by rememberSaveable(sheet.id) { mutableStateOf(sheet.amount) }
    var aisle by rememberSaveable(sheet.id) { mutableStateOf(sheet.aisle) }
    var always by rememberSaveable(sheet.id) { mutableStateOf(true) }
    var group by rememberSaveable(sheet.id) { mutableStateOf(sheet.group) }
    var newGroup by rememberSaveable(sheet.id) { mutableStateOf("") }
    var bad by rememberSaveable(sheet.id) { mutableStateOf(false) }

    val changes =
        ItemChanges(
            name = name.trim().takeIf { it.isNotEmpty() && !it.equals(sheet.name, ignoreCase = true) },
            amount = amount.trim().takeIf { it.isNotEmpty() && it != sheet.amount },
            aisle = aisle.takeIf { it != sheet.aisle },
            alwaysAisle = always,
            groupChanged = group != sheet.group,
            group = group,
        )

    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        SectionLabel("Edit")
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("Name") },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
            modifier = Modifier.fillMaxWidth().testTag("edit-name"),
        )
        OutlinedTextField(
            value = amount,
            onValueChange = {
                amount = it
                bad = false
            },
            label = { Text("Amount, e.g. 3 or 500 g") },
            isError = bad,
            supportingText = if (bad) ({ Text("Enter an amount, like 2 or 200 ml") }) else null,
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { if (!changes.isEmpty) bad = !onSave(changes) }),
            modifier = Modifier.fillMaxWidth().testTag("edit-qty"),
        )
        val unit = ItemEdit.unitOf(amount)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (u in ItemEdit.UNITS) {
                FilterChip(
                    selected = u == unit,
                    onClick = {
                        amount = ItemEdit.withUnit(amount, u)
                        bad = false
                    },
                    label = { Text(u) },
                    shape = PillShape,
                    colors = basilChipColors(),
                    modifier = Modifier.testTag("unit-$u"),
                )
            }
        }

        SectionLabel("Aisle")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (a in AisleCode.entries) {
                FilterChip(
                    selected = a == aisle,
                    onClick = {
                        if (a != aisle) {
                            aisle = a
                            // A category is the aisle's: one from the old aisle does not follow it.
                            if (group != null && group !in sheet.groups[a].orEmpty()) group = null
                        }
                    },
                    label = { Text(a.displayName) },
                    leadingIcon = { Icon(a.icon, contentDescription = null, modifier = Modifier.size(FilterChipDefaults.IconSize)) },
                    shape = PillShape,
                    colors = basilChipColors(),
                    modifier = Modifier.testTag("aisle-${a.name}"),
                )
            }
        }
        if (aisle != sheet.aisle) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = always, onCheckedChange = { always = it }, modifier = Modifier.testTag("always-aisle"))
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
                selected = group == null,
                onClick = { group = null },
                label = { Text("None") },
                shape = PillShape,
                colors = basilChipColors(),
                modifier = Modifier.testTag("group-none"),
            )
            for (g in groups) {
                FilterChip(
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
                onValueChange = { newGroup = it },
                label = { Text("New category") },
                singleLine = true,
                shape = MaterialTheme.shapes.medium,
                keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
                keyboardActions = KeyboardActions(onDone = { addGroup() }),
                modifier = Modifier.weight(1f).testTag("new-group"),
            )
            FilledTonalButton(onClick = { addGroup() }, enabled = newGroup.isNotBlank(), modifier = Modifier.padding(top = 8.dp)) {
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

        Button(
            onClick = { bad = !onSave(changes) },
            enabled = !changes.isEmpty,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("save-item"),
        ) { Text("Save changes") }
    }
}
