package com.robert4lyf.hypershop.ui.settings

import android.app.Activity
import android.view.WindowManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robert4lyf.hypershop.claude.ClaudeModel
import com.robert4lyf.hypershop.core.merge.UnitsSetting
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.ui.util.factoryFor
import java.util.Locale

data class SettingsActions(
    val onUnits: (UnitsSetting) -> Unit = {},
    val onRemoveStaple: (String) -> Unit = {},
    val onMoveAisle: (AisleCode, Int) -> Unit = { _, _ -> },
    val onWatchVibrate: (Boolean) -> Unit = {},
    val onSaveKey: (String) -> Unit = {},
    val onTestKey: () -> Unit = {},
    val onRemoveKey: () -> Unit = {},
    val onDismissKeyLost: () -> Unit = {},
    val onModel: (ClaudeModel) -> Unit = {},
    val onMonthlyCap: (String) -> Boolean = { true },
    val onDailyCalls: (String) -> Boolean = { true },
    val onDebugLog: (Boolean) -> Unit = {},
    val onRefreshLog: () -> Unit = {},
    /** Debug builds only. */
    val onDebug: (() -> Unit)? = null,
)

@Composable
fun SettingsRoute(
    container: AppContainer,
    onDebug: (() -> Unit)?,
) {
    val vm: SettingsViewModel = viewModel(factory = factoryFor { SettingsViewModel(container) })
    val ui by vm.ui.collectAsStateWithLifecycle()
    // The key field is on this screen: no screenshots, no recents thumbnail.
    val activity = LocalContext.current as? Activity
    DisposableEffect(activity) {
        activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
    }
    SettingsScreen(
        ui,
        SettingsActions(
            onUnits = { vm.setUnits(it) },
            onRemoveStaple = { vm.removeStaple(it) },
            onMoveAisle = { a, d -> vm.moveAisle(a, d) },
            onWatchVibrate = { vm.setWatchVibrate(it) },
            onSaveKey = { vm.saveKey(it) },
            onTestKey = { vm.testKey() },
            onRemoveKey = { vm.removeKey() },
            onDismissKeyLost = { vm.dismissKeyLost() },
            onModel = { vm.setModel(it) },
            onMonthlyCap = vm::setMonthlyCap,
            onDailyCalls = vm::setDailyCalls,
            onDebugLog = { vm.setDebugLog(it) },
            onRefreshLog = { vm.refreshLog() },
            onDebug = onDebug,
        ),
    )
}

