package com.robert4lyf.hypershop.backup

import android.graphics.BitmapFactory
import com.robert4lyf.hypershop.data.BackupValidator
import com.robert4lyf.hypershop.importing.RecipePhotos
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipException
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** What an archive says it holds, read before anything else in it. */
@Serializable
data class ArchiveManifest(
    val format: String = BackupArchive.FORMAT,
    val version: Int = BackupArchive.VERSION,
    val createdAt: Long = 0,
    val photos: List<ArchivePhoto> = emptyList(),
)

/** One recipe photo in an archive: its file name, the SHA-256 of its bytes and their count. */
@Serializable
data class ArchivePhoto(
    val name: String,
    val sha256: String,
    val bytes: Long,
)

/**
 * A backup with its recipe photos: a zip of `manifest.json` (first), `backup.json`
 * (the [BackupFile] as a plain backup has always been written) and `photos/<name>`,
 * one per photo file, however many recipes show it. The photos a recipe shows are
 * named by their content ([RecipePhotos]), so the name is checked against the
 * bytes, and a photo in the archive is the same file on every phone.
 *
 * A zip rather than base64 in the JSON: the photos stay out of the JSON parser's
 * memory, a photo is read straight to a file, and the JSON part is byte for byte
 * what an older backup is, so it goes through the same checks.
 *
 * Reading treats the file as hostile. Entry names are never used as paths: only
 * the three expected shapes are accepted, each at most once, and a photo is
 * written under its own checked name inside a staging folder. Every entry has a
 * cap on the bytes actually inflated (not what its header claims), the whole
 * archive has a cap, and a photo must be a JPEG of the size this app writes,
 * whose bytes hash to its name and to the manifest.
 */
object BackupArchive {
    const val FORMAT = "hypershop-archive"
    const val VERSION = 1
    const val MANIFEST = "manifest.json"
    const val BACKUP = "backup.json"
    const val PHOTOS = "photos/"

    /** A photo this app made is a 1280-pixel JPEG, well under 1 MB. */
    const val MAX_PHOTO_BYTES = 8L * 1024 * 1024
    const val MAX_PHOTOS = 10_000
    const val MAX_MANIFEST_BYTES = 4L * 1024 * 1024

    /** Everything inflated, photos and JSON together. */
    const val MAX_TOTAL_BYTES = 2L * 1024 * 1024 * 1024

    /** The names this app gives photos: 24 hex digits of the SHA-256 of the JPEG. */
    private const val MIN_HASH_DIGITS = 24
    private val sha256Shape = Regex("[a-f0-9]{64}")
    private val json = Json { ignoreUnknownKeys = true }
    private val writer = Json { encodeDefaults = true }

    /** A zip's local-file signature: what tells an archive from an older plain-JSON backup. */
    fun isArchive(head: ByteArray): Boolean =
        head.size >= 4 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte() && head[2] == 3.toByte() && head[3] == 4.toByte()

    /** Writes [backupJson] and [photos] (files in the photo folder) as one archive. */
    fun write(
        out: OutputStream,
        createdAt: Long,
        backupJson: ByteArray,
        photos: List<File>,
    ) {
        val listed = photos.map { ArchivePhoto(it.name, sha256(it), it.length()) }
        val zip = ZipOutputStream(out)
        zip.putNextEntry(ZipEntry(MANIFEST))
        zip.write(
            writer.encodeToString(ArchiveManifest.serializer(), ArchiveManifest(createdAt = createdAt, photos = listed)).toByteArray(),
        )
        zip.closeEntry()
        zip.putNextEntry(ZipEntry(BACKUP))
        zip.write(backupJson)
        zip.closeEntry()
        for (f in photos) {
            zip.putNextEntry(ZipEntry(PHOTOS + f.name))
            f.inputStream().use { it.copyTo(zip) }
            zip.closeEntry()
        }
        zip.finish()
        zip.flush()
    }

