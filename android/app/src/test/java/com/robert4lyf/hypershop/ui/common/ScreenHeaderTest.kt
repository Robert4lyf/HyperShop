package com.robert4lyf.hypershop.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.github.takahirom.roborazzi.captureRoboImage
import com.robert4lyf.hypershop.testutil.Shots
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Step AC: every screen's top is one plain compact bar. At the largest font on a narrow phone a long title stays
 * inside it in two lines at most (TalkBack still reads it whole), next to the back arrow and its actions.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34], qualifiers = "w320dp-h700dp-xxhdpi")
class ScreenHeaderTest {
    @get:Rule val compose = createComposeRule()

    private val long = "Slow-roasted aubergine with pomegranate, herbs and a lemon-tahini yoghurt"

    @Test
    fun aLongTitleAtTheLargestFontStaysInsideTheBar() {
        compose.setContent {
            val d = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(d.density, fontScale = 2f)) {
                HyperShopTheme(dynamicColor = false) {
                    Box(Modifier.width(320.dp)) { ScreenHeader(long, subtitle = "6 items still needed", onBack = {}) }
                }
            }
        }
        compose.onNodeWithTag("back").assertIsDisplayed()
        val title = compose.onNodeWithText(long).fetchSemanticsNode().boundsInRoot
        val root = compose.onRoot().getUnclippedBoundsInRoot()
        assertTrue("inside the bar: $title in $root", title.right <= root.right.value * 3 + 1 && title.left >= 0f)
        // Two lines at most: under three lines' height of the 200 % title style.
        assertTrue("two lines at most: ${title.height}", title.height < with(compose.density) { (28 * 2 * 3).sp.toPx() })
        compose.onRoot().captureRoboImage(Shots.path("header-large-font-light.png"))
    }
}
