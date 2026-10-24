package com.robert4lyf.hypershop.ui

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.await
import com.robert4lyf.hypershop.testutil.readyJob
import com.robert4lyf.hypershop.ui.list.ListViewModel
import com.robert4lyf.hypershop.ui.list.add
import com.robert4lyf.hypershop.ui.review.ReviewState
import com.robert4lyf.hypershop.ui.review.ReviewViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** W.4: "This is wrong" saves the line, how it was read and what was shown, on the phone, until shared or deleted. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class WrongNotesTest {
    @Test
    fun aReviewNoteCarriesTheLineAndHowItWasRead() {
        val c = TestContainer.create()
        val vm = ReviewViewModel(c, runBlocking { c.readyJob("3 garlic cloves", "200 ml milk") })
        await(vm.state) { it is ReviewState.Ready }
        val text = runBlocking { vm.wrongNote("cat:garlic", "a bulb") }!!
        assertTrue(text, text.contains("Line: 3 garlic cloves\n"))
        assertTrue(text, text.contains("Expected: a bulb\n"))
        assertTrue(text, text.contains("catalogue ${c.catalog.version}"))
        assertEquals(1, runBlocking { c.wrongReports.count() })
    }

    @Test
    fun aListNoteCarriesTheRowAsShownAndNotesAddUpUntilDeleted() {
        val c = TestContainer.create()
        c.add("3 garlic cloves")
        val vm = ListViewModel(c)
        val ui = await(vm.ui) { it.loaded && it.aisles.isNotEmpty() }
        vm.openItem(ui.aisles.flatMap { it.second }.single().id)
        val sheet = await(vm.sheet) { it != null }!!
        val text = runBlocking { vm.wrongNote(sheet, "") }
        assertTrue(text, text.contains("Showed: 1 bulb garlic · 3 cloves\n"))
        assertTrue(text, text.contains("Expected: (not said)\n"))
        runBlocking { vm.wrongNote(sheet, "cloves") }
        assertEquals(2, runBlocking { c.wrongReports.count() })
        runBlocking { c.wrongReports.clear() }
        assertEquals(0, runBlocking { c.wrongReports.count() })
    }
}
