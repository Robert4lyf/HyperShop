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

    /** Lines appended since the file was last rewritten from [_lines]. */
    private var appended = 0
    private val time = SimpleDateFormat("HH:mm:ss", Locale.UK)
    private val _lines = MutableStateFlow<List<String>>(emptyList())
    val lines: StateFlow<List<String>> = _lines

    @Volatile private var file: File? = null

    fun init(context: Context) {
        if (file != null) return
        val f = File(context.noBackupFilesDir, "voice-test.log")
        file = f
        if (f.exists()) {
            val all = f.readLines()
            _lines.value = all.takeLast(MAX_LINES)
            // The file is capped like the lines: plain text in app storage must not grow without bound.
            if (all.size > MAX_LINES) runCatching { f.writeText(_lines.value.joinToString("\n", postfix = "\n")) }
        }
    }

    @Synchronized
    fun log(message: String) {
        val line = "${time.format(Date())} $message"
        _lines.update { (it + line).takeLast(MAX_LINES) }
        appended++
        runCatching {
            if (appended >= MAX_LINES) {
                // Rewritten from the capped lines now and then, so a long test session can't fill the disk.
                appended = 0
                file?.writeText(_lines.value.joinToString("\n", postfix = "\n"))
            } else {
                file?.appendText(line + "\n")
            }
        }
    }

    @Synchronized
    fun clear() {
        _lines.value = emptyList()
        runCatching { file?.writeText("") }
    }

    fun text(): String = _lines.value.joinToString("\n")
}
