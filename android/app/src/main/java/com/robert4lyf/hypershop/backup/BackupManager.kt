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
    /** Photo files there will be (in the backup, or already on this phone). */
    val photos: Int = 0,
    /**
     * Photos recipes name that are neither, and cannot be fetched again from a page: they will be
     * missing. Writing, the ones that were not on this phone to put in the file.
     */
    val missingPhotos: Int = 0,
)

/** An archive's photos, checked and waiting outside the photo folder until the restore is confirmed. */
data class StagedPhotos(
    val dir: File,
    val names: Set<String>,
)

/** Reading a backup file: one of these. */
sealed interface Inspection {
    data class Ok(
        val file: BackupFile,
        val summary: BackupSummary,
        /** An archive's photos; null for a plain JSON backup, which has none. */
        val photos: StagedPhotos? = null,
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

    /** [f] in numbers; [available] is the photo files there will be (null: count every one named as there). */
    fun summary(
        f: BackupFile,
        available: Set<String>? = null,
    ): BackupSummary {
        val live = f.data.items.filter { it.deletedAt == null }.map { it.id }.toSet()
        val recipes = f.data.recipes.count { it.lastAddedAt != null }
        val named = f.data.recipeNotes.filter { it.photo != null }
        val have = named.mapNotNull { it.photo }.filter { available == null || it in available }.toSet()
        val missing = named.filter { available != null && it.photo !in available && it.photoUrl == null }.mapNotNull { it.photo }.toSet()
        return BackupSummary(f.createdAt, f.appVersion, recipes, live.size, have.size, missing.size)
    }

    /**
     * Writes the current data and every photo a recipe shows to [uri], as an archive ([BackupArchive]).
     * The data and the photo folder are read with no photo changing ([RecipePhotos.exclusive]), so the file
     * holds the photos its recipes name. A photo named but not on this phone is counted in
     * [BackupSummary.missingPhotos] and said, not skipped in silence.
     */
    suspend fun writeTo(
        context: Context,
        uri: Uri,
    ): Result<BackupSummary> =
        withContext(Dispatchers.IO) {
            runCatching {
                c.recipePhotos.exclusive {
                    val file = snapshot()
                    val names = file.data.recipeNotes.mapNotNull { it.photo }.toSortedSet()
                    // Only whole photos: one damaged file here would make the reader refuse the entire backup.
                    // One that is not is counted as missing, and said.
                    val files = names.mapNotNull { c.recipePhotos.file(it) }.filter { BackupArchive.isIntact(it) }
                    // "wt" truncates what was there; a provider that refuses the mode (Drive did) gets plain "w".
                    val out =
                        try {
                            context.contentResolver.openOutputStream(uri, "wt")
                        } catch (e: Exception) {
                            null
                        } ?: context.contentResolver.openOutputStream(uri, "w") ?: throw IOException("could not open the file")
                    out.use { BackupArchive.write(it, file.createdAt, jsonOf(file).toByteArray(), files) }
                    val s = summary(file, files.map { it.name }.toSet())
                    s.copy(missingPhotos = names.size - files.size)
                }
            }.onFailure { Timber.w(it, "Backup failed") }
        }

    /** [f] as a plain backup's JSON: what an archive holds as its `backup.json`. */
    fun jsonOf(f: BackupFile): String = writer.encodeToString(BackupFile.serializer(), f)

    /**
     * Where archives' photos wait between [inspect] and [restore]: a folder of its own for each inspection
     * ([StagedPhotos.dir]), so inspecting another file, or one that fails, never touches the photos a
     * confirmation still waiting would restore.
     */
    private val staging get() = File(c.context.cacheDir, "restore-photos")

    /** [photos] are not going to be restored: they go, and nothing else staged does. */
    fun discardStaged(photos: StagedPhotos?) {
        photos?.dir?.takeIf { it.parentFile == staging }?.deleteRecursively()
    }

    /** At start-up: no inspection outlives the process, so whatever is staged is left over. */
    fun discardAllStaged() {
        staging.deleteRecursively()
    }

    /** Reads and checks [uri] without changing anything. */
    suspend fun inspect(
        context: Context,
        uri: Uri,
    ): Inspection =
        withContext(Dispatchers.IO) {
            try {
                val stream = context.contentResolver.openInputStream(uri) ?: return@withContext Inspection.Unreadable
                stream.buffered().use { input ->
                    input.mark(4)
                    val head = input.readNBytes(4)
                    input.reset()
                    if (BackupArchive.isArchive(head)) return@use inspectArchive(input)
                    val bytes = input.readNBytes(MAX_JSON_BYTES + 1)
                    if (bytes.size > MAX_JSON_BYTES) return@use Inspection.Broken(listOf("the file is larger than a backup can be"))
                    withLocalPhotos(inspectText(String(bytes)))
                }
            } catch (e: IOException) {
                Timber.w(e, "Backup unreadable")
                Inspection.Unreadable
            } catch (e: SecurityException) {
                Inspection.Unreadable
            }
        }

    /** An archive: its photos staged, its JSON checked as any backup's. */
    private fun inspectArchive(input: java.io.InputStream): Inspection {
        val dir = File(staging, java.util.UUID.randomUUID().toString())
        val read = BackupArchive.read(input, dir, MAX_JSON_BYTES.toLong())
        val ok =
            when (read) {
                is BackupArchive.Read.Newer -> return Inspection.Newer(read.version)
                is BackupArchive.Read.Broken -> return Inspection.Broken(listOf(read.problem))
                is BackupArchive.Read.Ok -> read
            }
        val i = inspectText(ok.backup)
        if (i !is Inspection.Ok) {
            dir.deleteRecursively()
            return if (i is Inspection.NotABackup) Inspection.Broken(listOf("the archive's backup is not one")) else i
        }
        val available = ok.photos + localPhotos(i.file)
        return i.copy(summary = summary(i.file, available), photos = StagedPhotos(dir, ok.photos))
    }

    /** A plain JSON backup carries no photos: the ones it names count only if they are on this phone already. */
    private fun withLocalPhotos(i: Inspection): Inspection =
        if (i is Inspection.Ok) i.copy(summary = summary(i.file, localPhotos(i.file))) else i

    /** The photos [f] names that are already in this phone's photo folder (the same name is the same picture). */
    private fun localPhotos(f: BackupFile): Set<String> = intactHere(f.data.recipeNotes.mapNotNull { it.photo }.toSet())

    /** Which of [names] are whole photos in this phone's folder: there, hashing to their name, a JPEG. Read once each. */
    private fun intactHere(names: Set<String>): Set<String> =
        names.filter { n -> c.recipePhotos.file(n)?.let { BackupArchive.isIntact(it) } == true }.toSet()

    /** The two header fields alone, read without building the whole tree (review D.37). */
    @Serializable
    private data class Header(
        val format: String? = null,
        val version: Int? = null,
    )

    fun inspectText(text: String): Inspection {
        val head =
            try {
                json.decodeFromString(Header.serializer(), text)
            } catch (e: SerializationException) {
                return Inspection.NotABackup
            } catch (e: IllegalArgumentException) {
                return Inspection.NotABackup
            }
        // A header field of the wrong shape ({"format": {}}) is not a backup, not a crash.
        if (head.format != BackupFile.FORMAT) return Inspection.NotABackup
        val version = head.version ?: return Inspection.NotABackup
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
    suspend fun restore(
        f: BackupFile,
        photos: StagedPhotos? = null,
    ): RestoreOutcome =
        // Off the main thread: checking every photo (a hash and a size read each) is real work.
        withContext(Dispatchers.IO) { restoreOnThisThread(f, photos) }

    private suspend fun restoreOnThisThread(
        f: BackupFile,
        photos: StagedPhotos?,
    ): RestoreOutcome =
        try {
            lock.withLock {
                // Checked here too, not only by inspect(): nothing destructive starts on a file that would be refused.
                BackupFileValidator.validate(f).takeIf { it.isNotEmpty() }?.let { return@withLock RestoreOutcome.Refused(it) }
                // No photo changes, and no sweep, from here to the end: the folder holds every photo either side names.
                c.recipePhotos.exclusive { restoreLocked(f, photos) }
            }
        } finally {
            discardStaged(photos)
        }

    private suspend fun restoreLocked(
        f: BackupFile,
        photos: StagedPhotos?,
    ): RestoreOutcome {
        // The archive's photos go into the folder before the journal: a file there that nothing names yet is
        // harmless (the next start's sweep takes it if the restore never happens), and once the journal
        // exists, finishing the restore after a crash needs nothing that is not already in place. The
        // photos in the folder now stay too, so a rollback (or Undo) finds what the old data names.
        // Only the photos a recipe in the file shows: an archive listing others brings nothing in with them.
        val named = f.data.recipeNotes.mapNotNull { it.photo }.toSet()
        for (name in photos?.names.orEmpty().intersect(named)) {
            val staged = File(photos!!.dir, name)
            // Checked again as it goes in: what waited in the cache since the inspection is still that photo.
            if (!BackupArchive.isIntact(staged) || !c.recipePhotos.adopt(staged)) {
                Timber.e("Restore: photo %s could not be put in place", name)
                return RestoreOutcome.Failed(rolledBack = true)
            }
        }
        val target = withAvailablePhotos(f)
        val before = snapshot()
        journal.begin(target, before)
        val problems =
            try {
                apply(target)
            } catch (e: Exception) {
                Timber.e(e, "Restore failed part-way; rolling back")
                val back = runCatching { apply(before).isEmpty() }.onFailure { Timber.e(it, "Rollback failed") }.getOrDefault(false)
                if (back) journal.end()
                return RestoreOutcome.Failed(rolledBack = back)
            }
        journal.end()
        return if (problems.isEmpty()) RestoreOutcome.Restored(before) else RestoreOutcome.Refused(problems)
    }

    /**
     * [f] with every photo name that will not be a whole file here taken off its recipe, so the database
     * never names a missing or damaged file. A photo from a recipe's page keeps its address, and is fetched again.
     */
    private fun withAvailablePhotos(f: BackupFile): BackupFile {
        val notes = f.data.recipeNotes
        // Whole, not merely there: a damaged file is not a photo to point a recipe at.
        val whole = intactHere(notes.mapNotNull { it.photo }.toSet())
        if (notes.all { it.photo == null || it.photo in whole }) return f
        val fixed = notes.map { n -> if (n.photo != null && n.photo !in whole) n.copy(photo = null) else n }
        return f.copy(data = f.data.copy(recipeNotes = fixed))
    }

    /**
     * At start-up, before anything else reads the data: a restore the process
     * died in is rolled forward from its copy of the file, or back to the copy
     * of what was there when the file can't be read. Null when there was none.
     */
    suspend fun recover(): Recovery? =
        lock.withLock {
            // Both sides' photos were put in the folder before the journal was written; nothing deletes one
            // until this is over (the sweep runs after it, and never while a restore is still pending).
            c.recipePhotos.exclusive {
                val p = journal.pending() ?: return@exclusive null
                val target = p.target?.takeIf { BackupFileValidator.validate(it).isEmpty() }
                val outcome =
                    when {
                        target != null && tryApply(target) -> Recovery.FINISHED
                        p.previous != null && tryApply(p.previous) -> Recovery.ROLLED_BACK
                        // Kept for the next start; nothing here can say which half is current.
                        else -> return@exclusive Recovery.STUCK
                    }
                journal.end()
                outcome
            }
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

        /** The JSON alone: a real backup is a few megabytes; the archive's photos have their own budget. */
        const val MAX_JSON_BYTES = 16 * 1024 * 1024

        fun suggestedName(createdAt: Long): String {
            val d = java.time.Instant.ofEpochMilli(createdAt).atZone(java.time.ZoneId.systemDefault()).toLocalDate()
            return "hypershop-backup-$d.zip"
        }
    }
}
