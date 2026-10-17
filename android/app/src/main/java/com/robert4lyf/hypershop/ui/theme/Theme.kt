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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.robert4lyf.hypershop.R

// Basil green on a warm neutral ladder, tomato for things to check
// (docs/design/ui-overhaul.md). Every text pair clears 4.5:1.
private val Light =
    lightColorScheme(
        primary = Color(0xFF1E6B3C),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFCDEFD4),
        onPrimaryContainer = Color(0xFF00210F),
        inversePrimary = Color(0xFF8FD8A3),
        secondary = Color(0xFF4E6354),
        onSecondary = Color.White,
        secondaryContainer = Color(0xFFD1E8D6),
        onSecondaryContainer = Color(0xFF0C1F13),
        tertiary = Color(0xFFA9452C),
        onTertiary = Color.White,
        tertiaryContainer = Color(0xFFFFDBD1),
        onTertiaryContainer = Color(0xFF3B0900),
        background = Color(0xFFF6F7F2),
        onBackground = Color(0xFF191C19),
        surface = Color(0xFFF6F7F2),
        onSurface = Color(0xFF191C19),
        surfaceVariant = Color(0xFFDDE5DA),
        onSurfaceVariant = Color(0xFF414941),
        surfaceTint = Color(0xFF1E6B3C),
        inverseSurface = Color(0xFF2E312D),
        inverseOnSurface = Color(0xFFEFF1EB),
        error = Color(0xFFB3261E),
        onError = Color.White,
        errorContainer = Color(0xFFF9DEDC),
        onErrorContainer = Color(0xFF410E0B),
        outline = Color(0xFF717970),
        outlineVariant = Color(0xFFC1C9BF),
        surfaceBright = Color(0xFFF6F7F2),
        surfaceDim = Color(0xFFD7DAD3),
        surfaceContainerLowest = Color.White,
        surfaceContainerLow = Color(0xFFF0F2EC),
        surfaceContainer = Color(0xFFEAEDE6),
        surfaceContainerHigh = Color(0xFFE4E7E0),
        surfaceContainerHighest = Color(0xFFDEE2DA),
    )

private val Dark =
    darkColorScheme(
        primary = Color(0xFF8FD8A3),
        onPrimary = Color(0xFF00391C),
        primaryContainer = Color(0xFF00522B),
        onPrimaryContainer = Color(0xFFABF5BE),
        inversePrimary = Color(0xFF1E6B3C),
        secondary = Color(0xFFB5CCBA),
        onSecondary = Color(0xFF213527),
        secondaryContainer = Color(0xFF374B3D),
        onSecondaryContainer = Color(0xFFD1E8D6),
        tertiary = Color(0xFFFFB4A1),
        onTertiary = Color(0xFF5F1604),
        tertiaryContainer = Color(0xFF872F18),
        onTertiaryContainer = Color(0xFFFFDBD1),
        background = Color(0xFF0F1411),
        onBackground = Color(0xFFDEE4DD),
        surface = Color(0xFF0F1411),
        onSurface = Color(0xFFDEE4DD),
        surfaceVariant = Color(0xFF414942),
        onSurfaceVariant = Color(0xFFC1C9C0),
        surfaceTint = Color(0xFF8FD8A3),
        inverseSurface = Color(0xFFDEE4DD),
        inverseOnSurface = Color(0xFF2C322E),
        error = Color(0xFFF2B8B5),
        onError = Color(0xFF601410),
        errorContainer = Color(0xFF8C1D18),
        onErrorContainer = Color(0xFFF9DEDC),
        outline = Color(0xFF8B938A),
        outlineVariant = Color(0xFF414942),
        surfaceBright = Color(0xFF353A36),
        surfaceDim = Color(0xFF0F1411),
        surfaceContainerLowest = Color(0xFF0A0F0C),
        surfaceContainerLow = Color(0xFF171D19),
        surfaceContainer = Color(0xFF1B211D),
        surfaceContainerHigh = Color(0xFF252B27),
        surfaceContainerHighest = Color(0xFF303632),
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
        large = RoundedCornerShape(24.dp),
        extraLarge = RoundedCornerShape(32.dp),
    )

/**
 * The brand scheme by default, so the app always looks like itself; dynamic
 * (wallpaper) colour is still available to callers that ask for it.
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
    MaterialTheme(colorScheme = scheme, typography = HyperShopType, shapes = HyperShopShapes, content = content)
}

/** The card colour: white on the light background, a lifted grey on the dark one (never darker than the page). */
val androidx.compose.material3.ColorScheme.card: Color
    get() = if (surface.luminance() < 0.5f) surfaceContainerHigh else surfaceContainerLowest
