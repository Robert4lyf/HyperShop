package com.robert4lyf.hypershop.backup

import android.content.Context
import android.net.Uri
import com.robert4lyf.hypershop.catalog.EntryEdit
import com.robert4lyf.hypershop.claude.ClaudeModel
import com.robert4lyf.hypershop.core.merge.UnitsSetting
import com.robert4lyf.hypershop.data.BackupData
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.settings.ListView
import com.robert4lyf.hypershop.settings.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
import java.io.File
import java.io.IOException

/**
 * The file (docs/design/simplify.md phase 4): a version, when and by what it
 * was written, the database's user data, the settings worth carrying to
 * another phone, and the ingredient-library edits. Never the Claude key, the
 * Keystore, the debug log or anything about this install's watch link.
 */
@Serializable
data class BackupFile(
    val format: String = FORMAT,
    val version: Int = VERSION,
    val createdAt: Long,
    val appVersion: String? = null,
    val data: BackupData,
    val settings: BackupSettings = BackupSettings(),
    val libraryEdits: List<EntryEdit> = emptyList(),
) {
    companion object {
        const val FORMAT = "hypershop-backup"
        const val VERSION = 1
    }
}

/** Settings as their stored names; null means "leave the phone's as it is". */
@Serializable
data class BackupSettings(
    val units: String? = null,
    val theme: String? = null,
    val listView: String? = null,
    val claudeModel: String? = null,
    val monthlyCapUsd: Double? = null,
    val dailyCalls: Int? = null,
)

/** What a backup holds, for the confirmation before a restore. */
data class BackupSummary(
    val createdAt: Long,
    val appVersion: String?,
    val recipes: Int,
    val itemsOnList: Int,
)

/** Reading a backup file: one of these. */
sealed interface Inspection {
    data class Ok(
        val file: BackupFile,
        val summary: BackupSummary,
    ) : Inspection

    /** Not a HyperShop backup at all (or not JSON). */
    data object NotABackup : Inspection

    /** Written by a newer HyperShop than this one. */
    data class Newer(
        val version: Int,
    ) : Inspection

    /** A HyperShop backup whose contents don't hold together; [problems] says where. */
    data class Broken(
        val problems: List<String>,
    ) : Inspection

    data object Unreadable : Inspection
}

/** How a restore ended. */
sealed interface RestoreOutcome {
    /** Done; [before] is what was there, for Undo. */
    data class Restored(
        val before: BackupFile,
    ) : RestoreOutcome

    /** The database refused the file before anything was written. */
    data class Refused(
        val problems: List<String>,
    ) : RestoreOutcome

    /** It failed after the database had changed: put back as it was, or ([rolledBack] false) left for the next start. */
    data class Failed(
        val rolledBack: Boolean,
    ) : RestoreOutcome
}

/**
 * Writes and reads backup files through the Storage Access Framework (the user
 * picks where), and restores one through the repository. Nothing here keeps a
 * copy anywhere else.
 */
