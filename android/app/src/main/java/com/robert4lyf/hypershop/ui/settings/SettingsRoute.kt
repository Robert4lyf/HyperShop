package com.robert4lyf.hypershop.ui.settings

import android.app.Activity
import android.content.ClipboardManager
import android.view.WindowManager
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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Error
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.LocalDining
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Palette
import androidx.compose.material.icons.rounded.Route
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Watch
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robert4lyf.hypershop.claude.ClaudeModel
import com.robert4lyf.hypershop.core.merge.UnitsSetting
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.settings.ThemeMode
import com.robert4lyf.hypershop.ui.common.AisleBadge
import com.robert4lyf.hypershop.ui.common.InfoBanner
import com.robert4lyf.hypershop.ui.common.ScreenHeader
import com.robert4lyf.hypershop.ui.common.SectionCard
import com.robert4lyf.hypershop.ui.common.Tone
import com.robert4lyf.hypershop.ui.list.show
import com.robert4lyf.hypershop.ui.theme.Brand
import com.robert4lyf.hypershop.ui.theme.LocalDarkTheme
import com.robert4lyf.hypershop.ui.util.factoryFor
import java.time.format.DateTimeFormatter
import java.util.Locale

data class SettingsActions(
    val onUnits: (UnitsSetting) -> Unit = {},
    val onTheme: (ThemeMode) -> Unit = {},
    val onRemoveStaple: (String) -> Unit = {},
    val onMoveAisle: (AisleCode, Int) -> Unit = { _, _ -> },
    /** "Move to top" (0) / "Move to bottom" (last). */
    val onMoveAisleTo: (AisleCode, Int) -> Unit = { _, _ -> },
    val onResetAisles: () -> Unit = {},
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
    val onLibrary: () -> Unit = {},
    /** Advanced › Voice test. */
    val onVoiceTest: () -> Unit = {},
    /** Debug builds only. */
    val onDebug: (() -> Unit)? = null,
)

@Composable
fun SettingsRoute(
    container: AppContainer,
    onDebug: (() -> Unit)?,
    onLibrary: () -> Unit = {},
    onVoiceTest: () -> Unit = {},
    /** The app's snackbar host; without one the screen shows its own. */
    snackbar: SnackbarHostState? = null,
) {
    val vm: SettingsViewModel = viewModel(factory = factoryFor { SettingsViewModel(container) })
    val ui by vm.ui.collectAsStateWithLifecycle()
    val own = remember { SnackbarHostState() }
    val host = snackbar ?: own
    LaunchedEffect(vm, host) { vm.events.collect { show(container, host, it) } }
    SettingsScreen(
        ui,
        SettingsActions(
            onUnits = { vm.setUnits(it) },
            onTheme = { vm.setTheme(it) },
            onRemoveStaple = { vm.removeStaple(it) },
            onMoveAisle = { a, d -> vm.moveAisle(a, d) },
            onMoveAisleTo = { a, i -> vm.moveAisleTo(a, i) },
            onResetAisles = { vm.resetAisleOrder() },
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
            onLibrary = onLibrary,
            onVoiceTest = onVoiceTest,
        ),
        snackbar = own.takeIf { snackbar == null },
    )
}

