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
import androidx.compose.runtime.Composable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.ui.graphics.Color
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

/** Exhaustive, so a new [AisleCode] doesn't compile until it has a style. */
private fun AisleCode.hue(): Hue =
    when (this) {
        AisleCode.PRODUCE -> Hue(Icons.Rounded.Eco, 128f, 0.55f)
        AisleCode.BAKERY -> Hue(Icons.Rounded.BakeryDining, 34f, 0.70f)
        AisleCode.MEAT_FISH -> Hue(Icons.Rounded.SetMeal, 356f, 0.60f)
        AisleCode.DAIRY_EGGS -> Hue(Icons.Rounded.Egg, 46f, 0.75f)
        AisleCode.CHILLED -> Hue(Icons.Rounded.Kitchen, 192f, 0.55f)
        AisleCode.FROZEN -> Hue(Icons.Rounded.AcUnit, 210f, 0.65f)
        AisleCode.TINS_JARS -> Hue(Icons.Rounded.Inventory2, 18f, 0.60f)
        AisleCode.PASTA_RICE_GRAINS -> Hue(Icons.Rounded.RiceBowl, 40f, 0.55f)
        AisleCode.WORLD_FOODS -> Hue(Icons.Rounded.Public, 268f, 0.45f)
        AisleCode.BAKING -> Hue(Icons.Rounded.Cake, 330f, 0.55f)
        AisleCode.NUTS_DRIED_FRUIT -> Hue(Icons.Rounded.Grain, 28f, 0.35f)
        AisleCode.OILS_VINEGARS -> Hue(Icons.Rounded.WaterDrop, 68f, 0.50f)
        AisleCode.SPICES_CONDIMENTS -> Hue(Icons.Rounded.Whatshot, 10f, 0.70f)
        AisleCode.FREE_FROM -> Hue(Icons.Rounded.NoFood, 168f, 0.45f)
        AisleCode.DRINKS -> Hue(Icons.Rounded.LocalDrink, 228f, 0.50f)
        AisleCode.HOUSEHOLD -> Hue(Icons.Rounded.CleaningServices, 200f, 0.15f)
        AisleCode.OTHER -> Hue(Icons.Rounded.Category, 120f, 0.05f)
    }

private val LIGHT =
    AisleCode.entries.associateWith { a ->
        a.hue().let {
            AisleStyle(it.icon, Color.hsl(it.hue, it.saturation, 0.90f), Color.hsl(it.hue, it.saturation, 0.28f))
        }
    }
private val DARK =
    AisleCode.entries.associateWith { a ->
        a.hue().let { AisleStyle(it.icon, Color.hsl(it.hue, it.saturation * 0.45f, 0.22f), Color.hsl(it.hue, it.saturation, 0.80f)) }
    }

/** The aisle's style in the current theme (computed once per theme, not per row). */
val AisleCode.style: AisleStyle
    @Composable
    @ReadOnlyComposable
    get() = (if (LocalDarkTheme.current) DARK else LIGHT).getValue(this)

/** The aisle's icon, without colours (for chips and menus). */
val AisleCode.icon: ImageVector get() = hue().icon
