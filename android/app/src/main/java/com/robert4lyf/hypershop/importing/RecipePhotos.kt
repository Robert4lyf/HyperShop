package com.robert4lyf.hypershop.importing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import com.robert4lyf.hypershop.data.BackupValidator
import com.robert4lyf.hypershop.data.ListRepository
import com.robert4lyf.hypershop.data.RecipeNoteEntity
import com.robert4lyf.hypershop.extract.Extractor
import com.robert4lyf.hypershop.extract.FetchResult
import com.robert4lyf.hypershop.extract.SafeFetcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.math.max

/**
 * The photos of saved recipes: the recipe page's own photo, fetched once through
 * the [SafeFetcher] (an image, from a public https address), or one chosen from
 * the phone's photos. Each is scaled to at most [MAX_EDGE] pixels and kept as a
 * JPEG in the app's own storage, named by a hash of its contents, so the list of
 * recipes never touches the network to draw one. The file name, and where it came
 * from, are the recipe's [RecipeNoteEntity] (written through [ListRepository]).
 *
 * Files are named by their content, so two recipes with the same picture share one,
 * and a file is never changed once written. Ownership is the database's, and nothing
 * but [sweep] deletes a file: at start-up, before the app is ready, after any
 * interrupted restore is resolved, it takes what no recipe names. Deleting at the
 * moment a photo is replaced looked tidier and was not safe: the same file can be
 * another recipe's, the other side of an interrupted restore's, or what a restore's
 * Undo would put back. Every change to a recipe's photo, and the sweep, runs under
 * one lock, which a backup and a restore hold too ([exclusive]).
 */
