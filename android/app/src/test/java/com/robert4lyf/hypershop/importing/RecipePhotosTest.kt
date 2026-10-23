package com.robert4lyf.hypershop.importing

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.ParsedBy
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.core.parse.RuleParser
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.testutil.TestContainer
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream
import java.io.File

/** A saved recipe's photo: the page's own, fetched once and kept small; or one chosen; never anything else. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecipePhotosTest {
    private lateinit var server: MockWebServer
    private lateinit var c: AppContainer

    @Before
    fun up() {
        server = MockWebServer().apply { start() }
        c = TestContainer.create(pages = server)
    }

    @After
    fun down() = server.shutdown()

    private fun jpeg(
        w: Int,
        h: Int,
    ): ByteArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(200, 80, 40)) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun image(bytes: ByteArray) = MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(bytes))

    private fun saved(title: String): String =
        runBlocking {
            val s =
                c.repository.storeRecipe(
                    "https://example.com/${title.lowercase()}", title, 4, UnitLocale.METRIC, "WEB", ParsedBy.RULES, null, false,
                    listOf(RuleParser().parse("2 onions")),
                )
            c.repository.saveToLibrary(s.recipe.id, null)
            c.repository.libraryKeyOf(s.recipe.id)!!
        }

    private fun note(key: String) = runBlocking { c.repository.recipeNote(key) }

    @Test
    fun thePagesPhotoIsFetchedOnceAndKeptSmall() {
        val key = saved("Chilli")
        server.enqueue(image(jpeg(3000, 2000)))
        val url = server.url("/chilli.jpg").toString()
        assertTrue(runBlocking { c.recipePhotos.offer(key, url) })
        val n = note(key)!!
        assertEquals(url, n.photoUrl)
        val f = c.recipePhotos.file(n.photo)
        assertNotNull(f)
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f!!.path, bounds)
        assertEquals(RecipePhotos.MAX_EDGE, maxOf(bounds.outWidth, bounds.outHeight))
        // Offered again (the recipe saved again): nothing fetched, nothing changed.
        assertFalse(runBlocking { c.recipePhotos.offer(key, url) })
        assertEquals(1, server.requestCount)
    }

    @Test
    fun aChosenPhotoWinsAndRemoveTakesItAway() {
        val key = saved("Soup")
        val picked = File.createTempFile("pick", ".jpg").apply { writeBytes(jpeg(800, 600)) }
        assertTrue(runBlocking { c.recipePhotos.pick(key, Uri.fromFile(picked)) })
        val chosen = note(key)!!.photo
        assertNotNull(c.recipePhotos.file(chosen))
        assertNull(note(key)!!.photoUrl)
        // A later import of the page offers its photo: the chosen one stays, nothing is fetched.
        assertFalse(runBlocking { c.recipePhotos.offer(key, server.url("/soup.jpg").toString()) })
        assertEquals(chosen, note(key)!!.photo)
        assertEquals(0, server.requestCount)
        runBlocking { c.recipePhotos.remove(key) }
        assertNull(note(key)!!.photo)
        assertNull(c.recipePhotos.file(chosen))
    }

    @Test
    fun aPhotoKnownButNotOnThisPhoneIsFetchedAgain() {
        // As after a restore onto another phone: the address came in the backup, the file did not.
        val key = saved("Dal")
        val url = server.url("/dal.jpg").toString()
        runBlocking { c.repository.setRecipePhoto(key, "0123456789abcdef01234567.jpg", url) }
        server.enqueue(image(jpeg(400, 300)))
        runBlocking { c.recipePhotos.fill(listOf(note(key)!!)) }
        assertNotNull(c.recipePhotos.file(note(key)!!.photo))
    }

    @Test
    fun whatIsNotAnImageIsNeverKept() {
        val key = saved("Pie")
        // Says it is a JPEG, is not one.
        server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody("<html>not a photo</html>"))
        assertFalse(runBlocking { c.recipePhotos.offer(key, server.url("/pie.jpg").toString()) })
        assertNull(note(key)!!.photo)
        // A page where a photo should be.
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html").setBody("<html></html>"))
        runBlocking { c.recipePhotos.fill(listOf(note(key)!!)) }
        assertNull(note(key)!!.photo)
        // A name that is not one of ours never reaches a file.
        assertNull(c.recipePhotos.file("../../databases/hypershop.db"))
    }
}
