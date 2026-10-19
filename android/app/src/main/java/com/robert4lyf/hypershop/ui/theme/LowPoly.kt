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
 * A recipe header's background: a low-poly mosaic running deep teal → teal →
 * sage → gold → orange (the style guide in docs/design/recipe-header.jpg).
 *
 * Every recipe gets its own, seeded by [seed] (its title) so it never changes:
 * where the facets fall, which way each cell is split, a slight tilt of the
 * colour run, how far the warm end reaches, and a little light and shade per
 * facet. The left side, under the title, always stays in the dark teals so the
 * white text keeps its contrast; the gold and orange only reach the far right.
 */
fun Modifier.lowPoly(seed: String): Modifier =
    drawWithCache {
        val rnd = Random(seed.hashCode())
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
                    val base = sample(along)
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

/** The run, as (position, colour) stops: the warm end only past ~70% across. */
private val STOPS =
    listOf(
        0.00f to Color(0xFF0D3140),
        0.30f to Color(0xFF15596A),
        0.55f to Color(0xFF1F7E88),
        0.70f to Color(0xFF3C9293),
        0.80f to Color(0xFF8DAE7A),
        0.89f to Color(0xFFE6B84C),
        1.00f to Color(0xFFEE8540),
    )

private fun sample(t: Float): Color {
    val i = STOPS.indexOfLast { it.first <= t }.coerceIn(0, STOPS.size - 2)
    val (p0, c0) = STOPS[i]
    val (p1, c1) = STOPS[i + 1]
    return lerp(c0, c1, ((t - p0) / (p1 - p0)).coerceIn(0f, 1f))
}

/** Hues from the same run, for a recipe's chips and icons: teal, sage, gold, orange. */
internal val LOW_POLY_HUES = floatArrayOf(192f, 184f, 172f, 98f, 42f, 24f)
