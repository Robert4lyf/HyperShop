package com.robert4lyf.hypershop.ui.theme

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.PixelMap
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.graphics.luminance
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
 * White text on the heroes, measured on what is drawn: the real mosaic facets for every
 * palette (many recipes each, none of them the ones the shading was tuned on) with the
 * real shading over the text's box, read back pixel by pixel. The brightest pixel any
 * text could sit on must give white 4.5:1 (WCAG AA for normal text), and the soft white
 * of subtitles too. The mosaics do not depend on the light or dark theme, so one
 * reading covers both.
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

    /** The hero for [seed] with text boxes [boxes] (left, top, width, height in hero pixels) shaded as the app shades them. */
    private fun render(
        seed: String?,
        boxes: List<FloatArray>,
        photo: Color? = null,
    ): PixelMap {
        val img = ImageBitmap(heroW.toInt(), heroH.toInt())
        val canvas = Canvas(img)
        val d = Density(density)
        CanvasDrawScope().draw(d, LayoutDirection.Ltr, canvas, Size(heroW, heroH)) {
            if (photo != null) drawRect(photo) else drawFacets(lowPolyFacets(seed!!, size, 44 * density))
        }
        for ((x, y, w, h) in boxes) {
            canvas.save()
            canvas.translate(x, y)
            CanvasDrawScope().draw(d, LayoutDirection.Ltr, canvas, Size(w, h)) {
                drawShade(Offset(x, y), heroW, photo != null, 10 * density)
            }
            canvas.restore()
        }
        return img.toPixelMap()
    }

    /** The darkest contrast white text gets anywhere inside [box]. */
    private fun worst(
        px: PixelMap,
        box: FloatArray,
        ink: Color = Color.White,
    ): Float {
        var min = Float.MAX_VALUE
        val (x0, y0, w, h) = box
        for (x in x0.toInt() until (x0 + w).toInt().coerceAtMost(px.width) step 2) {
            for (y in y0.toInt() until (y0 + h).toInt().coerceAtMost(px.height) step 2) {
                val bg = px[x, y]
                min = minOf(min, contrast(ink.compositeOver(bg), bg))
            }
        }
        return min
    }

    private val seedsByPalette = (0 until 2000).map { "Dish $it" }.groupBy { lowPolyPalette(it) }

    @Test
    fun everyPaletteGivesWhiteTextFourAndAHalfWhereverTheTextSits() {
        assertEquals("every palette is reached", PALETTES.size, seedsByPalette.size)
        for ((palette, seeds) in seedsByPalette) {
            val p = PALETTES.indexOf(palette)
            for (seed in seeds.take(12)) {
                // A title right across the hero (a long one, or large text), and a short word at the bright end.
                val boxes =
                    listOf(
                        floatArrayOf(0f, 0f, heroW, heroH),
                        floatArrayOf(heroW * 0.78f, heroH * 0.3f, heroW * 0.2f, heroH * 0.2f),
                    )
                val wide = render(seed, boxes.take(1))
                val c = worst(wide, boxes[0])
                assertTrue("palette $p, $seed: white ${"%.2f".format(c)}:1 across the hero", c >= 4.5f)
                val soft = worst(wide, boxes[0], Brand.OnHeroSoft)
                assertTrue("palette $p, $seed: soft white ${"%.2f".format(soft)}:1", soft >= 4.5f)
                val corner = render(seed, boxes.drop(1))
                val k = worst(corner, boxes[1])
                assertTrue("palette $p, $seed: white ${"%.2f".format(k)}:1 at the bright end", k >= 4.5f)
            }
        }
    }

    @Test
    fun withoutTheShadeTheBrightEndFails() {
        // The test can fail: the bright end of every palette is below 4.5:1 for white as drawn.
        for ((palette, seeds) in seedsByPalette) {
            val px = render(seeds.first(), emptyList())
            val c = worst(px, floatArrayOf(heroW * 0.85f, 0f, heroW * 0.15f, heroH))
            assertTrue("palette ${PALETTES.indexOf(palette)} is bright at its end (${"%.2f".format(c)}:1)", c < 4.5f)
        }
    }

    @Test
    fun theShadeLeavesTheMosaicAloneAwayFromText() {
        val seed = seedsByPalette.values.first().first()
        val bare = render(seed, emptyList())
        val box = floatArrayOf(heroW * 0.6f, heroH * 0.1f, heroW * 0.2f, heroH * 0.15f)
        val shaded = render(seed, listOf(box))
        // Well clear of the box (past the fade, about 40 dp): the very same pixels.
        for ((x, y) in listOf(10 to 10, heroW.toInt() - 5 to heroH.toInt() - 5, (heroW * 0.7f).toInt() to (heroH * 0.8f).toInt())) {
            assertEquals("pixel $x,$y", bare[x, y], shaded[x, y])
        }
        // Over the text itself: darker.
        val cx = (box[0] + box[2] / 2).toInt()
        val cy = (box[1] + box[3] / 2).toInt()
        assertTrue(shaded[cx, cy].luminance() < bare[cx, cy].luminance())
    }

    @Test
    fun aWhitePhotoUnderTextStillGivesFourAndAHalf() {
        // The worst photograph there is: pure white (the photo's own top and bottom shade not counted).
        val box = floatArrayOf(heroW * 0.1f, heroH * 0.3f, heroW * 0.8f, heroH * 0.4f)
        val px = render(null, listOf(box), photo = Color.White)
        assertTrue(worst(px, box) >= 4.5f)
        assertTrue(worst(px, box, Brand.OnHeroSoft) >= 4.5f)
    }

    @Test
    fun theShadeIsTheMeasuredCurve() {
        assertEquals(0f, mosaicShade(0.2f))
        assertEquals(0.55f, mosaicShade(1f))
        // Never less as it goes towards the bright end.
        val xs = (0..100).map { it / 100f }
        assertTrue(xs.zipWithNext().all { (a, b) -> mosaicShade(b) >= mosaicShade(a) })
    }
}
