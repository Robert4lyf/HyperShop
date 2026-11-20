package com.robert4lyf.hypershop.ui

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performImeAction
import androidx.compose.ui.test.performTextInput
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.importing.ImportInput
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * Step AA.4: a message (with its Undo) never covers a screen's pinned bottom action. Through the real app shell: the
 * list's Finish shopping bar and the review's Add to list bar, at the normal font size and at twice it.
 */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34], qualifiers = "w360dp-h640dp-xhdpi")
class SnackbarPlacementTest {
    @get:Rule val compose = createComposeRule()

    private fun show(c: AppContainer) =
        compose.setContent { HyperShopTheme(dynamicColor = false) { HyperShopApp(c, rememberNavController()) } }

    private fun waitFor(tag: String) {
        val end = System.currentTimeMillis() + 10_000
        while (compose.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isEmpty()) {
            check(System.currentTimeMillis() < end) { "\"$tag\" never appeared" }
            compose.waitForIdle()
            Thread.sleep(20)
        }
        compose.waitForIdle()
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag, useUnmergedTree = true).fetchSemanticsNode().boundsInRoot

    /** Posts a message and checks it sits wholly above the bottom bar. */
    private fun assertMessageClearsTheBar(c: AppContainer) {
        waitFor("bottom-action-bar")
        c.notice.value = "A message with an action"
        compose.waitUntil(10_000) { compose.onAllNodes(hasText("A message with an action")).fetchSemanticsNodes().isNotEmpty() }
        compose.waitForIdle()
        val message = compose.onNode(hasText("A message with an action"), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        val host = bounds("snackbar")
        val bar = bounds("bottom-action-bar")
        assertTrue("the message $message overlaps the bar $bar", message.bottom <= bar.top)
        assertTrue("the snackbar $host overlaps the bar $bar", host.bottom <= bar.top + 0.5f)
    }

    private fun addAndTick(c: AppContainer) {
        waitFor("add-item")
        for (name in listOf("milk", "bread")) {
            compose.onNodeWithTag("add-item").performClick()
            compose.onNodeWithTag("add-item").performTextInput(name)
            compose.onNodeWithTag("add-item").performImeAction()
            compose.waitForIdle()
        }
        compose.waitUntil(10_000) { runBlocking { c.repository.snapshot() }.itemByKey("cat:milk") != null }
        val milk = runBlocking { c.repository.snapshot() }.itemByKey("cat:milk")!!.id
        waitFor("tick-$milk")
        compose.onNodeWithTag("tick-$milk").performClick()
    }

    @Test
    fun onTheListAMessageSitsAboveFinishShopping() {
        val c = TestContainer.create()
        show(c)
        addAndTick(c)
        waitFor("finish")
        assertMessageClearsTheBar(c)
    }

    @Test
    fun atTwiceTheFontSizeTooTheBarIsMeasuredNotGuessed() {
        RuntimeEnvironment.setFontScale(2f)
        val c = TestContainer.create()
        show(c)
        addAndTick(c)
        waitFor("finish")
        assertMessageClearsTheBar(c)
    }

    @Test
    fun onTheReviewAMessageSitsAboveAddToList() {
        val server = MockWebServer().apply { start() }
        server.enqueue(MockResponse().setHeader("Content-Type", "text/html; charset=utf-8").setBody(PAGE))
        try {
            val c = TestContainer.create(pages = server)
            // The review's own once-only "Read offline" message would replace the one this test posts.
            runBlocking { c.settings.setKeyNudged() }
            show(c)
            runBlocking {
                c.awaitReady()
                c.queueReview(c.importJobs.enqueue(ImportInput(server.url("/soup").toString())))
            }
            waitFor("add")
            assertMessageClearsTheBar(c)
        } finally {
            server.shutdown()
        }
    }

    private companion object {
        val PAGE =
            """
            <html><head><title>Soup</title>
            <script type="application/ld+json">
            {"@context":"https://schema.org","@type":"Recipe","name":"Soup","recipeYield":"2",
             "recipeIngredient":["2 onions","1 litre stock"]}
            </script></head><body><h1>Soup</h1></body></html>
            """.trimIndent()
    }
}