/** Settings, stateless, grouped as PLAN §3.5: List, Aisles, Watch, Claude, Advanced. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    ui: SettingsUi,
    actions: SettingsActions,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(Modifier.fillMaxSize()) {
            TopAppBar(title = { Text("Settings") })
            LazyColumn(Modifier.fillMaxSize().testTag("settings")) {
                listGroup(ui, actions)
                aislesGroup(ui, actions)
                watchGroup(ui, actions)
                claudeGroup(ui, actions)
                advancedGroup(ui, actions)
            }
        }
    }
    if (ui.keyLost) {
        AlertDialog(
            onDismissRequest = actions.onDismissKeyLost,
            title = { Text("Claude key removed") },
            text = {
                Text(
                    "Android could no longer unlock your saved key, so it was deleted and Claude is off. " +
                        "Recipes are read offline until you enter the key again.",
                )
            },
            confirmButton = { TextButton(onClick = actions.onDismissKeyLost) { Text("OK") } },
        )
    }
}

private fun LazyListScope.group(title: String) =
    item(key = "group-$title") {
        Column {
            HorizontalDivider()
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
            )
        }
    }

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, role = Role.Switch, onValueChange = onChange)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}

@OptIn(ExperimentalLayoutApi::class)
private fun LazyListScope.listGroup(
    ui: SettingsUi,
    actions: SettingsActions,
) {
    group("List")
    item(key = "units") {
        Column(Modifier.padding(horizontal = 16.dp)) {
            Text("Units")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((u, label) in UNIT_LABELS) {
                    FilterChip(selected = ui.units == u, onClick = { actions.onUnits(u) }, label = { Text(label) })
                }
            }
            Text("Always in stock", Modifier.padding(top = 12.dp))
            if (ui.staples.isEmpty()) Text("None", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    for (s in ui.staples) {
        item(key = "staple-$s") {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(s.replaceFirstChar { it.uppercase() }, Modifier.weight(1f))
                IconButton(onClick = { actions.onRemoveStaple(s) }) { Icon(Icons.Filled.Close, contentDescription = "Remove $s") }
            }
        }
    }
}

private fun LazyListScope.aislesGroup(
    ui: SettingsUi,
    actions: SettingsActions,
) {
    group("Aisles")
    item(key = "aisles-hint") {
        Text(
            "The order you walk the shop in.",
            Modifier.padding(horizontal = 16.dp),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    ui.aisles.forEachIndexed { i, a ->
        item(key = "aisle-${a.name}") {
            Row(Modifier.fillMaxWidth().padding(start = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(a.displayName, Modifier.weight(1f))
                IconButton(onClick = { actions.onMoveAisle(a, -1) }, enabled = i > 0) {
                    Icon(Icons.Filled.ArrowUpward, contentDescription = "Move ${a.displayName} up")
                }
                IconButton(onClick = { actions.onMoveAisle(a, 1) }, enabled = i < ui.aisles.lastIndex) {
                    Icon(Icons.Filled.ArrowDownward, contentDescription = "Move ${a.displayName} down")
                }
            }
        }
    }
}

private fun LazyListScope.watchGroup(
    ui: SettingsUi,
    actions: SettingsActions,
) {
    group("Watch")
    item(key = "watch") {
        Column {
            Text(
                "Forerunner 245: not connected. The watch app comes in a later update.",
                Modifier.padding(horizontal = 16.dp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            SwitchRow("Vibrate on the watch when an aisle is done", ui.watchVibrate, actions.onWatchVibrate)
        }
    }
}

private fun LazyListScope.claudeGroup(
    ui: SettingsUi,
    actions: SettingsActions,
) {
    group("Claude")
    item(key = "claude-key") { KeySection(ui, actions) }
    item(key = "claude-model") { ModelSection(ui, actions) }
    item(key = "claude-caps") { CapsSection(ui, actions) }
}

@Composable
private fun KeySection(
    ui: SettingsUi,
    actions: SettingsActions,
) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Recipe pages you import are sent to Anthropic for reading, under your own account and key. " +
                "Items you type never leave the phone.",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag("privacy"),
        )
        if (ui.keyMissingAfterRestore) {
            Text(
                "Your key isn't backed up, so it didn't come across with this restore. Enter it again.",
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (ui.hasKey) {
            Text("Key saved", Modifier.testTag("key-saved"))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = actions.onTestKey, enabled = ui.keyStatus != KeyStatus.TESTING) { Text("Test key") }
                OutlinedButton(onClick = actions.onRemoveKey) { Text("Remove key") }
            }
        } else {
            var key by remember { mutableStateOf("") }
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("Claude API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                // No autofill: this Compose version has none for text fields, and nothing opts in.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                modifier = Modifier.fillMaxWidth().testTag("key-field"),
            )
            Button(
                onClick = {
                    actions.onSaveKey(key)
                    key = ""
                },
                enabled = key.isNotBlank(),
            ) { Text("Save and test") }
            Text("Without a key, recipes are read by the offline parser.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        ui.keyStatus?.let {
            Text(
                when (it) {
                    KeyStatus.TESTING -> "Testing…"
                    KeyStatus.OK -> "✓ The key works"
                    KeyStatus.BAD_KEY -> "✗ Anthropic didn't accept that key"
                    KeyStatus.OFFLINE -> "Couldn't reach Anthropic. Check your connection."
                    KeyStatus.ERROR -> "Anthropic returned an error. Try again later."
                },
                Modifier.testTag("key-status"),
                color = if (it == KeyStatus.BAD_KEY) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ModelSection(
    ui: SettingsUi,
    actions: SettingsActions,
) {
    Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Text("Model")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (m in ClaudeModel.entries) {
                FilterChip(selected = ui.model == m, onClick = { actions.onModel(m) }, label = { Text(m.label) })
            }
        }
        Text(
            "Haiku costs about $0.001 a recipe. Sonnet is about 20 times that, Opus about 40.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CapsSection(
    ui: SettingsUi,
    actions: SettingsActions,
) {
    Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            "Spent this month: ${usd(ui.spentThisMonthUsd)} of ${usd(ui.monthlyCapUsd)} · " +
                "${ui.callsToday} of ${ui.dailyCalls} calls today",
            Modifier.testTag("spend"),
        )
        CapField("Monthly cap (US$)", String.format(Locale.US, "%.2f", ui.monthlyCapUsd), "cap-month", actions.onMonthlyCap)
        CapField("Calls a day", ui.dailyCalls.toString(), "cap-day", actions.onDailyCalls)
    }
}

@Composable
private fun CapField(
    label: String,
    current: String,
    tag: String,
    onSet: (String) -> Boolean,
) {
    var text by remember(current) { mutableStateOf(current) }
    var bad by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                bad = false
            },
            label = { Text(label) },
            isError = bad,
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f).testTag(tag),
        )
        TextButton(onClick = { bad = !onSet(text) }, enabled = text != current) { Text("Set") }
    }
}

private val UNIT_LABELS =
    listOf(UnitsSetting.METRIC to "Metric", UnitsSetting.IMPERIAL to "Imperial", UnitsSetting.AS_WRITTEN to "As written")

private fun usd(v: Double) = String.format(Locale.US, "$%.2f", v)

private fun LazyListScope.advancedGroup(
    ui: SettingsUi,
    actions: SettingsActions,
) {
    group("Advanced")
    item(key = "debug-log") { SwitchRow("Show the debug log", ui.debugLog, actions.onDebugLog) }
    if (ui.debugLog) {
        item(key = "debug-log-lines") {
            Column(Modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "Kept in memory only, never sent anywhere. Keys and query strings are removed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (ui.logLines.isEmpty()) Text("Nothing logged yet")
                for (l in ui.logLines) Text(l, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                TextButton(onClick = actions.onRefreshLog) { Text("Refresh") }
            }
        }
    }
    actions.onDebug?.let { open ->
        item(key = "debug-tools") {
            TextButton(onClick = open, modifier = Modifier.padding(horizontal = 8.dp)) { Text("Debug tools") }
        }
    }
}
