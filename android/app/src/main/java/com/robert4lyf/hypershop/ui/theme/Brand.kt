package com.robert4lyf.hypershop.ui.theme

import androidx.compose.ui.graphics.Color

/**
 * The brand's colour beyond Material's roles (docs/design/ui-overhaul.md, "Colour pass"): a hue per recipe from its art's
 * palette ([lowPoly]). Step AC: no hero gradients; Step AD: no photo bands, so no white-on-photo text.
 */
object Brand {
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
