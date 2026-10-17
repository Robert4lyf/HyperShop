package com.robert4lyf.hypershop.debug

import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.testutil.await
import com.robert4lyf.hypershop.ui.list.ListViewModel
import com.robert4lyf.hypershop.ui.review.ReviewState
import com.robert4lyf.hypershop.ui.review.ReviewViewModel
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class DebugToolsTest {
    @Test
    fun sampleListSampleRecipeAndFakeWatchTick() {
        val c = TestContainer.create()
        runBlocking { DebugTools.loadSampleList(c) }
        val l = runBlocking { c.repository.snapshot() }
        assertEquals(ListViewModel.SAMPLE.size, l.liveItems.size)
        val vm = ReviewViewModel(c, runBlocking { DebugTools.loadSampleRecipe(c) })
        val ready = await(vm.state) { it is ReviewState.Ready } as ReviewState.Ready
        assertEquals("Sample chilli", ready.ui.title)
        val onion = l.itemByKey("cat:onion")!!
        runBlocking { DebugTools.watchTick(c, onion.id, 1) }
        assertTrue(runBlocking { c.repository.snapshot() }.isDone(onion.id))
    }

    @Test
    fun backupRulesExcludeTheKeyFile() {
        val xml = File("src/main/res/xml")
        for (name in listOf("backup_rules.xml", "data_extraction_rules.xml")) {
            val doc = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(File(xml, name))
            val excludes = doc.getElementsByTagName("exclude")
            val paths = (0 until excludes.length).map { excludes.item(it).attributes.getNamedItem("path").nodeValue }
            val sections = if (name == "backup_rules.xml") 1 else 2
            assertEquals(name, sections, paths.count { it == "datastore/claude_key.preferences_pb" })
        }
    }
}
