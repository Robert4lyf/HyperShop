package com.robert4lyf.hypershop.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.robert4lyf.hypershop.di.Startup
import kotlinx.coroutines.delay

/**
 * What shows before the list may be touched. Start-up is normally over before
 * the first frame, so the spinner only appears if it is still going after a
 * moment (a restore being finished, a catalogue re-key). A restore that could
 * not be finished stops here and asks, rather than opening on half of it.
 */
@Composable
fun StartupScreen(
    startup: Startup,
    onRetry: () -> Unit,
    onKeep: () -> Unit,
) {
    Surface(Modifier.fillMaxSize().testTag("startup"), color = MaterialTheme.colorScheme.background) {
        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(24.dp), contentAlignment = Alignment.Center) {
            if (startup == Startup.Stuck) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text(
                        "A restore didn't finish",
                        style = MaterialTheme.typography.headlineSmall,
                        modifier = Modifier.semantics { heading() },
                    )
                    Text(
                        "HyperShop was closed while restoring a backup and couldn't complete it. " +
                            "Try again, or keep your data as it is now and restore the backup file again later.",
                        style = MaterialTheme.typography.bodyMedium,
                        textAlign = TextAlign.Center,
                    )
                    Button(onClick = onRetry, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("startup-retry")) {
                        Text("Try again")
                    }
                    OutlinedButton(onClick = onKeep, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp).testTag("startup-keep")) {
                        Text("Keep my data as it is")
                    }
                }
            } else {
                var slow by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(SPINNER_AFTER_MS)
                    slow = true
                }
                if (slow) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        CircularProgressIndicator()
                        Text(
                            if (startup == Startup.Recovering) "Getting your list ready…" else "Starting…",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        }
    }
}

private const val SPINNER_AFTER_MS = 400L