class BackupManager(
    private val c: AppContainer,
    private val appVersion: String? = null,
    private val journal: RestoreJournal = RestoreJournal(File(c.context.noBackupFilesDir, "restore")),
) {
    /** One restore at a time, and none while [recover] runs. */
    private val lock = Mutex()

    /** A test's hook at each stage of a restore. */
    internal var beforeStage: suspend (Stage) -> Unit = {}
    private val json = Json { ignoreUnknownKeys = true }

    /** Every field written, defaults included: a backup must say what it is (format, version) and leave nothing implied. */
    private val writer = Json { encodeDefaults = true }

    /** The current data as a file value. */
    suspend fun snapshot(): BackupFile {
        val s = c.settings
        return BackupFile(
            createdAt = c.clock(),
            appVersion = appVersion,
            data = c.repository.exportBackup(),
            settings =
                BackupSettings(
                    units = s.units.first().name,
                    theme = s.theme.first().name,
                    listView = s.listView.first().name,
                    claudeModel = s.model.first().id,
                    monthlyCapUsd = s.monthlyCapUsd.first(),
                    dailyCalls = s.dailyCalls.first(),
                ),
            libraryEdits = c.library.edits.value.values.sortedBy { it.id },
        )
    }

    fun summary(f: BackupFile): BackupSummary {
        val live = f.data.items.filter { it.deletedAt == null }.map { it.id }.toSet()
        val recipes = f.data.recipes.count { it.lastAddedAt != null }
        return BackupSummary(f.createdAt, f.appVersion, recipes, live.size)
    }

    /** Writes the current data to [uri]. Null on success, else what went wrong in a sentence. */
    suspend fun writeTo(
        context: Context,
        uri: Uri,
    ): Result<BackupSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                val file = snapshot()
                val text = writer.encodeToString(BackupFile.serializer(), file)
                // "wt" truncates what was there; a provider that refuses the mode (Drive did) gets plain "w".
                val out =
                    try {
                        context.contentResolver.openOutputStream(uri, "wt")
                    } catch (e: Exception) {
                        null
                    } ?: context.contentResolver.openOutputStream(uri, "w") ?: throw IOException("could not open the file")
                out.use { it.write(text.toByteArray()) }
                summary(file)
            }.onFailure { Timber.w(it, "Backup failed") }
        }

    /** Reads and checks [uri] without changing anything. */
    suspend fun inspect(
        context: Context,
        uri: Uri,
    ): Inspection =
        withContext(Dispatchers.IO) {
            val bytes =
                try {
                    val stream = context.contentResolver.openInputStream(uri) ?: return@withContext Inspection.Unreadable
                    stream.use { it.readNBytes(MAX_BYTES + 1) }
                } catch (e: IOException) {
                    Timber.w(e, "Backup unreadable")
                    return@withContext Inspection.Unreadable
                } catch (e: SecurityException) {
                    return@withContext Inspection.Unreadable
                }
            if (bytes.size > MAX_BYTES) return@withContext Inspection.Broken(listOf("the file is larger than a backup can be"))
            val text = String(bytes)
            inspectText(text)
        }

    fun inspectText(text: String): Inspection {
        val head =
            try {
                json.parseToJsonElement(text).jsonObject
            } catch (e: SerializationException) {
                return Inspection.NotABackup
            } catch (e: IllegalArgumentException) {
                return Inspection.NotABackup
            }
        if (head["format"]?.jsonPrimitive?.content != BackupFile.FORMAT) return Inspection.NotABackup
        val version = head["version"]?.jsonPrimitive?.content?.toIntOrNull() ?: return Inspection.NotABackup
        if (version > BackupFile.VERSION) return Inspection.Newer(version)
        val file =
            try {
                json.decodeFromString(BackupFile.serializer(), text)
            } catch (e: SerializationException) {
                return Inspection.Broken(listOf("a field is missing or of the wrong kind"))
            } catch (e: IllegalArgumentException) {
                return Inspection.Broken(listOf("a field is missing or of the wrong kind"))
            }
        val problems = BackupFileValidator.validate(file)
        if (problems.isNotEmpty()) return Inspection.Broken(problems)
        return Inspection.Ok(file, summary(file))
    }

    /**
     * Replaces this phone's data with [f], journalled ([RestoreJournal]) so the
     * process dying part-way is finished at the next start ([recover]). A refusal
     * by the database changes nothing; a failure after it is rolled back to what
     * was there, and only when that fails too is the restore left for [recover].
     */
    suspend fun restore(f: BackupFile): RestoreOutcome =
        lock.withLock {
            // Checked here too, not only by inspect(): nothing destructive starts on a file that would be refused.
            BackupFileValidator.validate(f).takeIf { it.isNotEmpty() }?.let { return@withLock RestoreOutcome.Refused(it) }
            val before = snapshot()
            journal.begin(f, before)
            val problems =
                try {
                    apply(f)
                } catch (e: Exception) {
                    Timber.e(e, "Restore failed part-way; rolling back")
                    val back = runCatching { apply(before).isEmpty() }.onFailure { Timber.e(it, "Rollback failed") }.getOrDefault(false)
                    if (back) journal.end()
                    return@withLock RestoreOutcome.Failed(rolledBack = back)
                }
            journal.end()
            if (problems.isEmpty()) RestoreOutcome.Restored(before) else RestoreOutcome.Refused(problems)
        }

    /**
     * At start-up, before anything else reads the data: a restore the process
     * died in is rolled forward from its copy of the file, or back to the copy
     * of what was there when the file can't be read. Null when there was none.
     */
    suspend fun recover(): Recovery? =
        lock.withLock {
            val p = journal.pending() ?: return@withLock null
            val target = p.target?.takeIf { BackupFileValidator.validate(it).isEmpty() }
            val outcome =
                when {
                    target != null && tryApply(target) -> Recovery.FINISHED
                    p.previous != null && tryApply(p.previous) -> Recovery.ROLLED_BACK
                    // Kept for the next start; nothing here can say which half is current.
                    else -> return@withLock Recovery.STUCK
                }
            journal.end()
            outcome
        }

    /** The user chose to keep the data as it is after a restore that could not be finished. */
    suspend fun abandonInterruptedRestore() = lock.withLock { journal.end() }

    private suspend fun tryApply(f: BackupFile): Boolean =
        runCatching { apply(f).isEmpty() }.onFailure { Timber.e(it, "Restore recovery failed") }.getOrDefault(false)

    /** Every stage replaces what it writes, so running it twice is the same as once. */
    private suspend fun apply(f: BackupFile): List<String> {
        beforeStage(Stage.DATABASE)
        val problems = c.repository.restoreBackup(f.data, c.epochMarker)
        if (problems.isNotEmpty()) return problems
        c.pendingReviews.value = emptyList()
        beforeStage(Stage.SETTINGS)
        val st = f.settings
        c.settings.restore(
            units = st.units?.let { u -> UnitsSetting.entries.firstOrNull { it.name == u } },
            theme = st.theme?.let { t -> ThemeMode.entries.firstOrNull { it.name == t } },
            listView = st.listView?.let { v -> ListView.entries.firstOrNull { it.name == v } },
            model = st.claudeModel?.let { ClaudeModel.fromId(it) },
            monthlyCapUsd = st.monthlyCapUsd,
            dailyCalls = st.dailyCalls,
        )
        beforeStage(Stage.LIBRARY)
        c.library.replaceAll(f.libraryEdits)
        beforeStage(Stage.REKEY)
        // The library edits may have changed the catalogue; the list's keys follow.
        c.repository.rekeyIfCatalogChanged()
        beforeStage(Stage.DONE)
        return emptyList()
    }

    /** Where a restore is; tests stop the "process" at each one. */
    enum class Stage { DATABASE, SETTINGS, LIBRARY, REKEY, DONE }

    /** What [recover] did, for the one-off notice. */
    enum class Recovery { FINISHED, ROLLED_BACK, STUCK }

    companion object {
        /** Well above any real backup (a 500-recipe library is a few MB). */
        const val MAX_BYTES = 64 * 1024 * 1024

        fun suggestedName(createdAt: Long): String {
            val d = java.time.Instant.ofEpochMilli(createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            return "hypershop-backup-$d.json"
        }
    }
}
