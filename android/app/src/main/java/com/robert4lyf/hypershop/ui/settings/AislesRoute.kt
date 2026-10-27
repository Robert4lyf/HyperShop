package com.robert4lyf.hypershop.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.ui.common.ScreenHeader
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
        ),
        onBack = onBack,
    )
}

/** The walking order, stateless. */
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
                if (ui.loaded) AislesGroup(ui, actions)
            }
        }
    }
}
