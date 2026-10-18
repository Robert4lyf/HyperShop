package com.robert4lyf.hypershop.debug

import androidx.compose.runtime.Composable
import com.robert4lyf.hypershop.di.AppContainer

/** Release builds have no debug screen. */
object DebugTools {
    val screen: (@Composable (AppContainer) -> Unit)? = null
}
