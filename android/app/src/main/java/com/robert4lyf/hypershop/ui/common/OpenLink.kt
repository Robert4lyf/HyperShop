package com.robert4lyf.hypershop.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalUriHandler
import java.net.URI

/** A recipe's page, when it is a web address the browser can open. */
fun webLink(url: String?): String? =
    url?.trim()?.takeIf { it.startsWith("https://", ignoreCase = true) || it.startsWith("http://", ignoreCase = true) }

/** "bbcgoodfood.com" from a recipe's address, for showing where it came from. */
fun siteName(url: String): String? =
    runCatching { URI(url).host }
        .getOrNull()
        ?.lowercase()
        ?.removePrefix("www.")
        ?.takeIf { it.isNotBlank() }

/** Opens a page in the browser; does nothing when no app can open it. */
@Composable
fun rememberOpenLink(): (String) -> Unit {
    val handler = LocalUriHandler.current
    return remember(handler) { { url -> runCatching { handler.openUri(url) } } }
}
