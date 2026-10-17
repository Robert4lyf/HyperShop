package com.robert4lyf.hypershop.ui.review

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.importing.ImportError
import com.robert4lyf.hypershop.ui.util.factoryFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@Composable
fun ReviewRoute(
    container: AppContainer,
    jobId: Long,
    snackbar: SnackbarHostState,
    onDone: () -> Unit,
) {
    // Claude may only run while this screen is in the foreground.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val obs =
            LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_START) container.reviewGate.set(true)
                if (e == Lifecycle.Event.ON_STOP) container.reviewGate.set(false)
            }
        lifecycle.addObserver(obs)
        if (lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) container.reviewGate.set(true)
        onDispose {
            lifecycle.removeObserver(obs)
            container.reviewGate.set(false)
        }
    }
    val vm: ReviewViewModel = viewModel(key = "review-$jobId", factory = factoryFor { ReviewViewModel(container, jobId) })
    val state by vm.state.collectAsStateWithLifecycle()
    var sheetKey by remember { mutableStateOf<String?>(null) }
    when (val s = state) {
        ReviewState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        is ReviewState.Ready ->
            ReviewScreen(
                ui = s.ui,
                onInStock = vm::setInStock,
                onRow = { sheetKey = it },
                onServings = vm::setServings,
                onAdd = vm::confirm,
                onCancel = vm::cancel,
                onRetryClaude = vm::retryWithClaude,
                onAisleInStock = vm::aisleInStock,
                onClearInStock = vm::clearInStock,
            )
        is ReviewState.Failed -> FailedView(s.error, onDone)
        is ReviewState.Added ->
            LaunchedEffect(s) {
                onDone()
                // Outlives this screen: the snackbar and its Undo belong to the list.
                container.applicationScope.launch(Dispatchers.Main) {
                    val r =
                        snackbar.showSnackbar(
                            "Added ${s.count} items",
                            actionLabel = "Undo",
                            duration = SnackbarDuration.Indefinite,
                        ).let { it }
                    if (r == SnackbarResult.ActionPerformed) container.repository.undoImport(s.useId)
                }
                container.applicationScope.launch(Dispatchers.Main) {
                    kotlinx.coroutines.delay(10_000)
                    snackbar.currentSnackbarData?.dismiss()
                }
            }
        ReviewState.Cancelled -> LaunchedEffect(Unit) { onDone() }
    }
    sheetKey?.let { key -> ReviewRowSheetHost(vm, key, onDismiss = { sheetKey = null }) }
}

@Composable
private fun FailedView(
    error: ImportError,
    onDone: () -> Unit,
) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(errorText(error))
        Button(onClick = onDone, modifier = Modifier.padding(top = 16.dp)) { Text("Back to the list") }
    }
}

fun errorText(e: ImportError): String =
    when (e) {
        ImportError.BLOCKED -> "That address can't be fetched (it isn't a public https page)."
        ImportError.NOT_HTML -> "That link isn't a web page."
        ImportError.TOO_LARGE -> "That page is too large to read."
        ImportError.HTTP -> "The site refused the request. Select the lines manually instead."
        ImportError.OFFLINE -> "You're offline. Try again when you're connected."
        ImportError.NO_RECIPE -> "No ingredient list found on that page."
        ImportError.NOT_A_RECIPE -> "That page doesn't look like a recipe."
    }