    /** What reading an archive found. */
    sealed interface Read {
        /** [backup] is the JSON to inspect as any backup; [photos] are checked and waiting in the staging folder. */
        data class Ok(
            val backup: String,
            val photos: Set<String>,
        ) : Read

        data class Newer(
            val version: Int,
        ) : Read

        data class Broken(
            val problem: String,
        ) : Read
    }

    /**
     * Reads an archive from [input], leaving its photos in [staging] (emptied first). Nothing outside
     * [staging] is written; on anything but [Read.Ok] it is emptied again.
     */
    fun read(
        input: InputStream,
        staging: File,
        maxBackupBytes: Long,
    ): Read {
        staging.deleteRecursively()
        staging.mkdirs()
        val r =
            try {
                ZipInputStream(input).use { readEntries(it, staging, maxBackupBytes) }
            } catch (e: ZipException) {
                Read.Broken("the archive is damaged")
            } catch (e: IOException) {
                Read.Broken("the archive could not be read to the end")
            } catch (e: IllegalArgumentException) {
                // An entry name that is not valid text ("MALFORMED"): thrown as this, not as an IOException.
                Read.Broken("the archive is damaged")
            }
        if (r !is Read.Ok) staging.deleteRecursively()
        return r
    }

    private class Budget(
        var left: Long = MAX_TOTAL_BYTES,
    )

    private fun readEntries(
        zip: ZipInputStream,
        staging: File,
        maxBackupBytes: Long,
    ): Read {
        val budget = Budget()
        val first = zip.nextEntry ?: return Read.Broken("the archive is empty")
        if (first.name != MANIFEST || first.isDirectory) return Read.Broken("the archive does not start with its manifest")
        val manifestText = String(readCapped(zip, MAX_MANIFEST_BYTES, budget) ?: return Read.Broken("the manifest is too large"))
        val manifest =
            try {
                json.decodeFromString(ArchiveManifest.serializer(), manifestText)
            } catch (e: SerializationException) {
                return Read.Broken("the manifest is damaged")
            } catch (e: IllegalArgumentException) {
                return Read.Broken("the manifest is damaged")
            }
        if (manifest.format != FORMAT) return Read.Broken("not a HyperShop archive")
        if (manifest.version > VERSION) return Read.Newer(manifest.version)
        if (manifest.version < 1) return Read.Broken("unknown archive version ${manifest.version}")
        manifestProblem(manifest)?.let { return Read.Broken(it) }
        val listed = manifest.photos.associateBy { it.name }
        var backup: String? = null
        val photos = HashSet<String>()
        var entries = 1
        while (true) {
            val e = zip.nextEntry ?: break
            if (++entries > MAX_PHOTOS + 2) return Read.Broken("the archive has too many entries")
            if (e.isDirectory) return Read.Broken("the archive holds a folder")
            when {
                e.name == BACKUP -> {
                    if (backup != null) return Read.Broken("the archive holds two backups")
                    if (photos.isNotEmpty()) return Read.Broken("the backup comes after the photos")
                    backup = String(readCapped(zip, maxBackupBytes, budget) ?: return Read.Broken("the backup is too large"))
                }
                e.name.startsWith(PHOTOS) -> {
                    val name = e.name.removePrefix(PHOTOS)
                    val meta = listed[name] ?: return Read.Broken("a photo the manifest does not list")
                    if (!photos.add(name)) return Read.Broken("a photo is in the archive twice")
                    stagePhoto(zip, staging, meta, budget)?.let { return Read.Broken(it) }
                }
                else -> return Read.Broken("an unexpected entry in the archive")
            }
        }
        if (backup == null) return Read.Broken("the archive holds no backup")
        val absent = listed.keys - photos
        if (absent.isNotEmpty()) return Read.Broken("${absent.size} photo(s) the manifest lists are missing from the archive")
        return Read.Ok(backup, photos)
    }

