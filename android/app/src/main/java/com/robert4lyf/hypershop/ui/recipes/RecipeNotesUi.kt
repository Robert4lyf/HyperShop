package com.robert4lyf.hypershop.ui.recipes

import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddPhotoAlternate
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Star
import androidx.compose.material.icons.rounded.StarOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.max

/**
 * A saved recipe's photo from the app's own storage, decoded off the main thread at
 * about the size it is drawn ([maxPx]), so a list of thumbnails is not a list of
 * 1280-pixel bitmaps. Nothing is drawn until it has been read. A photo decoded
 * lately is drawn from [PhotoCache] at once: scrolling back, coming back to the
 * tab or closing a recipe decoded every photo on screen again (T.4).
 */
@Composable
fun RecipePhoto(
    path: String,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    maxPx: Int = 1280,
) {
    val key = PhotoCache.key(path, maxPx)
    val image by produceState(PhotoCache.get(key), path, maxPx) {
        // Asked again for this file and size, not for whatever was showing before the size changed.
        PhotoCache.get(key)?.let {
            value = it
            return@produceState
        }
        value =
            withContext(Dispatchers.IO) {
                runCatching {
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeFile(path, bounds)
                    var sample = 1
                    while (max(bounds.outWidth, bounds.outHeight) / (sample * 2) >= maxPx) sample *= 2
                    BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample })?.let {
                        PhotoDecodes.count.incrementAndGet()
                        PhotoDecodes.bytes.addAndGet(it.allocationByteCount.toLong())
                        PhotoCache.put(key, it.asImageBitmap(), it.allocationByteCount)
                    }
                }.getOrNull()
            }
    }
    val img = image
    if (img != null) {
        Image(img, contentDescription, modifier, contentScale = ContentScale.Crop)
    } else {
        Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant))
    }
}

/**
 * The photos decoded lately, by file and size, up to [LIMIT] bytes of bitmap (an eighth of the
 * heap on a small phone): about six cards' worth at 1280 pixels, enough for a screen and the one
 * before it. A photo's file is named by its contents and never rewritten, so a name is the picture
 * for good and nothing here goes stale; a photo changed is a new name.
 */
internal object PhotoCache {
    private val LIMIT = minOf(Runtime.getRuntime().maxMemory() / 8, 32L * 1024 * 1024).toInt()

    private class Entry(
        val image: ImageBitmap,
        val bytes: Int,
    )

    private val cache =
        object : LruCache<String, Entry>(LIMIT) {
            override fun sizeOf(
                key: String,
                value: Entry,
            ) = value.bytes
        }

    fun key(
        path: String,
        maxPx: Int,
    ) = "$maxPx:$path"

    fun get(key: String): ImageBitmap? = cache.get(key)?.image

    fun put(
        key: String,
        image: ImageBitmap,
        bytes: Int,
    ): ImageBitmap {
        cache.put(key, Entry(image, bytes))
        return image
    }

    fun clear() = cache.evictAll()

    /** How many bitmaps are held (LruCache's own size() is in bytes here). */
    fun count(): Int = cache.snapshot().size
}

/** How many photos have been decoded, and the bitmap bytes made: what the performance probe reads. */
internal object PhotoDecodes {
    val count = java.util.concurrent.atomic.AtomicInteger()
    val bytes = java.util.concurrent.atomic.AtomicLong()
}

/**
 * Five stars, each a 48 dp target. Tapping the rating it already has takes it away.
 * Read by TalkBack as one choice of five ("4 of 5 stars").
 */
@Composable
fun StarRating(
    rating: Int?,
    onRate: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .selectableGroup()
            .semantics { stateDescription = rating?.let { "$it of 5 stars" } ?: "Not rated" }
            .testTag("rating"),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        for (star in 1..5) {
            val on = rating != null && star <= rating
            Box(
                Modifier
                    .size(48.dp)
                    .selectable(selected = rating == star, role = Role.RadioButton, onClick = { onRate(star) })
                    .semantics { contentDescription = if (star == 1) "1 star" else "$star stars" }
                    .testTag("rate-$star"),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    if (on) Icons.Rounded.Star else Icons.Rounded.StarOutline,
                    contentDescription = null,
                    tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(30.dp),
                )
            }
        }
    }
}

/**
 * Cooking notes: typed freely, kept when Save is tapped (it only shows when there is
 * something to save), so an accidental tap in the field never changes what was there.
 */
@Composable
fun CookingNotes(
    notes: String?,
    onSave: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var text by rememberSaveable(notes) { mutableStateOf(notes.orEmpty()) }
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it.take(com.robert4lyf.hypershop.data.ListRepository.MAX_COOKING_NOTES) },
            label = { Text("Cooking notes") },
            placeholder = { Text("Timings, amounts, swaps: \"20 min longer\", \"half the chilli\"") },
            modifier = Modifier.fillMaxWidth().heightIn(min = 96.dp).testTag("cooking-notes"),
            minLines = 3,
        )
        if (text.trim() != notes.orEmpty()) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                TextButton(onClick = { text = notes.orEmpty() }) { Text("Cancel") }
                TextButton(onClick = { onSave(text) }, modifier = Modifier.testTag("cooking-notes-save")) { Text("Save notes") }
            }
        }
    }
}

/** Choose a photo, take the page's own, or remove the one there is. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PhotoButtons(
    hasPhoto: Boolean,
    fromPage: Boolean,
    onChoose: () -> Unit,
    onPage: () -> Unit,
    onRemove: () -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedButton(onClick = onChoose, modifier = Modifier.testTag("photo-choose")) {
            Icon(Icons.Rounded.AddPhotoAlternate, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(if (hasPhoto) "  Change photo" else "  Choose a photo")
        }
        if (!hasPhoto && fromPage) {
            OutlinedButton(onClick = onPage, modifier = Modifier.testTag("photo-page")) {
                Icon(Icons.Rounded.Image, contentDescription = null, modifier = Modifier.size(18.dp))
                Text("  Use the recipe's photo")
            }
        }
        if (hasPhoto) TextButton(onClick = onRemove, modifier = Modifier.testTag("photo-remove")) { Text("Remove photo") }
    }
}
