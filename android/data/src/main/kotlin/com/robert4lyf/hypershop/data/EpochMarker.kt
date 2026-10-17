package com.robert4lyf.hypershop.data

import java.io.File
import java.security.SecureRandom
import java.util.UUID

/**
 * The list epoch's restore guard (PLAN §3.6): a marker file in
 * `noBackupFilesDir`, mirrored in `Meta.epochMarker`. A restore brings the
 * database back but not the file, so a missing or different marker means the
 * list was replaced: a new epoch, and watch tracking starts over.
 */
class EpochMarker(
    private val dir: File,
) {
    private val file get() = File(dir, FILE)

    fun read(): String? = runCatching { file.readText().trim() }.getOrNull()?.ifEmpty { null }

    fun write(marker: String) {
        dir.mkdirs()
        file.writeText(marker)
    }

    companion object {
        const val FILE = "epoch-marker"

        /** Positive 31-bit, so it fits a Monkey C Number. */
        fun newEpoch(): Long = (SecureRandom().nextInt() and 0x7fffffff).toLong().coerceAtLeast(1)

        fun newMarker(): String = UUID.randomUUID().toString()
    }
}
