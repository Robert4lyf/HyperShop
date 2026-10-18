package com.robert4lyf.hypershop.importing

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import com.robert4lyf.hypershop.claude.ClaudeRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.Base64
import java.util.UUID
import kotlin.math.max

/**
 * Photos of recipes waiting for Claude to read them: scaled to Claude's
 * recommended size, turned upright and saved as JPEG in no-backup storage. A
 * photo is deleted once read ([Importer.parse]) and any left after a week go.
 */
object PhotoStore {
    private const val QUALITY = 85
    private const val KEEP_MS = 7L * 24 * 60 * 60 * 1000

    private fun dir(context: Context) = File(context.noBackupFilesDir, "photos")

    /** The photo at [uri] (the camera's or the photo picker's), ready to send; null when it can't be read. */
    suspend fun save(
        context: Context,
        uri: Uri,
    ): File? =
        withContext(Dispatchers.IO) {
            runCatching {
                val r = context.contentResolver
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return@runCatching null
                var sample = 1
                while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= ClaudeRequest.PHOTO_MAX_EDGE) sample *= 2
                val opts = BitmapFactory.Options().apply { inSampleSize = sample }
                val decoded = r.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: return@runCatching null
                val rotation =
                    r.openInputStream(uri)?.use {
                        when (ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                            ExifInterface.ORIENTATION_ROTATE_90 -> 90f
                            ExifInterface.ORIENTATION_ROTATE_180 -> 180f
                            ExifInterface.ORIENTATION_ROTATE_270 -> 270f
                            else -> 0f
                        }
                    } ?: 0f
                val upright = fit(decoded, rotation)
                val out = File(dir(context).apply { mkdirs() }, "${UUID.randomUUID()}.jpg")
                out.outputStream().use { upright.compress(Bitmap.CompressFormat.JPEG, QUALITY, it) }
                prune(context)
                out
            }.getOrNull()
        }

    /** Scales the long side down to [ClaudeRequest.PHOTO_MAX_EDGE] and applies the EXIF rotation. */
    private fun fit(
        b: Bitmap,
        rotation: Float,
    ): Bitmap {
        val scale = minOf(1f, ClaudeRequest.PHOTO_MAX_EDGE.toFloat() / max(b.width, b.height))
        if (scale == 1f && rotation == 0f) return b
        val m =
            Matrix().apply {
                postScale(scale, scale)
                postRotate(rotation)
            }
        return Bitmap.createBitmap(b, 0, 0, b.width, b.height, m, true)
    }

    /** The saved photo, base64-encoded for the request; null when it has gone. */
    fun read(path: String): String? = File(path).takeIf { it.isFile }?.let { Base64.getEncoder().encodeToString(it.readBytes()) }

    fun delete(path: String?) {
        path?.let { File(it).delete() }
    }

    private fun prune(context: Context) {
        val cutoff = System.currentTimeMillis() - KEEP_MS
        dir(context).listFiles()?.filter { it.lastModified() < cutoff }?.forEach { it.delete() }
    }

    /** For tests: [bytes] written as a stored photo. */
    fun saveBytes(
        context: Context,
        bytes: ByteArray,
    ): File = File(dir(context).apply { mkdirs() }, "${UUID.randomUUID()}.jpg").apply { writeBytes(bytes) }
}