    private fun manifestProblem(m: ArchiveManifest): String? {
        if (m.photos.size > MAX_PHOTOS) return "the manifest lists too many photos"
        if (m.photos.map { it.name }.toSet().size != m.photos.size) return "the manifest lists a photo twice"
        for (p in m.photos) {
            if (!isPhotoName(p.name)) return "the manifest names a photo badly"
            if (!sha256Shape.matches(p.sha256) || !p.sha256.startsWith(p.name.removeSuffix(".jpg"))) {
                return "a photo's name does not match its hash"
            }
            if (p.bytes !in 1..MAX_PHOTO_BYTES) return "a photo is larger than a photo can be"
        }
        return null
    }

    /** A name this app could have given a photo: never a path. */
    fun isPhotoName(name: String): Boolean = BackupValidator.PHOTO_NAME.matches(name) && name.length - ".jpg".length >= MIN_HASH_DIGITS

    /** Null when the photo was staged; else why it was refused (and nothing of it is left). */
    private fun stagePhoto(
        zip: ZipInputStream,
        staging: File,
        meta: ArchivePhoto,
        budget: Budget,
    ): String? {
        val part = File(staging, meta.name + ".part")
        val digest = MessageDigest.getInstance("SHA-256")
        var total = 0L
        FileOutputStream(part).use { out ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = zip.read(buf)
                if (n < 0) break
                total += n
                budget.left -= n
                if (total > meta.bytes || total > MAX_PHOTO_BYTES || budget.left < 0) {
                    part.delete()
                    return "a photo is larger than its manifest says"
                }
                digest.update(buf, 0, n)
                out.write(buf, 0, n)
            }
            out.fd.sync()
        }
        val problem =
            when {
                total != meta.bytes -> "a photo is shorter than its manifest says"
                hex(digest.digest()) != meta.sha256 -> "a photo does not match its hash"
                !isJpeg(part) -> "a photo is not a JPEG this app made"
                else -> null
            }
        if (problem != null) {
            part.delete()
            return problem
        }
        if (!part.renameTo(File(staging, meta.name))) {
            part.delete()
            return "a photo could not be staged"
        }
        return null
    }

    /**
     * A photo file as this app wrote it: its bytes hash to its name, and it is a whole JPEG. What a
     * backup may put in an archive (the reader would refuse the whole file over one that is not), and
     * what a restore checks a same-named file already on the phone against.
     */
    fun isIntact(f: File): Boolean =
        runCatching { isPhotoName(f.name) && sha256(f).startsWith(f.name.removeSuffix(".jpg")) && isJpeg(f) }.getOrDefault(false)

    /** JPEG markers at both ends, and dimensions no bigger than this app ever writes. */
    private fun isJpeg(f: File): Boolean {
        if (f.length() < 4) return false
        RandomAccessFile(f, "r").use { r ->
            val head = ByteArray(3).also { r.readFully(it) }
            r.seek(f.length() - 2)
            val tail = ByteArray(2).also { r.readFully(it) }
            if (head[0] != 0xFF.toByte() || head[1] != 0xD8.toByte() || head[2] != 0xFF.toByte()) return false
            if (tail[0] != 0xFF.toByte() || tail[1] != 0xD9.toByte()) return false
        }
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, bounds)
        return bounds.outWidth > 0 && bounds.outHeight > 0 && maxOf(bounds.outWidth, bounds.outHeight) <= RecipePhotos.MAX_EDGE
    }

    /** At most [cap] bytes of the current entry, counted as inflated; null past it. */
    private fun readCapped(
        zip: ZipInputStream,
        cap: Long,
        budget: Budget,
    ): ByteArray? {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(BUFFER)
        while (true) {
            val n = zip.read(buf)
            if (n < 0) break
            budget.left -= n
            if (out.size() + n > cap || budget.left < 0) return null
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }

    fun sha256(f: File): String {
        val d = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s ->
            val buf = ByteArray(BUFFER)
            while (true) {
                val n = s.read(buf)
                if (n < 0) break
                d.update(buf, 0, n)
            }
        }
        return hex(d.digest())
    }

    private fun hex(b: ByteArray) = b.joinToString("") { "%02x".format(it) }

    private const val BUFFER = 64 * 1024
}
