package com.robert4lyf.hypershop.importing

import com.robert4lyf.hypershop.core.report.WrongReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
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
            file.parentFile?.mkdirs()
            val text = report.text()
            // Oldest dropped past the cap: a forgotten file never grows without end.
            val kept = (read() + text).takeLast(MAX_CHARS)
            file.writeText(kept.substring(kept.indexOf("--- ").coerceAtLeast(0)))
            text
        }

    suspend fun all(): String = io { read() }

    suspend fun count(): Int = io { Regex("""(?m)^--- This is wrong""").findAll(read()).count() }

    suspend fun clear() = io { file.delete() }

    private fun read() = if (file.exists()) file.readText() else ""

    private suspend fun <T> io(block: () -> T): T = withContext(Dispatchers.IO) { lock.withLock { block() } }

    private companion object {
        const val MAX_CHARS = 200_000
    }
}
