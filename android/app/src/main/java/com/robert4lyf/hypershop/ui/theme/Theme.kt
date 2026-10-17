package com.robert4lyf.hypershop.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

// Basil green on warm white; every text pair here clears 4.5:1.
private val Light =
    lightColorScheme(
        primary = Color(0xFF2E6B30),
        onPrimary = Color.White,
        primaryContainer = Color(0xFFC8EBC0),
        onPrimaryContainer = Color(0xFF0B2A0D),
        secondary = Color(0xFF52634F),
        background = Color(0xFFFCFDF6),
        onBackground = Color(0xFF1A1C19),
        surface = Color(0xFFFCFDF6),
        onSurface = Color(0xFF1A1C19),
        surfaceVariant = Color(0xFFDEE5D8),
        onSurfaceVariant = Color(0xFF42493F),
        error = Color(0xFFB3261E),
    )

private val Dark =
    darkColorScheme(
        primary = Color(0xFF9CD592),
        onPrimary = Color(0xFF00390A),
        primaryContainer = Color(0xFF15521A),
        onPrimaryContainer = Color(0xFFC8EBC0),
        secondary = Color(0xFFB9CCB4),
        background = Color(0xFF121411),
        onBackground = Color(0xFFE2E3DD),
        surface = Color(0xFF121411),
        onSurface = Color(0xFFE2E3DD),
        surfaceVariant = Color(0xFF42493F),
        onSurfaceVariant = Color(0xFFC2C9BD),
        error = Color(0xFFF2B8B5),
    )

/** Dynamic colour is on by default on the phone and off in tests and screenshots. */
@Composable
fun HyperShopTheme(
    dark: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val scheme =
        when {
            dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
                if (dark) dynamicDarkColorScheme(LocalContext.current) else dynamicLightColorScheme(LocalContext.current)
            dark -> Dark
            else -> Light
        }
    MaterialTheme(colorScheme = scheme, content = content)
}