/** Settings, stateless, grouped as PLAN §3.5: List, Aisles, Watch, Claude, Advanced. Each group is a card. */
@Composable
fun SettingsScreen(
    ui: SettingsUi,
    actions: SettingsActions,
    /** A host to draw at the bottom, when the app's isn't in reach. */
    snackbar: SnackbarHostState? = null,
) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize()) {
            LazyColumn(
                Modifier.fillMaxSize().testTag("settings"),
                contentPadding = PaddingValues(bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                item(key = "top") { ScreenHeader(title = "Settings", subtitle = "Your list, your shop, your watch") }
                // Nothing below the header until the saved settings are in, so no control jumps.
                if (!ui.loaded) {
                    item(key = "loading") {
                        Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(Modifier.testTag("settings-loading"))
                        }
                    }
                    return@LazyColumn
                }
                card("Appearance", Icons.Rounded.Palette, hue = 300f) { AppearanceGroup(ui, actions) }
                card("List", Icons.Rounded.Tune, hue = 140f) { ListGroup(ui, actions) }
                card("Aisles", Icons.Rounded.Route, hue = 30f) { AislesGroup(ui, actions) }
                card("Ingredients", Icons.Rounded.LocalDining, hue = 95f) { IngredientsGroup(actions) }
                card("Watch", Icons.Rounded.Watch, hue = 205f) { WatchGroup(ui, actions) }
                card("Claude", Icons.Rounded.AutoAwesome, hue = 265f) {
                    KeySection(ui, actions)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    ModelSection(ui, actions)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    CapsSection(ui, actions)
                }
                card("Advanced", Icons.Rounded.Code, hue = 180f) { AdvancedGroup(ui, actions) }
            }
            snackbar?.let { SnackbarHost(it, Modifier.align(Alignment.BottomCenter)) }
        }
    }
    if (ui.keyLost) {
        AlertDialog(
            onDismissRequest = actions.onDismissKeyLost,
            icon = { Icon(Icons.Rounded.Lock, contentDescription = null) },
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

private fun LazyListScope.card(
    title: String,
    icon: ImageVector,
    hue: Float,
    content: @Composable () -> Unit,
) = item(key = "group-$title") {
    SectionCard {
        // Each group has its own colour, so the page reads as sections at a glance.
        val dark = LocalDarkTheme.current
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Box(
                Modifier.size(36.dp).clip(RoundedCornerShape(12.dp)).background(Brand.tint(hue, dark)),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, contentDescription = null, tint = Brand.strong(hue, dark), modifier = Modifier.size(20.dp)) }
            Text(title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.semantics { heading() })
        }
        content()
    }
}

