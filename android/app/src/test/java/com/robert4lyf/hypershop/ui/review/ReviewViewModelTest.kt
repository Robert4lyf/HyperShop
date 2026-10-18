package com.robert4lyf.hypershop.ui.review

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.core.model.AisleCode
import com.robert4lyf.hypershop.core.model.UnitLocale
import com.robert4lyf.hypershop.data.ImportJobEntity
import com.robert4lyf.hypershop.importing.ImportError
import com.robert4lyf.hypershop.importing.ImportInput
import com.robert4lyf.hypershop.importing.ImportResult
import com.robert4lyf.hypershop.importing.PhotoStore
import com.robert4lyf.hypershop.testutil.CHILLI
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.await
import com.robert4lyf.hypershop.testutil.readyJob
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class ReviewViewModelTest {
    private fun ready(vm: ReviewViewModel) = (await(vm.state) { it is ReviewState.Ready } as ReviewState.Ready).ui

    @Test
    fun aRowChangeDuringAddCannotUndoIt() {
        val c = TestContainer.create()
        val vm = ReviewViewModel(c, runBlocking { c.readyJob("2 onions", "1 carrot") })
        val key = ready(vm).aisles.flatMap { it.second }.first().key
        vm.confirm()
        vm.setInStock(key, true)
        vm.setServings(8)
        await(vm.state) { it is ReviewState.Added }
        Thread.sleep(200)
        // The queued row changes found the screen finished and did nothing.
        assertTrue(vm.state.value is ReviewState.Added)
        assertEquals(1, runBlocking { c.repository.usesOnList() }.size)
    }

    @Test
    fun rowsAreGroupedByAisleWithStaplesAndGarnishPreTicked() {
        val c = TestContainer.create()
        val id = runBlocking { c.readyJob(*CHILLI) }
        val vm = ReviewViewModel(c, id)
        val ui = ready(vm)
        assertEquals("Weeknight chilli", ui.title)
        assertEquals(4, ui.servings)
        assertEquals(AisleCode.PRODUCE, ui.aisles.first().first)
        val all = ui.aisles.flatMap { it.second }
        assertTrue(all.first { it.key == "cat:salt" }.inStock)
        assertTrue(all.first { it.key == "cat:olive_oil" }.inStock)
        assertTrue("garnish pre-ticked", all.first { it.key == "cat:sour_cream" }.inStock)
        assertFalse(all.first { it.key == "cat:onion" }.inStock)
        assertEquals(all.count { !it.inStock }, ui.addCount)
        assertEquals("2", all.first { it.key == "cat:onion" }.quantity)
        assertEquals(Badge.RULES, ui.badge)
    }

    @Test
    fun inStockToggleConfirmAndUndo() {
        val c = TestContainer.create()
        val id = runBlocking { c.readyJob(*CHILLI) }
        val vm = ReviewViewModel(c, id)
        ready(vm)
        vm.setInStock("cat:onion", true)
        val onionInStock = { s: ReviewState -> s is ReviewState.Ready && s.ui.row("cat:onion").inStock }
        val ui = await(vm.state, ok = onionInStock) as ReviewState.Ready
        vm.confirm()
        val added = await(vm.state) { it is ReviewState.Added } as ReviewState.Added
        assertEquals(ui.ui.addCount, added.count)
        val list = runBlocking { c.repository.snapshot() }
        assertEquals(null, list.itemByKey("cat:onion"))
        assertTrue(list.itemByKey("cat:beef_mince") != null)
        runBlocking { c.repository.undoImport(added.useId) }
        assertTrue(runBlocking { c.repository.snapshot() }.liveItems.isEmpty())
    }

    @Test
    fun aRevChangeRecomputesWithANotice() {
        val c = TestContainer.create()
        val first = runBlocking { c.readyJob("1 onion") }
        val vm1 = ReviewViewModel(c, first)
        ready(vm1)
        val second = runBlocking { c.readyJob("2 onions") }
        val vm2 = ReviewViewModel(c, second)
        ready(vm2)
        vm1.confirm()
        await(vm1.state) { it is ReviewState.Added }
        vm2.confirm()
        val ui = (await(vm2.state) { it is ReviewState.Ready && it.ui.notice != null } as ReviewState.Ready).ui
        assertEquals("List changed — review updated", ui.notice)
        vm2.confirm()
        await(vm2.state) { it is ReviewState.Added }
    }

    @Test
    fun servingsRescale() {
        val c = TestContainer.create()
        val vm = ReviewViewModel(c, runBlocking { c.readyJob("2 onions", servings = 4) })
        ready(vm)
        vm.setServings(8)
        val ui = (await(vm.state) { it is ReviewState.Ready && it.ui.servings == 8 } as ReviewState.Ready).ui
        assertEquals("4", ui.aisles.flatMap { it.second }.single().quantity)
    }

    @Test
    fun claudePendingIsParsedInTheForeground() {
        val claude = MockWebServer().apply { start() }
        try {
            claude.enqueue(MockResponse().setBody(File("../../testdata/claude/synthetic-ok-cake.json").readText()))
            val c = TestContainer.create(claude = claude, key = "sk-ant-test")
            c.reviewGate.set(true)
            val job = runBlocking { c.readyJob("200g golden caster sugar", claudePending = true) }
            val ui = ready(ReviewViewModel(c, job))
            assertEquals(Badge.CLAUDE, ui.badge)
            assertEquals(1, claude.requestCount)
            // Left with Back and opened again from the card: Claude's parse was kept, not paid for twice.
            assertEquals(Badge.CLAUDE, ready(ReviewViewModel(c, job)).badge)
            assertEquals(1, claude.requestCount)
        } finally {
            claude.shutdown()
        }
    }

    @Test
    fun aPhotoIsReadByClaudeOnTheReviewScreen() {
        val claude = MockWebServer().apply { start() }
        try {
            claude.enqueue(MockResponse().setBody(File("../../testdata/claude/synthetic-ok-cake.json").readText()))
            val c = TestContainer.create(claude = claude, key = "sk-ant-test")
            c.reviewGate.set(true)
            val photo = PhotoStore.saveBytes(c.context, byteArrayOf(1, 2, 3))
            val result = ImportResult(null, null, null, UnitLocale.METRIC, "PHOTO", claudePending = true, photo = photo.path)
            val job = runBlocking { c.importJobs.enqueueReady(ImportInput("", "Photo of a recipe"), result) }
            val ui = ready(ReviewViewModel(c, job))
            assertEquals(Badge.CLAUDE, ui.badge)
            assertTrue(ui.aisles.isNotEmpty())
            val body = claude.takeRequest().body.readUtf8()
            assertTrue(body.contains("\"type\":\"image\""))
            assertTrue(body.contains("\"data\":\"AQID\""))
            // Read once: the photo is gone and the lines are kept as text.
            assertFalse(photo.exists())
        } finally {
            claude.shutdown()
        }
    }

    @Test
    fun aPhotoWithoutAKeyAsksForOne() {
        val c = TestContainer.create()
        c.reviewGate.set(true)
        val photo = PhotoStore.saveBytes(c.context, byteArrayOf(1, 2, 3))
        val result = ImportResult(null, null, null, UnitLocale.METRIC, "PHOTO", claudePending = true, photo = photo.path)
        val job = runBlocking { c.importJobs.enqueueReady(ImportInput("", "Photo of a recipe"), result) }
        val vm = ReviewViewModel(c, job)
        val failed = await(vm.state) { it is ReviewState.Failed } as ReviewState.Failed
        assertEquals(ImportError.PHOTO_NEEDS_KEY, failed.error)
    }

    @Test
    fun cancelDiscardsTheRecipe() {
        val c = TestContainer.create()
        val vm = ReviewViewModel(c, runBlocking { c.readyJob("2 onions", url = "https://example.com/chilli") })
        ready(vm)
        vm.cancel()
        await(vm.state) { it == ReviewState.Cancelled }
        assertTrue(runBlocking { c.db.dao().recipesByUrl("https://example.com/chilli") }.isEmpty())
    }

    @Test
    fun noIngredientsGoesToSelectLinesManually() {
        val c = TestContainer.create()
        val id =
            runBlocking {
                val job =
                    c.db.dao().insertJob(
                        ImportJobEntity(0, "RUNNING", """{"url":"https://example.com/blog"}""", null, null, 0),
                    )
                c.importJobs.finish(
                    job,
                    ImportResult(
                        url = "https://example.com/blog",
                        title = "Gran's scones",
                        servings = null,
                        locale = UnitLocale.METRIC,
                        method = "PAGE_TEXT",
                        pageText = "Gran's scones\n\nThese are lovely.\n225g self-raising flour\n75g butter\n150ml milk\nx",
                        error = ImportError.NO_RECIPE,
                    ),
                )
                job
            }
        val vm = ReviewViewModel(c, id)
        val failed = await(vm.state) { it is ReviewState.Failed } as ReviewState.Failed
        assertEquals(ImportError.NO_RECIPE, failed.error)
        assertEquals(listOf("Gran's scones", "These are lovely.", "225g self-raising flour", "75g butter", "150ml milk"), failed.candidates)
        vm.useLines(listOf("225g self-raising flour", "75g butter\n150ml milk\n"))
        val ui = ready(vm)
        assertEquals("Gran's scones", ui.title)
        assertEquals(3, ui.addCount)
    }
}

private fun ReviewUi.row(key: String) = aisles.flatMap { it.second }.first { it.key == key }
