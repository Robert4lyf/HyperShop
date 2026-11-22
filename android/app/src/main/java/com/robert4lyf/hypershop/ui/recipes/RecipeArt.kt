package com.robert4lyf.hypershop.ui.recipes

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
