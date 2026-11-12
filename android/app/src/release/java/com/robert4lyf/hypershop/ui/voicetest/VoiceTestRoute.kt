package com.robert4lyf.hypershop.ui.voicetest

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect

/** Release cannot open the debug harness, even via restored navigation state. */
@Composable
fun VoiceTestRoute(onBack: () -> Unit) {
    LaunchedEffect(Unit) { onBack() }
}