@Composable
private fun SwitchRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    enabled: Boolean = true,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Switch(checked = checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun IngredientsGroup(actions: SettingsActions) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .clickable(role = Role.Button, onClick = actions.onLibrary)
            .testTag("open-library"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text("Ingredient library", style = MaterialTheme.typography.bodyLarge)
            Hint("Names, aisles and units the app knows. Edit them or add your own.")
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AppearanceGroup(
    ui: SettingsUi,
    actions: SettingsActions,
) {
    Text("Theme", style = MaterialTheme.typography.bodyLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth().testTag("theme")) {
        THEME_LABELS.forEachIndexed { i, (t, label) ->
            SegmentedButton(
                selected = ui.theme == t,
                onClick = { actions.onTheme(t) },
                shape = SegmentedButtonDefaults.itemShape(i, THEME_LABELS.size),
            ) { Text(label) }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)
@Composable
private fun ListGroup(
    ui: SettingsUi,
    actions: SettingsActions,
) {
    Text("Units", style = MaterialTheme.typography.bodyLarge)
    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
        UNIT_LABELS.forEachIndexed { i, (u, label) ->
            SegmentedButton(
                selected = ui.units == u,
                onClick = { actions.onUnits(u) },
                shape = SegmentedButtonDefaults.itemShape(i, UNIT_LABELS.size),
            ) { Text(label) }
        }
    }
    Spacer(Modifier.height(4.dp))
    Text("Always in stock", style = MaterialTheme.typography.bodyLarge)
    if (ui.staples.isEmpty()) {
        Hint("None yet. Mark something \"Always in stock\" on the review screen.")
    } else {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            for (s in ui.staples) StapleChip(s) { actions.onRemoveStaple(s) }
        }
    }
}

/** A staple: its name, and an × that is the only way to remove it (a full 48 dp target). */
@Composable
private fun StapleChip(
    name: String,
    onRemove: () -> Unit,
) {
    Surface(
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 14.dp)) {
            Text(name.replaceFirstChar { it.uppercase() }, style = MaterialTheme.typography.labelLarge)
            IconButton(onClick = onRemove) {
                Icon(Icons.Rounded.Close, contentDescription = "Remove $name", modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun AislesGroup(
    ui: SettingsUi,
    actions: SettingsActions,
) {
    var confirmReset by remember { mutableStateOf(false) }
    Hint("The order you walk the shop in. The list follows it.")
    Column {
        ui.aisles.forEachIndexed { i, a ->
            Row(Modifier.fillMaxWidth().heightIn(min = 52.dp), verticalAlignment = Alignment.CenterVertically) {
                AisleBadge(a, size = 32.dp)
                Text(a.displayName, Modifier.weight(1f).padding(start = 12.dp), style = MaterialTheme.typography.bodyLarge)
                IconButton(onClick = { actions.onMoveAisle(a, -1) }, enabled = i > 0) {
                    Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move ${a.displayName} up")
                }
                IconButton(onClick = { actions.onMoveAisle(a, 1) }, enabled = i < ui.aisles.lastIndex) {
                    Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move ${a.displayName} down")
                }
                var menu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "More for ${a.displayName}") }
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                        DropdownMenuItem(text = { Text("Move to top") }, enabled = i > 0, onClick = {
                            menu = false
                            actions.onMoveAisleTo(a, 0)
                        })
                        DropdownMenuItem(text = { Text("Move to bottom") }, enabled = i < ui.aisles.lastIndex, onClick = {
                            menu = false
                            actions.onMoveAisleTo(a, ui.aisles.lastIndex)
                        })
                    }
                }
            }
        }
    }
    TextButton(onClick = { confirmReset = true }, modifier = Modifier.testTag("reset-aisles")) { Text("Reset order") }
    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            title = { Text("Reset the aisle order?") },
            text = { Text("The aisles go back to the order the app came with.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmReset = false
                    actions.onResetAisles()
                }) { Text("Reset") }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Keep") } },
        )
    }
}

