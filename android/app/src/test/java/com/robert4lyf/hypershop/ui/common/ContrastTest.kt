package com.robert4lyf.hypershop.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The hero's own ink on its white pills. White text on the mosaics and photos is held
 * to 4.5:1 by HeroContrastTest, on rendered pixels; tools/contrast.py covers the
 * theme's fixed pairs.
 */
class ContrastTest {
    private fun contrast(
        a: Color,
        b: Color,
    ): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    @Test
    fun theHeroPillsInkIsLegibleOnWhite() {
        assertTrue(contrast(Color(0xFF12301F), Color.White) >= 7f)
        assertTrue(contrast(Color(0xFF178040), Color.White) >= 4.5f)
    }
}
