package com.robert4lyf.hypershop.ui.theme

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AcUnit
import androidx.compose.material.icons.rounded.BakeryDining
import androidx.compose.material.icons.rounded.Cake
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.CleaningServices
import androidx.compose.material.icons.rounded.Eco
import androidx.compose.material.icons.rounded.Egg
import androidx.compose.material.icons.rounded.Grain
import androidx.compose.material.icons.rounded.Inventory2
import androidx.compose.material.icons.rounded.Kitchen
import androidx.compose.material.icons.rounded.LocalDrink
import androidx.compose.material.icons.rounded.NoFood
import androidx.compose.material.icons.rounded.Public
import androidx.compose.material.icons.rounded.RiceBowl
import androidx.compose.material.icons.rounded.SetMeal
import androidx.compose.material.icons.rounded.WaterDrop
import androidx.compose.material.icons.rounded.Whatshot
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import com.robert4lyf.hypershop.core.model.AisleCode

/** An aisle's icon and colours: a soft container with a strong icon on it. */
data class AisleStyle(
    val icon: ImageVector,
    val container: Color,
    val content: Color,
)

private data class Hue(
    val icon: ImageVector,
    val hue: Float,
    val saturation: Float,
)

private val HUES =
    mapOf(
        AisleCode.PRODUCE to Hue(Icons.Rounded.Eco, 128f, 0.55f),
        AisleCode.BAKERY to Hue(Icons.Rounded.BakeryDining, 34f, 0.70f),
        AisleCode.MEAT_FISH to Hue(Icons.Rounded.SetMeal, 356f, 0.60f),
        AisleCode.DAIRY_EGGS to Hue(Icons.Rounded.Egg, 46f, 0.75f),
        AisleCode.CHILLED to Hue(Icons.Rounded.Kitchen, 192f, 0.55f),
        AisleCode.FROZEN to Hue(Icons.Rounded.AcUnit, 210f, 0.65f),
        AisleCode.TINS_JARS to Hue(Icons.Rounded.Inventory2, 18f, 0.60f),
        AisleCode.PASTA_RICE_GRAINS to Hue(Icons.Rounded.RiceBowl, 40f, 0.55f),
        AisleCode.WORLD_FOODS to Hue(Icons.Rounded.Public, 268f, 0.45f),
        AisleCode.BAKING to Hue(Icons.Rounded.Cake, 330f, 0.55f),
        AisleCode.NUTS_DRIED_FRUIT to Hue(Icons.Rounded.Grain, 28f, 0.35f),
        AisleCode.OILS_VINEGARS to Hue(Icons.Rounded.WaterDrop, 68f, 0.50f),
        AisleCode.SPICES_CONDIMENTS to Hue(Icons.Rounded.Whatshot, 10f, 0.70f),
        AisleCode.FREE_FROM to Hue(Icons.Rounded.NoFood, 168f, 0.45f),
        AisleCode.DRINKS to Hue(Icons.Rounded.LocalDrink, 228f, 0.50f),
        AisleCode.HOUSEHOLD to Hue(Icons.Rounded.CleaningServices, 200f, 0.15f),
        AisleCode.OTHER to Hue(Icons.Rounded.Category, 120f, 0.05f),
    )

/** The aisle's style in the current theme (light or dark follows the colour scheme). */
val AisleCode.style: AisleStyle
    @Composable
    @ReadOnlyComposable
    get() {
        val h = HUES.getValue(this)
        val dark = MaterialTheme.colorScheme.surface.luminance() < 0.5f
        return if (dark) {
            AisleStyle(h.icon, Color.hsl(h.hue, h.saturation * 0.45f, 0.22f), Color.hsl(h.hue, h.saturation, 0.80f))
        } else {
            AisleStyle(h.icon, Color.hsl(h.hue, h.saturation, 0.90f), Color.hsl(h.hue, h.saturation, 0.28f))
        }
    }

/** The aisle's icon, without colours (for chips and menus). */
val AisleCode.icon: ImageVector get() = HUES.getValue(this).icon
