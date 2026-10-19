package com.robert4lyf.hypershop.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robert4lyf.hypershop.R

// A fresh basil green on a bright, slightly minted neutral ladder, tomato for
// things to check (docs/design/ui-overhaul.md, "Brighter pass"). Every text
// pair clears 4.5:1 (tools/contrast.py lists them).
private val Light =
    lightColorScheme(
        primary = Color(0xFF178040),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFC6F2D2),
        onPrimaryContainer = Color(0xFF05331A),
        inversePrimary = Color(0xFF8FE0A8),
        secondary = Color(0xFF48685A),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFD6ECDC),
        onSecondaryContainer = Color(0xFF0C1F13),
        tertiary = Color(0xFFB4432A),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFDCD0),
        onTertiaryContainer = Color(0xFF3B0900),
        background = Color(0xFFF6F9F4),
        onBackground = Color(0xFF171C18),
        surface = Color(0xFFF6F9F4),
        onSurface = Color(0xFF171C18),
        surfaceVariant = Color(0xFFDFE8DD),
        onSurfaceVariant = Color(0xFF4A5449),
        surfaceTint = Color(0xFF178040),
        inverseSurface = Color(0xFF2C312D),
        inverseOnSurface = Color(0xFFF0F3EC),
        error = Color(0xFFB3261E),
        onError = Color.White,
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        outline = Color(0xFF70796F),
        outlineVariant = Color(0xFFC6CFC4),
        surfaceBright = Color(0xFFFFFFFF),
        surfaceDim = Color(0xFFD9DFD6),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF0F4EE),
        surfaceContainer = Color(0xFFEAF0E8),
        surfaceContainerHigh = Color(0xFFE3EAE1),
        surfaceContainerHighest = Color(0xFFDCE4DA),
    )

private val Dark =
    darkColorScheme(
        primary = Color(0xFF8FE0A8),
        onPrimary = Color(0xFF003A1C),
        primaryContainer = Color(0xFF0F5A30),
        onPrimaryContainer = Color(0xFFB5F5C6),
        inversePrimary = Color(0xFF178040),
        secondary = Color(0xFFB8D0BE),
        onSecondary = Color(0xFF213527),
        secondaryContainer = Color(0xFF384E40),
        onSecondaryContainer = Color(0xFFD6ECDC),
        tertiary = Color(0xFFFFB4A1),
        onTertiary = Color(0xFF5F1604),
        tertiaryContainer = Color(0xFF872F18),
        onTertiaryContainer = Color(0xFFFFDCD0),
        background = Color(0xFF121A15),
        onBackground = Color(0xFFE2E8E1),
        surface = Color(0xFF121A15),
        onSurface = Color(0xFFE2E8E1),
        surfaceVariant = Color(0xFF3F4A42),
        onSurfaceVariant = Color(0xFFC3CCC2),
        surfaceTint = Color(0xFF8FE0A8),
        inverseSurface = Color(0xFFE2E8E1),
        inverseOnSurface = Color(0xFF2C322E),
        error = Color(0xFFF2B8B5),
        onError = Color(0xFF601410),
        errorContainer = Color(0xFF8C1D18),
        onErrorContainer = Color(0xFFF9DEDC),
        outline = Color(0xFF8C968B),
        outlineVariant = Color(0xFF3F4A42),
        surfaceBright = Color(0xFF38433C),
        surfaceDim = Color(0xFF121A15),
        surfaceContainerLowest = Color(0xFF0C120E),
        surfaceContainerLow = Color(0xFF182019),
        surfaceContainer = Color(0xFF1C2620),
        surfaceContainerHigh = Color(0xFF202A24),
        surfaceContainerHighest = Color(0xFF2A352E),
    )

/** Plus Jakarta Sans (SIL OFL, assets/licenses), bundled so the app looks the same on every phone. */
val Jakarta =
    FontFamily(
        Font(R.font.plus_jakarta_sans_regular, FontWeight.Normal),
        Font(R.font.plus_jakarta_sans_medium, FontWeight.Medium),
        Font(R.font.plus_jakarta_sans_semibold, FontWeight.SemiBold),
        Font(R.font.plus_jakarta_sans_bold, FontWeight.Bold),
        Font(R.font.plus_jakarta_sans_extrabold, FontWeight.ExtraBold),
    )

private fun style(
    size: Int,
    line: Int,
    weight: FontWeight,
    tracking: Double = 0.0,
) = TextStyle(fontFamily = Jakarta, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, letterSpacing = tracking.sp)

val HyperShopType =
    Typography(
        displayLarge = style(52, 60, FontWeight.ExtraBold, -1.0),
        displayMedium = style(44, 52, FontWeight.ExtraBold, -0.8),
        displaySmall = style(36, 44, FontWeight.ExtraBold, -0.6),
        headlineLarge = style(30, 38, FontWeight.Bold, -0.4),
        headlineMedium = style(26, 34, FontWeight.Bold, -0.3),
        headlineSmall = style(22, 30, FontWeight.Bold, -0.2),
        titleLarge = style(20, 28, FontWeight.Bold, -0.1),
        titleMedium = style(16, 24, FontWeight.SemiBold),
        titleSmall = style(14, 20, FontWeight.SemiBold),
        bodyLarge = style(16, 24, FontWeight.Medium),
        bodyMedium = style(14, 20, FontWeight.Normal),
        bodySmall = style(12, 16, FontWeight.Normal, 0.1),
        labelLarge = style(14, 20, FontWeight.SemiBold, 0.1),
        labelMedium = style(12, 16, FontWeight.SemiBold, 0.3),
        labelSmall = style(11, 16, FontWeight.SemiBold, 0.4),
    )

val HyperShopShapes =
    Shapes(
        extraSmall = RoundedCornerShape(8.dp),
        small = RoundedCornerShape(12.dp),
        medium = RoundedCornerShape(16.dp),
        large = RoundedCornerShape(22.dp),
        extraLarge = RoundedCornerShape(28.dp),
    )

/**
 * The brand scheme, so the app always looks like itself (docs/design/ui-overhaul.md
 * turned wallpaper colour off); [dynamicColor] is kept for experiments.
 */
@Composable
fun HyperShopTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val scheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
            dark -> Dark
            else -> Light
        }
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = scheme, typography = HyperShopType, shapes = HyperShopShapes, content = content)
    }
}

/** Whether [HyperShopTheme] is dark, set once by the theme rather than guessed from colours. */
val LocalDarkTheme = staticCompositionLocalOf { false }

/** The card colour: white on the light background, a lifted green-grey on the dark one (never darker than the page). */
val androidx.compose.material3.ColorScheme.card: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalDarkTheme.current) surfaceContainerHigh else surfaceContainerLowest

/** A card's hairline edge: faint in the light theme (where the shadow does the work), a touch lighter than the card in the dark one. */
val androidx.compose.material3.ColorScheme.cardEdge: Color
    @Composable
    @ReadOnlyComposable
    get() = if (LocalDarkTheme.current) surfaceContainerHighest else outlineVariant.copy(alpha = 0.45f)
