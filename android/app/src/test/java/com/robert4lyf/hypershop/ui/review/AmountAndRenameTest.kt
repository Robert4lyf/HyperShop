package com.robert4lyf.hypershop.ui.review

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.await
import com.robert4lyf.hypershop.testutil.readyJob
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

/** Y.16: "Set" and "Rename" on the row sheet each show what they did (owner: "doesn't appear to do anything"). */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class AmountAndRenameTest {
    private fun ready(vm: ReviewViewModel) = (await(vm.state) { it is ReviewState.Ready } as ReviewState.Ready).ui

    @Test
    fun anAmountInJarsReadsAsJarsNotOther() {
        val c = TestContainer.create()
        val vm = ReviewViewModel(c, runBlocking { c.readyJob("truffle pesto") })
        val key = ready(vm).aisles.flatMap { it.second }.single().key
        val line = vm.sheet(key)!!.lines.single()
        assertTrue(vm.setAmount(line.id, "1 jar"))
        await(vm.state) { s -> s is ReviewState.Ready && vm.sheet(key)?.lines?.single()?.amount != null }
        assertEquals("1 jar", vm.sheet(key)!!.lines.single().amount)
    }

    @Test
    fun anotherWordForTheSameItemSaysSoAndOffersNoRename() {
        val c = TestContainer.create()
        val vm = ReviewViewModel(c, runBlocking { c.readyJob("250g butter") })
        val key = ready(vm).aisles.flatMap { it.second }.single().key
        assertFalse(vm.renameChanges(key, "Salted butter"))
        assertTrue(vm.renamePreview("Salted butter", key)!!.startsWith("That is butter already"))
        assertTrue(vm.renameChanges(key, "Margarine"))
    }
}