@Composable
private fun WatchGroup(
    ui: SettingsUi,
    actions: SettingsActions,
) {
    InfoBanner(ui.watchStatus, Icons.Rounded.Watch, Modifier.testTag("watch-status"))
    Text(
        "Vibration and the rest of the watch's settings are on the watch: Settings at the bottom of its aisle list.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun KeySection(
    ui: SettingsUi,
    actions: SettingsActions,
) {
    var confirmRemove by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        InfoBanner(
            "Recipe pages, photos and typed recipes you review are sent to Anthropic under your own key. " +
                "Items you add on the list never leave the phone.",
            Icons.Rounded.Lock,
            Modifier.testTag("privacy"),
            Tone.PRIMARY,
        )
        if (ui.keyMissingAfterRestore) {
            InfoBanner(
                "Your key isn't backed up, so it didn't come across with this restore. Enter it again.",
                Icons.Rounded.Error,
                tone = Tone.ERROR,
            )
        }
        if (ui.hasKey) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Icon(
                    Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text("Key saved", Modifier.testTag("key-saved"), style = MaterialTheme.typography.bodyLarge)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = actions.onTestKey, enabled = ui.keyStatus != KeyStatus.TESTING) { Text("Test key") }
                OutlinedButton(onClick = { confirmRemove = true }) { Text("Remove key") }
            }
        } else {
            // While the key field is on screen: no screenshots, no recents thumbnail. (Only
            // then: toggling the window flag on every visit to Settings made the tab janky.)
            val context = LocalContext.current
            val activity = context as? Activity
            DisposableEffect(activity) {
                activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
                onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE) }
            }
            // Not rememberSaveable: a secret has no place in the saved instance state.
            var key by remember { mutableStateOf("") }
            OutlinedTextField(
                value = key,
                onValueChange = { key = it },
                label = { Text("Claude API key") },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                // No autofill: this Compose version has none for text fields, and nothing opts in.
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
                trailingIcon = {
                    // Read only when tapped, so Android shows no "pasted from your clipboard" for nothing.
                    IconButton(onClick = {
                        val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                        clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()?.let { key = it.trim() }
                    }, modifier = Modifier.testTag("key-paste")) { Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste") }
                },
                shape = RoundedCornerShape(16.dp),
                modifier = Modifier.fillMaxWidth().testTag("key-field"),
            )
            Button(
                onClick = {
                    actions.onSaveKey(key)
                    key = ""
                },
                enabled = key.isNotBlank(),
            ) { Text("Save and test") }
            Hint("Without a key, recipes are read by the built-in parser, which gets simple lists right and struggles with unusual ones.")
        }
        ui.keyStatus?.let {
            Text(
                when (it) {
                    KeyStatus.TESTING -> "Testing…"
                    KeyStatus.OK -> "✓ The key works"
                    KeyStatus.BAD_KEY -> "✗ Anthropic didn't accept that key"
                    KeyStatus.MALFORMED -> "✗ That doesn't look like a key — paste it again"
                    KeyStatus.OFFLINE -> "Couldn't reach Anthropic. Check your connection."
                    KeyStatus.ERROR -> "Anthropic returned an error. Try again later."
                },
                Modifier.testTag("key-status"),
                style = MaterialTheme.typography.titleSmall,
                color =
                    when (it) {
                        KeyStatus.OK -> MaterialTheme.colorScheme.primary
                        KeyStatus.BAD_KEY, KeyStatus.MALFORMED -> MaterialTheme.colorScheme.error
                        else -> MaterialTheme.colorScheme.onSurface
                    },
            )
        }
    }
    if (confirmRemove) {
        AlertDialog(
            onDismissRequest = { confirmRemove = false },
            title = { Text("Remove your Claude key?") },
            text = { Text("Recipes will be read offline until you enter one again.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmRemove = false
                    actions.onRemoveKey()
                }) { Text("Remove") }
            },
            dismissButton = { TextButton(onClick = { confirmRemove = false }) { Text("Keep") } },
        )
    }
}

private val MODEL_NOTES =
    mapOf(
        ClaudeModel.HAIKU to "Fast and the most accurate on ingredient names. About $0.001 a recipe.",
        ClaudeModel.SONNET to "About 20 times Haiku's cost.",
        ClaudeModel.OPUS to "About 40 times Haiku's cost.",
    )

@Composable
private fun ModelSection(
    ui: SettingsUi,
    actions: SettingsActions,
) {
    Column {
        Text("Model", style = MaterialTheme.typography.bodyLarge)
        for (m in ClaudeModel.entries) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 56.dp)
                    .selectable(selected = ui.model == m, role = Role.RadioButton) { actions.onModel(m) },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = ui.model == m, onClick = null)
                Column(Modifier.padding(start = 12.dp)) {
                    Text(m.label, style = MaterialTheme.typography.bodyLarge)
                    MODEL_NOTES[m]?.let { Hint(it) }
                }
            }
        }
    }
}

private val RESET_DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM", Locale.getDefault())

