package com.robert4lyf.hypershop.ui.common

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import com.robert4lyf.hypershop.ui.theme.Brand
import com.robert4lyf.hypershop.ui.theme.PALETTES
import com.robert4lyf.hypershop.ui.theme.sample
import com.robert4lyf.hypershop.ui.theme.scrimAlpha
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WCAG contrast of the text that sits on colour the theme file does not pin down:
 * white on the heroes' mosaics (every palette, every point across, with the
 * scrim), and the hero's own ink on its white pills. tools/contrast.py covers the
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

    /** The facet colour at [x] across a hero drawn to [end], shaded the light way the mosaic can, under the scrim. */
    private fun heroAt(
        palette: Int,
        x: Float,
        end: Float,
    ): Color {
        val base = lerp(sample(PALETTES[palette].stops, x * end), Color.White, 0.05f)
        return lerp(base, Color.Black, scrimAlpha(x))
    }

    @Test
    fun whiteTextClearsFourAndAHalfToOneAcrossEveryHero() {
        for (p in PALETTES.indices) {
            for (i in 0..100) {
                val x = i / 100f
                val c = contrast(Brand.OnHero, heroAt(p, x, HEADER_END))
                assertTrue("palette $p at $x: white ${"%.2f".format(c)}:1", c >= 4.5f)
            }
        }
    }

    @Test
    fun theSoftWhiteOverlineClearsFourAndAHalfOnTheDarkHalf() {
        // The overline sits in the left column; its soft white blends with what is under it.
        for (p in PALETTES.indices) {
            for (i in 0..60) {
                val x = i / 100f
                val under = heroAt(p, x, HEADER_END)
                val soft = lerp(under, Color.White, Brand.OnHeroSoft.alpha)
                val c = contrast(soft, under)
                assertTrue("palette $p at $x: soft white ${"%.2f".format(c)}:1", c >= 4.5f)
            }
        }
    }

    @Test
    fun theHeroPillsInkIsLegibleOnWhite() {
        assertTrue(contrast(Color(0xFF12301F), Color.White) >= 7f)
        assertTrue(contrast(Color(0xFF178040), Color.White) >= 4.5f)
    }
}
