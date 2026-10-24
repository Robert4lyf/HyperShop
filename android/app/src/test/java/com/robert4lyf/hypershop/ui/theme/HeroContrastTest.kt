package com.robert4lyf.hypershop.ui.theme

import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * White text on the heroes, measured on what is drawn: the real mosaic facets for every palette,
 * many recipes each, read back pixel by pixel. Since V.3 there is no shade behind the text; the
 * palettes themselves are dark enough. Everywhere: white 3:1 (WCAG AA for large text such as the
 * titles, and for icons). On the left half, where the subtitles and small text sit: white and the
 * soft white of subtitles 4.5:1. The mosaics do not depend on the light or dark theme.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(sdk = [34])
class HeroContrastTest {
    private val density = 3f
    private val heroW = 380f * density
    private val heroH = 220f * density

    private fun contrast(
        a: Color,
        b: Color,
    ): Float {
        val la = a.luminance()
        val lb = b.luminance()
        return (maxOf(la, lb) + 0.05f) / (minOf(la, lb) + 0.05f)
    }

    /** The hero for [seed], as drawn: the real facets, no shade. */
    private fun render(seed: String): PixelMap {
        val img = ImageBitmap(heroW.toInt(), heroH.toInt())
        CanvasDrawScope().draw(Density(density), LayoutDirection.Ltr, Canvas(img), Size(heroW, heroH)) {
            drawFacets(lowPolyFacets(seed, size, 44 * density))
        }
        return img.toPixelMap()
    }

    /** White's contrast on every other pixel of the band [x0]..[x1] (fractions of the width), sorted. */
    private fun band(
        px: PixelMap,
        x0: Float,
        x1: Float,
        ink: Color = Color.White,
    ): List<Float> {
        val out = ArrayList<Float>()
        for (x in (heroW * x0).toInt() until (heroW * x1).toInt().coerceAtMost(px.width) step 3) {
            for (y in 0 until px.height step 3) out += contrast(ink.compositeOver(px[x, y]), px[x, y])
        }
        return out.sorted()
    }

    private val seedsByPalette = (0 until 2000).map { "Dish $it" }.groupBy { lowPolyPalette(it) }

    @Test
    fun whiteTextIsReadableAcrossEveryPalette() {
        assertEquals("every palette is reached", PALETTES.size, seedsByPalette.size)
        for ((palette, seeds) in seedsByPalette) {
            val p = PALETTES.indexOf(palette)
            for (seed in seeds.take(12)) {
                val px = render(seed)
                val anywhere = band(px, 0f, 1f).first()
                assertTrue("palette $p, $seed: white ${"%.2f".format(anywhere)}:1 somewhere", anywhere >= 3f)
                val left = band(px, 0f, 0.5f).first()
                assertTrue("palette $p, $seed: white ${"%.2f".format(left)}:1 on the left half", left >= 4.5f)
                val soft = band(px, 0f, 0.5f, Brand.OnHeroSoft).first()
                assertTrue("palette $p, $seed: soft white ${"%.2f".format(soft)}:1 on the left half", soft >= 4.5f)
            }
        }
    }

    @Test
    fun theColoursAreRicherThanAsWritten() {
        // Saturation up and value down, more toward the bright end, never lifted toward white.
        val hsv = FloatArray(3)
        for (p in PALETTES) {
            val bright = p.stops.last().second
            android.graphics.Color.colorToHSV(bright.toArgb(), hsv)
            assertTrue("a bright end at value ${hsv[2]}", hsv[2] <= DEEPEN_BRIGHT + 0.01f)
        }
    }

    @Test
    fun report() {
        val bands = listOf(0f to 0.25f, 0.25f to 0.5f, 0.5f to 0.75f, 0.75f to 1f)
        for ((palette, seeds) in seedsByPalette) {
            val all = bands.map { ArrayList<Float>() }
            for (seed in seeds.take(12)) {
                val px = render(seed)
                bands.forEachIndexed { i, (a, b) -> all[i] += band(px, a, b) }
            }
            val line =
                all.joinToString("  ") { v ->
                    v.sort()
                    "min %.1f p10 %.1f".format(v.first(), v[v.size / 10])
                }
            println("CONTRAST palette ${PALETTES.indexOf(palette)}: $line")
        }
    }
}
