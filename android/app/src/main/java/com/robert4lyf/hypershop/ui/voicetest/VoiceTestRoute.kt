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
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.robert4lyf.hypershop.voice.VoiceTestLog
import com.robert4lyf.hypershop.voice.VoiceTestService
import com.robert4lyf.hypershop.voice.VoiceTestTools

/** What the screen shows and does: stateless, for tests. */
data class VoiceTestUi(
    val running: Boolean = false,
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
        VoiceTestUi(running, pack, quick, log),
        VoiceTestActions(
            onCheckPack = { VoiceTestTools.checkPack(context) { pack = it } },
            onDownloadPack = { VoiceTestTools.downloadPack(context) { pack = it } },
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
        LazyColumn(
            Modifier.fillMaxSize().testTag("voice-test"),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(start = 16.dp, end = 16.dp, bottom = 32.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back") }
                    Text("Voice test", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.semantics { heading() })
                }
                Text(
                    "Checks for the voice mode plan, on your phone and headphones. Nothing is ticked and Claude isn't used. " +
                        "Only the words the phone recognised go in the log; no audio is kept.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            item {
                Step("1. Speech pack", "Is English (UK) recognition on the phone itself, so it works without signal?") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = actions.onCheckPack, modifier = Modifier.testTag("check-pack")) { Text("Check") }
                        OutlinedButton(onClick = actions.onDownloadPack) { Text("Download") }
                    }
                    ui.packStatus?.let { Text(it) }
                }
            }
            item {
                Step("2. Quick check", "Tap, then say \"next three\". Shows what was heard and how fast. Try both recognizers.") {
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
                    "Start it, lock the phone and put it in your pocket. Say things like \"next\", \"done\", \"got the milk\", " +
                        "press your headphone button now and then, and leave it 10+ minutes (try a noisy room or a shop). Then come back " +
                        "and share the log. Run it once with each headset.",
                ) {
                    ToggleRow("Use the headset microphone", headsetMic) { headsetMic = it }
                    ToggleRow("On-device recognizer", onDevice) { onDevice = it }
                    SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                        SegmentedButton(
                            selected = segmented,
                            onClick = { segmented = true },
                            shape = SegmentedButtonDefaults.itemShape(0, 2),
                        ) { Text("Long session") }
                        SegmentedButton(
                            selected = !segmented,
                            onClick = { segmented = false },
                            shape = SegmentedButtonDefaults.itemShape(1, 2),
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Log", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                    OutlinedButton(onClick = actions.onShare, modifier = Modifier.testTag("share-log")) { Text("Share log") }
                    androidx.compose.material3.TextButton(onClick = actions.onClear) { Text("Clear") }
                }
            }
            if (ui.log.isEmpty()) item { Text("Nothing yet", style = MaterialTheme.typography.bodySmall) }
            items(ui.log.asReversed()) { line ->
                Text(line, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
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
    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            content()
        }
    }
}

@Composable
private fun ToggleRow(
    label: String,
    checked: Boolean,
    onChange: (Boolean) -> Unit,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