@Composable
private fun CapsSection(
    ui: SettingsUi,
    actions: SettingsActions,
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Spending", style = MaterialTheme.typography.bodyLarge)
        Text(
            "Spent this month: ${usd(ui.spentThisMonthUsd)} of ${usd(ui.monthlyCapUsd)} · " +
                "${ui.callsToday} of ${ui.dailyCalls} calls today",
            Modifier.testTag("spend"),
            style = MaterialTheme.typography.bodyMedium,
        )
        Meter(
            "This month",
            (ui.spentThisMonthUsd / ui.monthlyCapUsd.coerceAtLeast(0.01)).toFloat(),
            "This month: ${usd(ui.spentThisMonthUsd)} of ${usd(ui.monthlyCapUsd)}",
        )
        Meter("Today", ui.callsToday / ui.dailyCalls.coerceAtLeast(1).toFloat(), "Today: ${ui.callsToday} of ${ui.dailyCalls} calls")
        val nextMonth = ui.today.withDayOfMonth(1).plusMonths(1).format(RESET_DAY)
        Hint("At the cap, recipes are read offline until $nextMonth for the month, or until tomorrow for the day.")
        CapField(
            "Monthly cap (US$)",
            String.format(Locale.US, "%.2f", ui.monthlyCapUsd),
            "cap-month",
            help = "Between $0 and $100",
            keyboard = KeyboardType.Decimal,
            onSet = actions.onMonthlyCap,
        )
        CapField(
            "Calls a day",
            ui.dailyCalls.toString(),
            "cap-day",
            help = "0 to 1000 a day (0 turns Claude off)",
            keyboard = KeyboardType.Number,
            onSet = actions.onDailyCalls,
        )
    }
}

/** A progress bar read by TalkBack as one sentence ([description]), not as a bar and a label. */
@Composable
private fun Meter(
    label: String,
    fraction: Float,
    description: String,
) {
    Row(
        Modifier.clearAndSetSemantics { contentDescription = description },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            label,
            Modifier.padding(end = 4.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        LinearProgressIndicator(
            progress = { fraction.coerceIn(0f, 1f) },
            modifier = Modifier.weight(1f).height(8.dp),
            color = if (fraction >= 0.9f) MaterialTheme.colorScheme.tertiary else MaterialTheme.colorScheme.primary,
            trackColor = MaterialTheme.colorScheme.surfaceContainerHighest,
            strokeCap = StrokeCap.Round,
            gapSize = 4.dp,
            drawStopIndicator = {},
        )
    }
}

@Composable
private fun CapField(
    label: String,
    current: String,
    tag: String,
    help: String,
    keyboard: KeyboardType,
    onSet: (String) -> Boolean,
) {
    var text by rememberSaveable(current) { mutableStateOf(current) }
    var bad by rememberSaveable { mutableStateOf(false) }
    val set = { if (text != current) bad = !onSet(text) }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = {
                text = it
                bad = false
            },
            label = { Text(label) },
            isError = bad,
            supportingText = { Text(help) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = keyboard, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { set() }),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.weight(1f).testTag(tag),
        )
        FilledTonalButton(onClick = set, enabled = text != current) { Text("Set") }
    }
}

private val THEME_LABELS = listOf(ThemeMode.SYSTEM to "System", ThemeMode.LIGHT to "Light", ThemeMode.DARK to "Dark")

private val UNIT_LABELS =
    listOf(UnitsSetting.METRIC to "Metric", UnitsSetting.IMPERIAL to "Imperial", UnitsSetting.AS_WRITTEN to "As written")

private fun usd(v: Double) = String.format(Locale.US, "$%.2f", v)

@Composable
private fun AdvancedGroup(
    ui: SettingsUi,
    actions: SettingsActions,
) {
    SwitchRow("Show the debug log", ui.debugLog, actions.onDebugLog)
    if (ui.debugLog) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Hint("Kept in memory only, never sent anywhere. Keys and query strings are removed.")
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.surfaceContainerHighest) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (ui.logLines.isEmpty()) Text("Nothing logged yet", style = MaterialTheme.typography.bodySmall)
                    for (l in ui.logLines) Text(l, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
                }
            }
            TextButton(onClick = actions.onRefreshLog) { Text("Refresh") }
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = actions.onVoiceTest, modifier = Modifier.testTag("open-voice-test")) { Text("Voice test") }
        Hint("An experiment: checks whether hands-free shopping would work on this phone")
    }
    actions.onDebug?.let { open -> OutlinedButton(onClick = open) { Text("Debug tools") } }
}
