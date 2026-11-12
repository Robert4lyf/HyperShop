package com.robert4lyf.hypershop.ui.voicetest

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robert4lyf.hypershop.ui.common.SectionCard
import com.robert4lyf.hypershop.ui.common.basilSegmentedColors
import com.robert4lyf.hypershop.voice.VoiceTestLog
import com.robert4lyf.hypershop.voice.VoiceTestService
import com.robert4lyf.hypershop.voice.VoiceTestTools

/** What the screen shows and does: stateless, for tests. */
data class VoiceTestUi(
    val running: Boolean = false,
    /** The speech pack download is under way, so "Download" is off meanwhile. */
    val downloading: Boolean = false,
    val packStatus: String? = null,
    val quickStatus: String? = null,
    val log: List<String> = emptyList(),
)

data class VoiceTestActions(
    val onCheckPack: () -> Unit = {},
    val onDownloadPack: () -> Unit = {},
    val onQuickCheck: (onDevice: Boolean) -> Unit = {},
    val onSpeak: () -> Unit = {},
    val onStart: (VoiceTestService.Options) -> Unit = {},
    val onStop: () -> Unit = {},
    val onShare: () -> Unit = {},
    val onClear: () -> Unit = {},
)

/** Settings › Advanced › Voice test: the on-phone checks from the voice mode plan (§5, step 0). */
@Composable
fun VoiceTestRoute(onBack: () -> Unit) {
    val context = LocalContext.current
    remember { VoiceTestLog.init(context) }
    val running by VoiceTestService.running.collectAsStateWithLifecycle()
    val log by VoiceTestLog.lines.collectAsStateWithLifecycle()
    var pack by remember { mutableStateOf<String?>(null) }
    var downloading by remember { mutableStateOf(false) }
    var quick by remember { mutableStateOf<String?>(null) }
    var active by remember { mutableStateOf<SpeechRecognizer?>(null) }
    DisposableEffect(Unit) { onDispose { active?.destroy() } }
    var pending by remember { mutableStateOf<(() -> Unit)?>(null) }
    val permissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { granted ->
            if (granted[Manifest.permission.RECORD_AUDIO] == true) {
                pending?.invoke()
            } else {
                VoiceTestLog.log("microphone permission refused")
                quick = "Microphone permission refused — allow it in Android settings"
            }
            pending = null
        }

    /** Runs [action] once the microphone (and notifications, for the service) are allowed. */
    fun withMic(action: () -> Unit) {
        val mic = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        val notes = ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        if (mic && notes) return action()
        pending = action
        permissions.launch(arrayOf(Manifest.permission.RECORD_AUDIO, Manifest.permission.POST_NOTIFICATIONS))
    }
    VoiceTestScreen(
        VoiceTestUi(running, downloading, pack, quick, log),
        VoiceTestActions(
            onCheckPack = { VoiceTestTools.checkPack(context) { pack = it } },
            onDownloadPack = {
                downloading = true
                // Progress reports keep it running; any other report is the end, one way or another.
                VoiceTestTools.downloadPack(context) {
                    pack = it
                    downloading = it.startsWith("Downloading")
                }
            },
            onQuickCheck = { onDevice ->
                withMic {
                    active?.destroy()
                    active = VoiceTestTools.quickCheck(context, onDevice) { quick = it }
                }
            },
            onSpeak = { VoiceTestTools.speak(context, "Next: two onions, carrots, and a pack of basil.") },
            onStart = { o -> withMic { VoiceTestService.start(context, o) } },
            onStop = { VoiceTestService.stop(context) },
            onShare = {
                val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, VoiceTestLog.text())
                context.startActivity(Intent.createChooser(send, "Share the voice test log"))
            },
            onClear = VoiceTestLog::clear,
        ),
        onBack,
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun VoiceTestScreen(
    ui: VoiceTestUi,
    actions: VoiceTestActions,
    onBack: () -> Unit = {},
) {
    var headsetMic by rememberSaveable { mutableStateOf(true) }
    var onDevice by rememberSaveable { mutableStateOf(true) }
    var segmented by rememberSaveable { mutableStateOf(true) }
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        // The cards bring their own 16 dp gutter; the title and the log lines pad themselves.
        LazyColumn(
            Modifier.fillMaxSize().testTag("voice-test"),
            contentPadding = PaddingValues(bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(Modifier.padding(start = 4.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                    Text("Voice test", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                }
            }
            item {
                Step("1. Speech pack", "English (UK), on the phone") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = actions.onCheckPack, modifier = Modifier.testTag("check-pack")) { Text("Check") }
                        OutlinedButton(
                            onClick = actions.onDownloadPack,
                            enabled = !ui.downloading,
                            modifier = Modifier.testTag("download-pack"),
                        ) { Text("Download") }
                    }
                    ui.packStatus?.let { Text(it) }
                }
            }
            item {
                Step("2. Quick check", "Say \"next three\"") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { actions.onQuickCheck(true) }, modifier = Modifier.testTag("quick-on-device")) {
                            Text("On-device")
                        }
                        OutlinedButton(onClick = { actions.onQuickCheck(false) }) { Text("Default") }
                        OutlinedButton(onClick = actions.onSpeak) { Text("Speak a line") }
                    }
                    ui.quickStatus?.let { Text(it) }
                }
            }
            item {
                Step(
                    "3. Background test",
                    "Lock the phone, talk for 10+ minutes, then share the log.",
                ) {
                    ToggleRow("Use the headset microphone", headsetMic) { headsetMic = it }
                    ToggleRow("On-device recognizer", onDevice) { onDevice = it }
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = segmented,
                            // No checkmark: it would push the label sideways on every switch; the fill says which is on.
                            icon = {},
                            onClick = { segmented = true },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                            colors = basilSegmentedColors(),
                        ) { Text("Long session") }
                        SegmentedButton(
                            selected = !segmented,
                            icon = {},
                            onClick = { segmented = false },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
                            colors = basilSegmentedColors(),
                        ) { Text("Restart each phrase") }
                    }
                    if (ui.running) {
                        Button(onClick = actions.onStop, modifier = Modifier.fillMaxWidth().testTag("bg-stop")) {
                            Text("Stop (listening…)")
                        }
                    } else {
                        Button(
                            onClick = {
                                val mode = if (segmented) VoiceTestService.Mode.SEGMENTED else VoiceTestService.Mode.RESTART
                                actions.onStart(VoiceTestService.Options(headsetMic, onDevice, mode))
                            },
                            modifier = Modifier.fillMaxWidth().testTag("bg-start"),
                        ) { Text("Start background test") }
                    }
                }
            }
            item {
                Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = actions.onShare, modifier = Modifier.testTag("share-log")) { Text("Share log") }
                    TextButton(onClick = actions.onClear) { Text("Clear") }
                }
            }
            val gutter = Modifier.padding(horizontal = 16.dp)
            if (ui.log.isEmpty()) item { Text("Nothing yet", gutter, style = MaterialTheme.typography.bodySmall) }
            items(ui.log.asReversed()) { line ->
                Text(line, gutter, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
            }
        }
    }
}

@Composable
private fun Step(
    title: String,
    hint: String,
    content: @Composable () -> Unit,
) {
    SectionCard {
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        content()
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    // The whole row is the switch, so TalkBack reads one control with its label.
    Row(
        Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(value = checked, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = null)
    }
}
