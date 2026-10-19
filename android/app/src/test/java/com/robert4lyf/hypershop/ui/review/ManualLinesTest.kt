package com.robert4lyf.hypershop.ui.review

import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.robert4lyf.hypershop.importing.ImportError
import com.robert4lyf.hypershop.testutil.Shots
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

private val LINES = listOf("Gran's scones", "These are lovely.", "225g self-raising flour", "75g butter", "150ml milk")

@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w411dp-h891dp-xxhdpi")
class ManualLinesTest {
    @get:Rule val compose = createComposeRule()

    private var used: List<String>? = null
    private var retried = 0

    private fun show(
        error: ImportError = ImportError.NO_RECIPE,
        lines: List<String> = LINES,
        dark: Boolean = false,
        retry: Boolean = false,
    ) = compose.setContent {
        HyperShopTheme(dark = dark, dynamicColor = false) {
            ManualLinesView(error, lines, onUse = { used = it }, onBack = {}, onRetry = if (retry) ({ retried++ }) else null)
        }
    }

    @Test
    fun pickAndPasteLines() {
        show()
        compose.onNodeWithText("Use 0 lines").assertIsNotEnabled()
        compose.onNodeWithText("75g butter").performClick()
        compose.onNodeWithText("225g self-raising flour").performClick()
        compose.onNodeWithTag("selected-count").assertTextEquals("2 selected")
        compose.onNodeWithTag("clear-selection").performClick()
        compose.onNodeWithTag("selected-count").assertTextEquals("0 selected")
        compose.onNodeWithText("75g butter").performClick()
        compose.onNodeWithText("225g self-raising flour").performClick()
        compose.onNodeWithTag("paste-lines").performTextInput("150ml milk")
        compose.onNodeWithText("Use 3 lines").performClick()
        assertEquals(listOf("225g self-raising flour", "75g butter", "150ml milk"), used)
    }

    @Test
    fun blockedPageOffersPasteAndTryAgain() {
        show(ImportError.HTTP, emptyList(), retry = true)
        compose.onNodeWithText("The site refused the request.").assertExists()
        compose.onNodeWithText("Paste the ingredient lines").assertExists()
        compose.onNodeWithText("Try again").performClick()
        assertEquals(1, retried)
        assertTrue(ImportError.OFFLINE.transient)
        assertTrue(!ImportError.NO_RECIPE.transient)
    }

    @Test
    fun recordLight() {
        show()
        compose.onRoot().captureRoboImage(Shots.path("manual-lines-light.png"))
    }

    @Test
    fun recordDark() {
        show(ImportError.HTTP, emptyList(), dark = true)
        compose.onRoot().captureRoboImage(Shots.path("manual-lines-dark.png"))
    }
}
