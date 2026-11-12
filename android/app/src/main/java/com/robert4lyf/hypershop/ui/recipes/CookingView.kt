package com.robert4lyf.hypershop.ui.recipes

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.robert4lyf.hypershop.ui.common.rememberOpenLink
import com.robert4lyf.hypershop.ui.common.webLink

/** Saved text only: serving context and source remain available; no generated instructions. */
@Composable
internal fun CookingView(
    detail: RecipeDetail,
    onClose: () -> Unit,
) {
    Dialog(onDismissRequest = onClose, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        val view = LocalView.current
        val lifecycle = LocalLifecycleOwner.current.lifecycle
        DisposableEffect(view, lifecycle) {
            val previous = view.keepScreenOn
            val observer = LifecycleEventObserver { _, _ -> view.keepScreenOn = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
            lifecycle.addObserver(observer)
            view.keepScreenOn = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            onDispose {
                lifecycle.removeObserver(observer)
                view.keepScreenOn = previous
            }
        }
        val open = rememberOpenLink()
        Surface(Modifier.fillMaxSize().testTag("cooking-view"), color = MaterialTheme.colorScheme.background) {
            Column(Modifier.fillMaxSize().padding(16.dp)) {
                TextButton(onClick = onClose) { Text("Back to recipe") }
                Text(detail.row.title, style = MaterialTheme.typography.headlineMedium)
                Text(detail.servings?.let { "Saved recipe · serves $it" } ?: "Saved recipe · servings not recorded")
                webLink(detail.row.url)?.let { url -> TextButton(onClick = { open(url) }) { Text("Open original recipe") } }
                LazyColumn(contentPadding = PaddingValues(vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                    item { Text("Ingredients as saved", style = MaterialTheme.typography.titleLarge) }
                    detail.sections.forEachIndexed { index, (heading, lines) ->
                        heading?.let { item(key = "h-$index") { Text(it, style = MaterialTheme.typography.titleMedium) } }
                        items(lines) { Text(it, style = MaterialTheme.typography.bodyLarge) }
                    }
                    item {
                        Text("Your cooking notes", style = MaterialTheme.typography.titleLarge)
                        Text(
                            detail.row.notes ?: "No cooking notes saved. Open the original recipe for its method.",
                            style = MaterialTheme.typography.bodyLarge,
                        )
                    }
                }
            }
        }
    }
}
