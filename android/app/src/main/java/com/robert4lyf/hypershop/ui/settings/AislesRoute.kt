package com.robert4lyf.hypershop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.ui.common.ScreenHeader
import com.robert4lyf.hypershop.ui.common.basilSegmentedColors
import com.robert4lyf.hypershop.ui.common.rememberReorderScroll
import com.robert4lyf.hypershop.ui.common.reorderViewport
import com.robert4lyf.hypershop.ui.util.factoryFor

/** Settings › Aisles › Walking order, as its own screen (UX review 22): the aisles with arrows, ends and Reset. */
@Composable
fun AislesRoute(
    container: AppContainer,
    onBack: () -> Unit,
) {
    val vm: SettingsViewModel = viewModel(factory = factoryFor { SettingsViewModel(container) })
    val ui by vm.ui.collectAsStateWithLifecycle()
    AislesScreen(
        ui,
        SettingsActions(
            onMoveAisle = { a, d -> vm.moveAisle(a, d) },
            onMoveAisleTo = { a, i -> vm.moveAisleTo(a, i) },
            onMoveGroup = { a, g, by -> vm.moveGroup(a, g, by) },
            onMoveGroupTo = { a, g, i -> vm.moveGroupTo(a, g, i) },
            onResetAisles = { vm.resetAisleOrder() },
            onEditStore = { vm.editStore(it) },
            onRenameStore = { store, name -> vm.renameStore(store, name) },
        ),
        onBack = onBack,
    )
}

/** The walking order, stateless. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AislesScreen(
    ui: SettingsUi,
    actions: SettingsActions,
    onBack: () -> Unit = {},
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val scroll = rememberScrollState()
        val reorder = rememberReorderScroll(scroll)
        Column(Modifier.fillMaxSize().reorderViewport(reorder).verticalScroll(scroll).testTag("aisles")) {
            ScreenHeader(title = "Aisles", subtitle = "The order you walk the store", onBack = onBack, backTag = "aisles-back")
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                // Missing 4 of the review: a second store's order, picked when a shop starts. Step AD.2: each store's
                // tab has a pencil that renames it, in place of a name field above the aisles.
                var renaming by rememberSaveable { mutableStateOf<Long?>(null) }
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    for ((i, store) in listOf(1L, 2L).withIndex()) {
                        val name = ui.storeNames[store] ?: if (store == 1L) "My store" else "Second store"
                        SegmentedButton(
                            selected = ui.editingStore == store,
                            onClick = { actions.onEditStore(store) },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                            colors = basilSegmentedColors(),
                            modifier = Modifier.testTag("store-$store"),
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(name, Modifier.weight(1f, fill = false), maxLines = 1, overflow = TextOverflow.Ellipsis)
                                IconButton(
                                    onClick = { renaming = store },
                                    modifier = Modifier.size(40.dp).testTag("rename-store-$store"),
                                ) {
                                    Icon(Icons.Rounded.Edit, contentDescription = "Rename $name", modifier = Modifier.size(18.dp))
                                }
                            }
                        }
                    }
                }
                renaming?.let { store ->
                    RenameStoreDialog(
                        ui.storeNames[store].orEmpty(),
                        onDismiss = { renaming = null },
                    ) {
                        renaming = null
                        actions.onRenameStore(store, it)
                    }
                }
                if (ui.loaded) AislesGroup(ui, actions, reorder)
            }
        }
    }
}

/** Step AD.2: a store's new name, from the pencil on its tab. */
@Composable
private fun RenameStoreDialog(
    current: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(current) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename store") },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                label = { Text("Store name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("store-name"),
            )
        },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onSave(name.trim()) }, modifier = Modifier.testTag("store-name-save")) {
                Text("Save")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