class RecipePhotos(
    private val context: Context,
    private val fetcher: SafeFetcher,
    private val repository: ListRepository,
) {
    /**
     * One photo change at a time (and one fetch: a long library opening for the first time is not
     * 50 downloads at once). Also what keeps a sweep, a backup and a restore apart from a change.
     */
    private val lock = Mutex()

    private val dir get() = File(context.filesDir, DIR)

    /**
     * Each saved recipe's photo on this phone, by its library key: what every screen that shows a recipe
     * draws it with ([com.robert4lyf.hypershop.ui.recipes.RecipeArt]).
     */
    fun paths(): Flow<Map<String, String>> =
        repository.observeRecipeNotes()
            .map { notes -> notes.mapNotNull { (key, n) -> file(n.photo)?.let { key to it.path } }.toMap() }
            .flowOn(Dispatchers.IO)

    /** The photo on this phone for the recipe saved as [key], or null. */
    suspend fun pathOf(key: String): String? = withContext(Dispatchers.IO) { file(repository.recipeNote(key)?.photo)?.path }

    /** [block] with no photo changed or deleted meanwhile: a backup reading the folder, a restore filling it. */
    suspend fun <T> exclusive(block: suspend () -> T): T = lock.withLock { block() }

    /**
     * Deletes what nothing uses: files no recipe shows (replaced or removed, left by a change that failed
     * before its database write, or by a restore that brought other photos) and half-written ones. Run at
     * start-up once any interrupted restore is resolved and before anything can change a photo; [keep] is
     * spared too.
     */
    suspend fun sweep(keep: Set<String> = emptySet()): Int =
        lock.withLock {
            val used = repository.photosInUse() + keep
            withContext(Dispatchers.IO) {
                dir.listFiles().orEmpty().count { f -> f.name !in used && f.delete() }
            }
        }

    /** The photo file for [name], or null when there is none (or it is not one of ours). */
    fun file(name: String?): File? = name?.takeIf { BackupValidator.PHOTO_NAME.matches(it) }?.let { File(dir, it) }?.takeIf { it.isFile }

    /**
     * A recipe went into the library from a page that has a photo: remember it, and fetch it now unless a
     * photo was already chosen. False when it could not be fetched (it is tried again by [fill]).
     */
    suspend fun offer(
        key: String,
        url: String,
    ): Boolean {
        repository.offerRecipePhoto(key, url)
        val n = repository.recipeNote(key) ?: return false
        if (file(n.photo) != null || n.photoUrl != url) return false
        return download(key, url)
    }

    /**
     * Fetches the photos that are known but not on this phone: after a restore onto another one (the backup
     * carries addresses, not images), or an offer that failed offline. One pass, in order, quietly.
     */
    suspend fun fill(notes: Collection<RecipeNoteEntity>) {
        for (n in notes) {
            val url = n.photoUrl ?: continue
            if (file(n.photo) == null) download(n.libraryKey, url)
        }
    }

    /**
     * "Use the recipe page's photo" on a recipe saved before photos were kept: the page is fetched again
     * (as a share would), its photo found and fetched. False when the page or its photo can't be had.
     */
    suspend fun fromPage(
        key: String,
        pageUrl: String,
    ): Boolean {
        val page = fetcher.fetch(pageUrl) as? FetchResult.Ok ?: return false
        val html = if (page.contentType == "application/ld+json") Extractor.wrapLdJson(page.body) else page.body
        val image = withContext(Dispatchers.Default) { Extractor.extract(html, page.finalUrl).image } ?: return false
        // Read and written under the lock: a photo chosen meanwhile is not put back to the one before it.
        lock.withLock { repository.setRecipePhoto(key, repository.recipeNote(key)?.photo, image) }
        return download(key, image)
    }

    /** A photo chosen from the phone's photos (the system picker: no permission needed). */
    suspend fun pick(
        key: String,
        uri: Uri,
    ): Boolean =
        lock.withLock {
            val bytes =
                withContext(Dispatchers.IO) {
                    runCatching {
                        context.contentResolver.openInputStream(uri)?.use { s ->
                            val buf = s.readNBytes((MAX_PICKED_BYTES + 1).toInt())
                            buf.takeIf { it.size <= MAX_PICKED_BYTES }
                        }
                    }.getOrNull()
                } ?: return@withLock false
            val name = store(bytes) ?: return@withLock false
            replace(key, name, null)
            true
        }

    /** "Remove photo": the file goes, and the page's photo is not fetched again for this recipe. */
    suspend fun remove(key: String) =
        lock.withLock {
            replace(key, null, null)
        }

    private suspend fun download(
        key: String,
        url: String,
    ): Boolean =
        lock.withLock {
            // Asked again meanwhile, or chosen by hand meanwhile: nothing to do.
            val n = repository.recipeNote(key)
            if (n?.photoUrl != url || file(n.photo) != null) return@withLock false
            val r = fetcher.fetchImage(url) as? FetchResult.Image ?: return@withLock false
            val name = store(r.bytes) ?: return@withLock false
            replace(key, name, url)
            true
        }

    private suspend fun replace(
        key: String,
        name: String?,
        url: String?,
    ) {
        // The old file stays: another recipe may show the same picture, an interrupted restore's other side
        // or a restore's Undo may name it. The next start's [sweep] takes it once nothing does.
        repository.setRecipePhoto(key, name, url)
    }

    /**
     * A photo from a backup archive, already checked ([com.robert4lyf.hypershop.backup.BackupArchive]):
     * moved into the folder under its own name, or left be when that file is already there (same name,
     * same content). Called by a restore inside [exclusive]. False when it could not be put there.
     */
    fun adopt(staged: File): Boolean {
        dir.mkdirs()
        val f = File(dir, staged.name)
        // The same name is the same picture, unless the copy here was damaged (written before writes were
        // flushed, and cut short by a power loss): then the archive's checked copy replaces it.
        if (f.isFile && com.robert4lyf.hypershop.backup.BackupArchive.isIntact(f)) return true
        if (staged.renameTo(f)) return true
        val tmp = File(dir, staged.name + ".part")
        return runCatching {
            staged.inputStream().use { input ->
                FileOutputStream(tmp).use { out ->
                    input.copyTo(out)
                    out.fd.sync()
                }
            }
            tmp.renameTo(f)
        }.getOrDefault(false).also { if (!it) tmp.delete() }
    }

    /** Decoded, scaled and re-encoded: what is kept is always a JPEG this app made, never the bytes as fetched. */
    private suspend fun store(bytes: ByteArray): String? =
        withContext(Dispatchers.Default) {
            runCatching {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                if (bounds.outWidth.toLong() * bounds.outHeight > MAX_PIXELS) return@runCatching null
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= MAX_EDGE) sample *= 2
                val decoded =
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = sample })
                        ?: return@runCatching null
                val long = max(decoded.width, decoded.height)
                val scaled =
                    if (long > MAX_EDGE) {
                        Bitmap.createScaledBitmap(decoded, decoded.width * MAX_EDGE / long, decoded.height * MAX_EDGE / long, true)
                    } else {
                        decoded
                    }
                val out = java.io.ByteArrayOutputStream()
                scaled.compress(Bitmap.CompressFormat.JPEG, QUALITY, out)
                val jpeg = out.toByteArray()
                val name = MessageDigest.getInstance("SHA-256").digest(jpeg).take(12).joinToString("") { "%02x".format(it) } + ".jpg"
                val written =
                    withContext(Dispatchers.IO) {
                        dir.mkdirs()
                        val f = File(dir, name)
                        if (f.isFile) return@withContext true
                        // Flushed and renamed whole: the database is only ever pointed at a complete file.
                        val tmp = File(dir, "$name.part")
                        FileOutputStream(tmp).use { out ->
                            out.write(jpeg)
                            out.fd.sync()
                        }
                        tmp.renameTo(f).also { if (!it) tmp.delete() }
                    }
                name.takeIf { written }
            }.getOrNull()
        }

    companion object {
        const val DIR = "recipe-photos"
        const val MAX_EDGE = 1280
        private const val QUALITY = 85

        /** A photo chosen from the phone: anything bigger is not a photo of a dinner. */
        const val MAX_PICKED_BYTES = 25L * 1024 * 1024

        /** Refused before decoding: a small file that claims to be enormous. */
        const val MAX_PIXELS = 50_000_000L
    }
}
