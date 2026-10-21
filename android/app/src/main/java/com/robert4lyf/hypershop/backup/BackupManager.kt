package com.robert4lyf.hypershop.backup

import android.content.Context
import android.net.Uri
import com.robert4lyf.hypershop.catalog.EntryEdit
import com.robert4lyf.hypershop.claude.ClaudeModel
import com.robert4lyf.hypershop.core.merge.UnitsSetting
import com.robert4lyf.hypershop.data.BackupData
import com.robert4lyf.hypershop.data.BackupValidator
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.settings.ListMode
import com.robert4lyf.hypershop.settings.ListView
import com.robert4lyf.hypershop.settings.ThemeMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import timber.log.Timber
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

/**
 * Writes and reads backup files through the Storage Access Framework (the user
 * picks where), and restores one through the repository. Nothing here keeps a
 * copy anywhere else.
 */
class BackupManager(
    private val c: AppContainer,
    private val appVersion: String? = null,
) {
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
        val problems = BackupValidator.validate(file.data)
        if (problems.isNotEmpty()) return Inspection.Broken(problems)
        return Inspection.Ok(file, summary(file))
    }

    /**
     * Replaces this phone's data with [f]: the database in one transaction (a
     * new list epoch), then the settings, then the ingredient library. Returns
     * the validator's problems when the database refused it, else nothing.
     */
    suspend fun restore(f: BackupFile): List<String> {
        val problems = c.repository.restoreBackup(f.data, c.epochMarker)
        if (problems.isNotEmpty()) return problems
        c.pendingReviews.value = emptyList()
        val s = c.settings
        val st = f.settings
        st.units?.let { u -> runCatching { UnitsSetting.valueOf(u) }.getOrNull()?.let { s.setUnits(it) } }
        st.theme?.let { t -> runCatching { ThemeMode.valueOf(t) }.getOrNull()?.let { s.setTheme(it) } }
        st.listView?.let { v -> runCatching { ListView.valueOf(v) }.getOrNull()?.let { s.setListView(it) } }
        st.claudeModel?.let { m -> ClaudeModel.fromId(m)?.let { s.setModel(it) } }
        st.monthlyCapUsd?.let { s.setMonthlyCap(it) }
        st.dailyCalls?.let { s.setDailyCalls(it) }
        // A restored list is planned, not mid-shop.
        s.setListMode(ListMode.PLAN)
        c.library.replaceAll(f.libraryEdits)
        // The library edits may have changed the catalogue; the list's keys follow.
        c.repository.rekeyIfCatalogChanged()
        return emptyList()
    }

    companion object {
        /** Well above any real backup (a 500-recipe library is a few MB). */
        const val MAX_BYTES = 64 * 1024 * 1024

        fun suggestedName(createdAt: Long): String {
            val d = java.time.Instant.ofEpochMilli(createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            return "hypershop-backup-$d.json"
        }
    }
}
