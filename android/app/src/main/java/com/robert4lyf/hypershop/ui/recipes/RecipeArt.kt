package com.robert4lyf.hypershop.ui.recipes

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import com.robert4lyf.hypershop.ui.theme.lowPoly

/**
 * A recipe's picture wherever it is shown: its photo when it has one, else its own
 * mosaic. The same art on every screen is what makes a recipe recognisable at a glance.
 */
@Composable
fun RecipeArt(
    title: String,
    photo: String?,
    modifier: Modifier = Modifier,
    maxPx: Int = 512,
) {
    if (photo != null) {
        RecipePhoto(photo, contentDescription = null, modifier = modifier, maxPx = maxPx)
    } else {
        Box(modifier.lowPoly(title))
    }
}

/**
 * [RecipeArt] behind white text (a card's band, the review's header). A photo is darkened
 * at the top and the bottom, where the text and buttons sit; the mosaic needs nothing.
 */
@Composable
fun RecipeBackdrop(
    title: String,
    photo: String?,
    modifier: Modifier = Modifier,
) {
    if (photo == null) {
        Box(modifier.lowPoly(title))
        return
    }
    Box(modifier) {
        RecipePhoto(photo, contentDescription = null, modifier = Modifier.matchParentSize(), maxPx = 1280)
        Box(Modifier.matchParentSize().background(PHOTO_SHADE))
    }
}

private val PHOTO_SHADE =
    Brush.verticalGradient(
        0f to Color.Black.copy(alpha = 0.35f),
        0.4f to Color.Black.copy(alpha = 0.1f),
        1f to Color.Black.copy(alpha = 0.7f),
    )
