package com.robert4lyf.hypershop.ui.theme

import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.Shader
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp

/**
 * Keeps white text readable on a bright hero without darkening the hero: the
 * picture behind each piece of text (registered with [onBackdrop]) is shaded just
 * enough, where the text is, and fades out a little way past it. Everywhere else
 * the mosaic or photo is drawn as it is.
 *
 * On a mosaic how much depends on how far across the hero the text sits
 * ([mosaicShade]): the left fifth is dark enough as drawn, and the bright end
 * needs a little over half. On a photo it is [PHOTO_SHADE] wherever text is, enough for
 * white text on a white photo. HeroContrastTest renders every palette with it and
 * holds the brightest pixel under text to 4.5:1.
 */
@Stable
class HeroBackdrop internal constructor(
    /** The hero is a photo, not a mosaic. */
    val photo: Boolean,
) {
    internal var host by mutableStateOf<LayoutCoordinates?>(null)
}

@Composable
fun rememberHeroBackdrop(photo: Boolean = false): HeroBackdrop = remember(photo) { HeroBackdrop(photo) }

/** On the hero itself: what the shading's position across it is measured against. */
fun Modifier.backdropHost(b: HeroBackdrop): Modifier = onGloballyPositioned { b.host = it }

/** On white text (or an icon) on a hero: the picture behind it is shaded for it. */
fun Modifier.onBackdrop(b: HeroBackdrop?): Modifier =
    if (b == null) {
        this
    } else {
        composed {
            var me by remember { mutableStateOf<LayoutCoordinates?>(null) }
            onGloballyPositioned { me = it }.drawWithCache {
                val feather = FEATHER.toPx()
                onDrawBehind {
                    val host = b.host ?: return@onDrawBehind
                    val self = me ?: return@onDrawBehind
                    if (!host.isAttached || !self.isAttached) return@onDrawBehind
                    val at = host.localPositionOf(self, Offset.Zero)
                    drawShade(at, host.size.width.toFloat(), b.photo, feather)
                }
            }
        }
    }

/**
 * How much black lies over the mosaic at [x] (0..1 across the hero) under text:
 * none on the left fifth, then rising to a little over half at the bright end.
 * Measured, not guessed: the darkening each column of every palette needs for
 * white, and the soft white of subtitles, to reach 4.5:1 on its brightest facet
 * (HeroContrastTest renders it and holds it there).
 */
internal fun mosaicShade(x: Float): Float {
    var prev = SHADE_STOPS.first()
    for (s in SHADE_STOPS) {
        if (x <= s.first) {
            if (s.first == prev.first) return s.second
            return prev.second + (s.second - prev.second) * (x - prev.first) / (s.first - prev.first)
        }
        prev = s
    }
    return SHADE_STOPS.last().second
}

/** Under text on a photo: enough for white text on a white photo (4.5:1). */
internal const val PHOTO_SHADE = 0.58f

private val SHADE_STOPS =
    listOf(
        0f to 0f,
        0.22f to 0f,
        0.30f to 0.08f,
        0.40f to 0.16f,
        0.55f to 0.27f,
        0.62f to 0.36f,
        0.75f to 0.50f,
        0.85f to 0.55f,
        1f to 0.55f,
    )

/** The blur's radius: the shading reaches about twice this past the text, fading to nothing. */
private val FEATHER = 10.dp

/**
 * The shading behind one piece of text at [at] in a hero [heroWidth] wide: a soft, blurred shape, full strength
 * over the text's own box and fading out over [blur] all round, so it reads as a glow behind the words rather
 * than a box. Nothing for an element with no size (an empty row of actions).
 */
internal fun DrawScope.drawShade(
    at: Offset,
    heroWidth: Float,
    photo: Boolean,
    blur: Float,
) {
    if (size.width <= 0f || size.height <= 0f) return
    val paint =
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            maskFilter = BlurMaskFilter(blur, BlurMaskFilter.Blur.NORMAL)
            if (photo) {
                color = Color.Black.copy(alpha = PHOTO_SHADE).toArgb()
            } else {
                shader =
                    LinearGradient(
                        -at.x,
                        0f,
                        -at.x + heroWidth,
                        0f,
                        SHADE_STOPS.map { Color.Black.copy(alpha = it.second).toArgb() }.toIntArray(),
                        SHADE_STOPS.map { it.first }.toFloatArray(),
                        Shader.TileMode.CLAMP,
                    )
            }
        }
    // Grown by twice the blur's radius, so the fade happens outside the words and they sit on the full shade.
    val grow = 2 * blur
    drawIntoCanvas { it.nativeCanvas.drawRoundRect(-grow, -grow, size.width + grow, size.height + grow, grow, grow, paint) }
}
