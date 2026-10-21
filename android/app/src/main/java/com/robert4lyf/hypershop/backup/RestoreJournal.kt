package com.robert4lyf.hypershop.backup

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * What makes a restore survive the process dying half-way (KNOWN_ISSUES #14).
 * A restore writes three places — the database, the settings store and the
 * ingredient-library file — and no transaction spans them. So before the
 * first is touched, the file being restored and a copy of what was there are
 * written here and flushed, and only then the journal that says "a restore is
 * under way". Each stage is idempotent (it replaces, never adds), so the next
 * start rolls the restore forward from [target] and nothing is left half one
 * and half the other. It lives in `noBackupFilesDir`: a journal carried to
 * another phone by Android's own backup would replay a restore nobody asked for.
 */
class RestoreJournal(
    private val dir: File,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val writer = Json { encodeDefaults = true }
    private val journal get() = File(dir, "journal")
    private val target get() = File(dir, "target.json")
    private val previous get() = File(dir, "previous.json")

    /** Both copies first, then the journal: a journal on disk always has its files. */
    fun begin(
        to: BackupFile,
        from: BackupFile,
    ) {
        dir.mkdirs()
        journal.delete()
        writeDurably(previous, writer.encodeToString(BackupFile.serializer(), from))
        writeDurably(target, writer.encodeToString(BackupFile.serializer(), to))
        writeDurably(journal, MARK)
    }

    /** The restore is over, done or rolled back: the journal goes first, then the copies. */
    fun end() {
        journal.delete()
        target.delete()
        previous.delete()
    }

    /** A restore the process died in, or null. */
    fun pending(): Pending? {
        if (!journal.exists()) {
            // Copies with no journal are a begin() that never finished: nothing was touched.
            if (target.exists() || previous.exists()) end()
            return null
        }
        return Pending(read(target), read(previous))
    }

    /** What an interrupted restore was doing; either side is null when its copy can't be read. */
    data class Pending(
        val target: BackupFile?,
        val previous: BackupFile?,
    )

    private fun read(f: File): BackupFile? =
        try {
            json.decodeFromString(BackupFile.serializer(), f.readText())
        } catch (e: IOException) {
            Timber.w(e, "Restore journal: %s unreadable", f.name)
            null
        } catch (e: SerializationException) {
            Timber.w(e, "Restore journal: %s damaged", f.name)
            null
        } catch (e: IllegalArgumentException) {
            Timber.w(e, "Restore journal: %s damaged", f.name)
            null
        }

    private fun writeDurably(
        f: File,
        text: String,
    ) {
        val tmp = File(dir, f.name + ".tmp")
        FileOutputStream(tmp).use { out ->
            out.write(text.toByteArray())
            out.fd.sync()
        }
        if (!tmp.renameTo(f)) {
            f.delete()
            if (!tmp.renameTo(f)) throw IOException("could not write ${f.name}")
        }
    }

    private companion object {
        const val MARK = "restoring"
    }
}
