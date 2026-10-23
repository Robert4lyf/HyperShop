package com.robert4lyf.hypershop.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.robert4lyf.hypershop.testutil.Shots
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * A hero's words at the largest font on a narrow phone, with a long title: they wrap
 * inside the hero rather than run off it, and they are the element the shade is drawn
 * behind ("hero-text" sits on the same element as the shade), so the shade grows with them.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h700dp-xxhdpi")
class HeroTextTest {
    @get:Rule val compose = createComposeRule()

    private val long = "Slow-roasted aubergine with pomegranate, herbs and a lemon-tahini yoghurt"

    @Test
    fun aLongTitleAtTheLargestFontWrapsInsideItsShade() {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                HyperShopTheme(dynamicColor = false) {
                    Box(Modifier.width(320.dp)) { ScreenHeader(long, overline = "This week", subtitle = "6 items still needed") }
                }
            }
        }
        compose.onNodeWithText(long).assertIsDisplayed()
        val text = compose.onAllNodesWithTag("hero-text").fetchSemanticsNodes()
        assertEquals(1, text.size)
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        val words = text.single().boundsInRoot
        assertTrue("inside the hero: $words in $root", words.right <= root.right.value * 3 + 1 && words.left >= 0f)
        assertTrue("wrapped onto several lines", words.height > words.width / 4)
        compose.onRoot().captureRoboImage(Shots.path("hero-large-font-light.png"))
    }
}
