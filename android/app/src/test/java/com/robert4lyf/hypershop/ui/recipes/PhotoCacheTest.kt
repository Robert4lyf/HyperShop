package com.robert4lyf.hypershop.ui.recipes

import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.testutil.Photos
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

/** T.4b: a photo shown again (scrolled back to, the tab come back to) is not decoded again. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PhotoCacheTest {
    @get:Rule val compose = createComposeRule()

    private lateinit var photo: String

    @Before
    fun up() {
        PhotoCache.clear()
        photo = File.createTempFile("photo", ".jpg").apply { writeBytes(Photos.jpeg(1280, 960)) }.path
    }

    @Test
    fun aPhotoShownAgainIsDrawnFromTheCache() {
        var shown by mutableStateOf(true)
        var maxPx by mutableStateOf(512)
        val before = PhotoDecodes.count.get()
        compose.setContent {
            if (shown) RecipePhoto(photo, contentDescription = null, modifier = Modifier.size(100.dp).testTag("photo"), maxPx = maxPx)
        }
        compose.waitUntil(5_000) { PhotoDecodes.count.get() == before + 1 }
        // Written on the UI thread, so each change is applied and recomposed before the next.
        compose.runOnIdle { shown = false }
        compose.waitForIdle()
        compose.onNodeWithTag("photo").assertDoesNotExist()
        compose.runOnIdle { shown = true }
        compose.waitForIdle()
        compose.onNodeWithTag("photo").assertExists()
        assertEquals("shown again: no second decode", before + 1, PhotoDecodes.count.get())
        // Another size is another bitmap.
        compose.runOnIdle { maxPx = 1280 }
        compose.waitForIdle()
        compose.onNodeWithTag("photo").assertExists()
        compose.waitUntil(5_000) { PhotoDecodes.count.get() == before + 2 }
        assertEquals(2, PhotoCache.count())
    }

    @Test
    fun theCacheIsBoundedByBytesNotByPhotos() {
        val big = androidx.compose.ui.graphics.ImageBitmap(1280, 960)
        // Far more full-size photos than the limit holds: the oldest go, memory stays put.
        repeat(100) { PhotoCache.put(PhotoCache.key("p$it", 1280), big, 1280 * 960 * 4) }
        assertEquals(true, PhotoCache.count() in 1..(32 * 1024 * 1024 / (1280 * 960 * 4)))
        assertEquals(null, PhotoCache.get(PhotoCache.key("p0", 1280)))
    }
}
