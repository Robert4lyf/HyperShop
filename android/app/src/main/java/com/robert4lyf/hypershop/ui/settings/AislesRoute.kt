package com.robert4lyf.hypershop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.ui.common.ScreenHeader
import com.robert4lyf.hypershop.ui.common.basilSegmentedColors
import com.robert4lyf.hypershop.ui.theme.Brand
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
            onResetAisles = { vm.resetAisleOrder() },
            onEditStore = { vm.editStore(it) },
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
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("aisles")) {
            ScreenHeader(
                title = "Aisles",
                subtitle = "The order you walk the store",
                actions = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = Brand.OnHero)
                    }
                },
            )
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                // Missing 4 of the review: a second store's order, picked when a shop starts.
                SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                    SegmentedButton(
                        selected = ui.editingStore == 1L,
                        onClick = { actions.onEditStore(1) },
                        shape = SegmentedButtonDefaults.itemShape(0, 2),
                        colors = basilSegmentedColors(),
                        modifier = Modifier.testTag("store-1"),
                    ) { Text("My store") }
                    SegmentedButton(
                        selected = ui.editingStore == 2L,
                        onClick = { actions.onEditStore(2) },
                        shape = SegmentedButtonDefaults.itemShape(1, 2),
                        colors = basilSegmentedColors(),
                        modifier = Modifier.testTag("store-2"),
                    ) { Text("Second store") }
                }
                Text(
                    if (ui.editingStore == 2L) {
                        "A second shop's order. Once it differs from your store's, \"Shop\" asks which one you are in."
                    } else {
                        "The order the list and the watch follow."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
                if (ui.loaded) AislesGroup(ui, actions)
            }
        }
    }
}
