package com.robert4lyf.hypershop.ui

import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.robert4lyf.hypershop.testutil.TestContainer
import com.robert4lyf.hypershop.ui.common.UiMessage
import com.robert4lyf.hypershop.ui.list.show
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper

/** X.4: snackbars do not queue; the newest takes the place of the one showing. */
@RunWith(AndroidJUnit4::class)
@Config(sdk = [34])
class SnackbarReplaceTest {
    @get:Rule val compose = createComposeRule()

    @Test
    fun theNewestReplacesTheOneShowing() {
        val c = TestContainer.create()
        val host = SnackbarHostState()
        compose.setContent { SnackbarHost(host) }
        compose.runOnIdle { show(c, host, UiMessage("rice vinegar at home", undo = {})) }
        compose.waitUntil(5_000) {
            ShadowLooper.idleMainLooper()
            host.currentSnackbarData?.visuals?.message == "rice vinegar at home"
        }
        compose.runOnIdle {
            show(c, host, UiMessage("salt at home", undo = {}))
            show(c, host, UiMessage("soy sauce at home", undo = {}))
        }
        compose.waitUntil(5_000) {
            ShadowLooper.idleMainLooper()
            host.currentSnackbarData?.visuals?.message == "soy sauce at home"
        }
        compose.onNodeWithText("rice vinegar at home").assertDoesNotExist()
        // Nothing waits behind it: dismissed, nothing else appears.
        compose.runOnIdle { host.currentSnackbarData?.dismiss() }
        compose.waitForIdle()
        compose.runOnIdle { assert(host.currentSnackbarData == null) { "queued: ${host.currentSnackbarData?.visuals?.message}" } }
    }
}
