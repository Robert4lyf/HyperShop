package com.robert4lyf.hypershop.ui.recipes

import android.graphics.Bitmap
import android.graphics.Color
import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.await
import com.robert4lyf.hypershop.testutil.awaitTrue
import com.robert4lyf.hypershop.testutil.readyJob
import com.robert4lyf.hypershop.ui.review.ReviewState
import com.robert4lyf.hypershop.ui.review.ReviewViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okio.Buffer
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.ByteArrayOutputStream

/** Stars, notes and the photo through the Recipes tab and the review, against a page served locally. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class RecipeNotesViewModelTest {
    private lateinit var server: MockWebServer
    private lateinit var c: AppContainer
    private val collectors = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val models = ArrayList<androidx.lifecycle.ViewModel>()

    @Before
    fun up() {
        server = MockWebServer().apply { start() }
        c = TestContainer.create(pages = server)
    }

    @After
    fun down() {
        collectors.cancel()
        for (vm in models) vm.viewModelScope.cancel()
        Thread.sleep(200)
        server.shutdown()
    }

    private fun jpeg(): ByteArray {
        val b = Bitmap.createBitmap(900, 600, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.rgb(180, 90, 30)) }
        return ByteArrayOutputStream().also { b.compress(Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
    }

    private fun recipes(): RecipesViewModel =
        RecipesViewModel(c).also { vm ->
            models += vm
            collectors.launch { vm.ui.collect {} }
        }

    /** A recipe added from [url] through the review, as a share would leave it; its photo at [image]. */
    private fun addFromPage(
        url: String,
        image: String?,
    ) {
        val job = runBlocking { c.readyJob("2 onions", "1 tbsp olive oil", title = "Chilli", url = url) }
        if (image != null) {
            runBlocking { c.importJobs.update(job, c.importJobs.get(job)!!.result!!.copy(imageUrl = image)) }
        }
        val review = ReviewViewModel(c, job).also { models += it }
        // The same page again asks first; this is the "Add again" answer.
        if (await(
                review.state,
            ) { it is ReviewState.Ready || it is ReviewState.AlreadyOnList } is ReviewState.AlreadyOnList
        ) {
            review.addAgain()
        }
        await(review.state) { it is ReviewState.Ready }
        review.confirm()
        await(review.state) { it is ReviewState.Added }
    }

    @Test
    fun theRecipesPhotoArrivesAfterAddWithoutAnotherTap() {
        server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(jpeg())))
        addFromPage("https://example.com/chilli", server.url("/chilli.jpg").toString())
        val vm = recipes()
        val row = await(vm.ui) { it.library.singleOrNull()?.photo != null }.library.single()
        assertNotNull(row.photo)
    }

    @Test
    fun aRecipeSavedBeforePhotosGetsThePagesOwn() {
        val page = server.url("/dal").toString()
        addFromPage(page, image = null)
        val vm = recipes()
        val row = await(vm.ui) { it.library.isNotEmpty() }.library.single()
        assertNull(row.photo)
        // The page is fetched again for its photo, then the photo.
        server.enqueue(
            MockResponse().setHeader("Content-Type", "text/html").setBody(
                "<html><head><script type=\"application/ld+json\">{\"@type\":\"Recipe\",\"recipeIngredient\":[\"2 onions\"]," +
                    "\"image\":\"/dal.jpg\"}</script></head></html>",
            ),
        )
        server.enqueue(MockResponse().setHeader("Content-Type", "image/jpeg").setBody(Buffer().write(jpeg())))
        vm.pagePhoto(row)
        await(vm.ui) { it.library.single().photo != null }
        assertEquals(
            "/dal.jpg",
            server.run {
                takeRequest()
                takeRequest().path
            },
        )
    }

    @Test
    fun starsToggleAndNotesAreKept() {
        addFromPage("https://example.com/soup", image = null)
        val vm = recipes()
        var row = await(vm.ui) { it.library.isNotEmpty() }.library.single()
        vm.rate(row, 4)
        row = await(vm.ui) { it.library.single().rating == 4 }.library.single()
        // The same star again takes the rating away.
        vm.rate(row, 4)
        row = await(vm.ui) { it.library.single().rating == null }.library.single()
        vm.saveNotes(row, "Blend half, keep half chunky")
        await(vm.ui) { it.library.single().notes == "Blend half, keep half chunky" }
        // And they outlive an edit of the recipe (a new copy of it).
        awaitTrue { runBlocking { c.repository.recipeNote(row.key) }?.notes != null }
        addFromPage("https://example.com/soup", image = null)
        assertEquals("Blend half, keep half chunky", await(vm.ui) { it.library.isNotEmpty() }.library.single().notes)
    }
}
