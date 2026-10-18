package com.robert4lyf.hypershop

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import com.robert4lyf.hypershop.debug.DebugTools
import com.robert4lyf.hypershop.settings.ThemeMode
import com.robert4lyf.hypershop.ui.HyperShopApp
import com.robert4lyf.hypershop.ui.theme.HyperShopTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val container get() = (application as HyperShopApplication).container

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        // A recreated activity has handled its intent already; the ImportJob row carries the import.
        if (savedInstanceState == null) handle(intent)
        setContent {
            // Null for the first frame or so while the setting is read; drawing nothing avoids a flash of the wrong theme.
            val mode by container.settings.theme.collectAsState(initial = null)
            val system = isSystemInDarkTheme()
            mode?.let { m ->
                val dark =
                    when (m) {
                        ThemeMode.SYSTEM -> system
                        ThemeMode.LIGHT -> false
                        ThemeMode.DARK -> true
                    }
                // Status and navigation bar icons follow the app's theme, not the phone's.
                LaunchedEffect(dark) {
                    val bars = SystemBarStyle.auto(Color.TRANSPARENT, Color.TRANSPARENT) { dark }
                    enableEdgeToEdge(statusBarStyle = bars, navigationBarStyle = bars)
                }
                HyperShopTheme(dark = dark) {
                    HyperShopApp(container, debugScreen = DebugTools.screen)
                }
            }
        }
    }

    /** singleTop: a second share arrives here while the app is open. */
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handle(intent)
    }

    private fun handle(intent: Intent?) {
        if (intent?.action != Intent.ACTION_SEND) return
        // Reopened from Recents: the share was imported when it first arrived.
        if (intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return
        if (intent.type?.startsWith("text/") != true) return
        val text = intent.getStringExtra(Intent.EXTRA_TEXT)
        val subject = intent.getStringExtra(Intent.EXTRA_SUBJECT)
        container.applicationScope.launch { container.importShared(text, subject) }
    }
}
