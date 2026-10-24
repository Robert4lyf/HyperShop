package com.robert4lyf.hypershop.importing

import com.robert4lyf.hypershop.core.report.WrongReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * W.4: the "This is wrong" notes, appended to one text file on the phone. Nothing sends it: it leaves only when
 * you share it (the sheet's "Save and share", or Settings). Not in the backup rules' folders, so Android's
 * backup does not carry it either.
 */
class WrongReports(
    private val file: File,
) {
    private val lock = Mutex()

    /** Appends [report] and returns its text, for a share straight after. */
    suspend fun save(report: WrongReport): String =
        io {
            val text = report.text()
            // A note that cannot be written (a full disk) is still the text to share: never a crash over a note.
            runCatching {
                file.parentFile?.mkdirs()
                // Oldest dropped past the cap, at a note's own first line: a forgotten file never grows without
                // end, and "Share them" stays well inside what one share intent can carry.
                val all = read() + text
                val kept = if (all.length <= MAX_CHARS) all else all.takeLast(MAX_CHARS).let { t -> t.substring(firstNote(t)) }
                file.writeText(kept)
            }.onFailure { Timber.w(it, "wrong note not saved") }
            text
        }

    private fun firstNote(t: String): Int = t.indexOf("\n$HEADER").let { if (it < 0) t.length else it + 1 }

    suspend fun all(): String = io { read() }

    suspend fun count(): Int = io { Regex("(?m)^" + Regex.escape(HEADER)).findAll(read()).count() }

    suspend fun clear() = io { file.delete() }

    private fun read() = runCatching { if (file.exists()) file.readText() else "" }.getOrDefault("")

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { lock.withLock { block() } }

    private companion object {
        const val MAX_CHARS = 50_000
        const val HEADER = "--- This is wrong"
    }
}
