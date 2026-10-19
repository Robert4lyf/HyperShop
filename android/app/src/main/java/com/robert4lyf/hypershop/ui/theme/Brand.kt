package com.robert4lyf.hypershop.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/**
 * The brand's colour beyond Material's roles (docs/design/ui-overhaul.md,
 * "Colour pass"): a basil-to-lagoon gradient for heroes, citrus for highlights
 * on it, and a low-poly header per recipe ([lowPoly]). White text clears 4.5:1 on every gradient here.
 */
object Brand {
    val Citrus = Color(0xFFFFE08A)

    /** Text and icons on a hero: white, or a touch softer for secondary lines. */
    val OnHero = Color.White
    val OnHeroSoft = Color.White.copy(alpha = 0.9f)

    /** Warm, for "ready to review": a call to act, distinct from the basil header. White text clears 4.5:1 on both ends. */
    val WarmCall = Brush.linearGradient(listOf(Color(0xFFC4451F), Color(0xFFBD3A64)))

    /** A recipe's hue for its chips and icons, from its header's palette and picked by its title, so it stays the same. */
    fun recipeHue(seed: String): Float = lowPolyPalette(seed).hue

    /** A soft tint of [hue] for chips on a card. */
    fun tint(
        hue: Float,
        dark: Boolean,
    ): Color = if (dark) Color.hsl(hue, 0.30f, 0.24f) else Color.hsl(hue, 0.55f, 0.92f)

    /** A strong [hue] for an icon on [tint]. */
    fun strong(
        hue: Float,
        dark: Boolean,
    ): Color = if (dark) Color.hsl(hue, 0.60f, 0.80f) else Color.hsl(hue, 0.60f, 0.30f)
}

/** The hero gradient for the current theme. */
val heroBrush: Brush
    @Composable
    @ReadOnlyComposable
    get() = Brush.linearGradient(if (LocalDarkTheme.current) HERO_DARK else HERO_LIGHT)

// Brighter than the first pass: fresh basil into lagoon; white text still clears 4.5:1 at both ends.
private val HERO_LIGHT = listOf(Color(0xFF15854A), Color(0xFF0F7F86))
private val HERO_DARK = listOf(Color(0xFF0E6A3B), Color(0xFF0B6B70))

/** Two soft light circles in the top-right corner: depth on a hero without an image. */
fun Modifier.heroGlow(): Modifier =
    drawBehind {
        val r = size.minDimension
        drawCircle(Color.White.copy(alpha = 0.12f), radius = r * 0.9f, center = Offset(size.width + r * 0.15f, -r * 0.25f))
        drawCircle(Color.White.copy(alpha = 0.08f), radius = r * 0.55f, center = Offset(size.width - r * 0.2f, size.height + r * 0.1f))
    }
