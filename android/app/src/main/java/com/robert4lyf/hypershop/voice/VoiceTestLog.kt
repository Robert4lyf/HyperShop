package com.robert4lyf.hypershop.voice

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The voice test's log: what was heard, errors, restarts, button presses,
 * battery. Kept in memory for the screen and in a file (no backup) so "Share
 * log" has the whole run. Only words the recognizer returned are written; no
 * audio is kept.
 */
object VoiceTestLog {
    private const val MAX_LINES = 2000
    private val time = SimpleDateFormat("HH:mm:ss", Locale.UK)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    @Volatile private var file: File? = null

    fun init(context: Context) {
        if (file != null) return
        val f = File(context.noBackupFilesDir, "voice-test.log")
        file = f
        if (f.exists()) _lines.value = f.readLines().takeLast(MAX_LINES)
    }

    @Synchronized
    fun log(message: String) {
        val line = "${time.format(Date())} $message"
        _lines.update { (it + line).takeLast(MAX_LINES) }
        runCatching { file?.appendText(line + "\n") }
    }

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
        runCatching { file?.writeText("") }
    }

    fun text(): String = _lines.value.joinToString("\n")
}
