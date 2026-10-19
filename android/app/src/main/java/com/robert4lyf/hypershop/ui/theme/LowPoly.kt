package com.robert4lyf.hypershop.ui.theme

import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.unit.dp
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/**
 * A recipe header's background: a low-poly mosaic in the manner of the style
 * guide (docs/design/recipe-header.jpg), in one of several palettes.
 *
 * Every recipe gets its own, seeded by [seed] (its title) so it never changes:
 * the palette, where the facets fall, which way each cell is split, a slight tilt of the
 * colour run, how far the warm end reaches, and a little light and shade per
 * facet. The left side, under the title, always stays in the dark end so the
 * white text keeps its contrast; the bright end only reaches the far right.
 *
 * [end] is how far along the palette the right edge gets: below 1 for a header
 * with white buttons on the right, which stay legible on the mid tones but not
 * on the gold and mint at the very end.
 */
fun Modifier.lowPoly(
    seed: String,
    end: Float = 1f,
): Modifier =
    drawWithCache {
        val rnd = Random(seed.hashCode())
        val stops = lowPolyPalette(seed).stops
        val cell = 44.dp.toPx()
        val cols = ceil(size.width / cell).toInt().coerceAtLeast(2)
        val rows = ceil(size.height / cell).toInt().coerceAtLeast(2)
        val dx = size.width / cols
        val dy = size.height / rows
        // Grid points, the inner ones nudged up to a third of a cell; the edges stay
        // on the edges so the mosaic fills the box.
        val pts =
            Array(rows + 1) { r ->
                Array(cols + 1) { c ->
                    val jx = if (c in 1 until cols) (rnd.nextFloat() - 0.5f) * dx * 0.66f else 0f
                    val jy = if (r in 1 until rows) (rnd.nextFloat() - 0.5f) * dy * 0.66f else 0f
                    Offset(c * dx + jx, r * dy + jy)
                }
            }
        // The colour run: mostly left to right, tilted a little either way, and
        // starting a little further into the teals on some recipes than others.
        val tilt = (rnd.nextFloat() - 0.5f) * 0.7f
        val dirX = cos(tilt)
        val dirY = sin(tilt)
        val reach = 0.10f * rnd.nextFloat()
        val facets = ArrayList<Pair<Path, Color>>(rows * cols * 2)
        for (r in 0 until rows) {
            for (c in 0 until cols) {
                val a = pts[r][c]
                val b = pts[r][c + 1]
                val d = pts[r + 1][c]
                val e = pts[r + 1][c + 1]
                val tris = if (rnd.nextBoolean()) listOf(listOf(a, b, e), listOf(a, e, d)) else listOf(listOf(a, b, d), listOf(b, e, d))
                for (t in tris) {
                    val cx = (t[0].x + t[1].x + t[2].x) / 3f / size.width
                    val cy = (t[0].y + t[1].y + t[2].y) / 3f / size.height
                    val along = (cx * dirX + (cy - 0.5f) * dirY * 0.6f - reach + (rnd.nextFloat() - 0.5f) * 0.06f).coerceIn(0f, 1f)
                    val shade = (rnd.nextFloat() - 0.5f) * 0.10f
                    val base = sample(stops, along * end)
                    val colour = if (shade >= 0) lerp(base, Color.White, shade) else lerp(base, Color.Black, -shade)
                    val path =
                        Path().apply {
                            moveTo(t[0].x, t[0].y)
                            lineTo(t[1].x, t[1].y)
                            lineTo(t[2].x, t[2].y)
                            close()
                        }
                    facets += path to colour
                }
            }
        }
        onDrawBehind {
            for ((path, colour) in facets) {
                drawPath(path, colour)
                // A hairline in the facet's own colour hides the seams anti-aliasing leaves.
                drawPath(path, colour, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f))
            }
        }
    }

/** One header palette: dark at 0 (under the title), bright at 1, and [hue] for the recipe's chips. */
internal class LowPolyPalette(
    val hue: Float,
    val stops: List<Pair<Float, Color>>,
)

private fun palette(
    hue: Float,
    vararg colours: Long,
): LowPolyPalette {
    // Dark half kept long so white text on the left keeps its contrast.
    val at = floatArrayOf(0.00f, 0.30f, 0.55f, 0.70f, 0.80f, 0.89f, 1.00f)
    return LowPolyPalette(hue, colours.mapIndexed { i, c -> at[i] to Color(c) })
}

/** All in the style guide's manner: a deep base running into a warm or bright end. */
private val PALETTES =
    listOf(
        // The style guide: deep teal, sage, gold, orange.
        palette(184f, 0xFF0D3140, 0xFF15596A, 0xFF1F7E88, 0xFF3C9293, 0xFF8DAE7A, 0xFFE6B84C, 0xFFEE8540),
        // Indigo into magenta and peach.
        palette(300f, 0xFF1B1840, 0xFF2E2A6B, 0xFF4B3A8F, 0xFF7A4597, 0xFFB5528E, 0xFFE57A78, 0xFFF4A96A),
        // Forest into lime and yellow.
        palette(110f, 0xFF10301F, 0xFF1A5232, 0xFF2A7443, 0xFF4A8C45, 0xFF8DB24A, 0xFFD3CF4E, 0xFFF2D35A),
        // Navy into sky and coral.
        palette(210f, 0xFF0E2140, 0xFF173B6B, 0xFF225C92, 0xFF3D7FB0, 0xFF7FA9C4, 0xFFF0A08A, 0xFFF0715E),
        // Plum into rose and gold.
        palette(330f, 0xFF2E1230, 0xFF4E1D4C, 0xFF73305F, 0xFF994466, 0xFFC86A6A, 0xFFE8A35A, 0xFFF2C94C),
        // Burgundy into red and amber.
        palette(12f, 0xFF35111A, 0xFF5A1A24, 0xFF84262C, 0xFFA83A2E, 0xFFD0602F, 0xFFEB9338, 0xFFF5BE4A),
        // Slate into teal and mint.
        palette(160f, 0xFF1A2633, 0xFF24404F, 0xFF2B6068, 0xFF2F8078, 0xFF4FA88A, 0xFF8FD1A0, 0xFFD5EEA8),
        // Aubergine into violet and pink.
        palette(275f, 0xFF231338, 0xFF3A1F5C, 0xFF56307F, 0xFF7243A0, 0xFF9B5FBF, 0xFFD27FC6, 0xFFF4A3C0),
    )

/** The palette for a recipe, picked by its title so it never changes. */
internal fun lowPolyPalette(seed: String): LowPolyPalette = PALETTES[Math.floorMod(seed.hashCode() * 31 + 7, PALETTES.size)]

private fun sample(
    stops: List<Pair<Float, Color>>,
    t: Float,
): Color {
    val i = stops.indexOfLast { it.first <= t }.coerceIn(0, stops.size - 2)
    val (p0, c0) = stops[i]
    val (p1, c1) = stops[i + 1]
    return lerp(c0, c1, ((t - p0) / (p1 - p0)).coerceIn(0f, 1f))
}
