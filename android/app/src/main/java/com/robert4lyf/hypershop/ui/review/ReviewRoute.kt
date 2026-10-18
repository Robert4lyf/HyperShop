package com.robert4lyf.hypershop.ui.review

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.importing.ImportError
import com.robert4lyf.hypershop.ui.common.UiMessage
import com.robert4lyf.hypershop.ui.list.show
import com.robert4lyf.hypershop.ui.list.showTimed
import com.robert4lyf.hypershop.ui.util.factoryFor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun ReviewRoute(
    container: AppContainer,
    jobId: Long,
    snackbar: SnackbarHostState,
    onDone: () -> Unit,
    /** Opens the review of another job in this one's place (Undo after Cancel, "Try again"). */
    onReopen: (Long) -> Unit = {},
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
    LaunchedEffect(vm) { vm.events.collect { show(container, snackbar, it) } }
    var sheetKey by remember { mutableStateOf<String?>(null) }
    var confirmDiscard by remember { mutableStateOf(false) }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard this recipe?") },
            text = { Text("The changes you made here will be lost.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    vm.cancel()
                }, modifier = Modifier.testTag("discard")) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
    when (val s = state) {
        is ReviewState.Loading -> LoadingView(s, onCancel = vm::cancel, onPaste = vm::pasteInstead)
        is ReviewState.Ready ->
            ReviewScreen(
                ui = s.ui,
                onInStock = vm::setInStock,
                onRow = { sheetKey = it },
                onServings = vm::setServings,
                onAdd = vm::confirm,
                onCancel = { if (s.ui.changed) confirmDiscard = true else vm.cancel() },
                onRetryClaude = vm::retryWithClaude,
                onAisleInStock = vm::aisleInStock,
                onClearInStock = vm::clearInStock,
                edits = ReviewEdits(onTitle = vm::setTitle, onAddLine = vm::addLine),
                onStopRetry = vm::stopRetry,
            )
        is ReviewState.Failed ->
            ManualLinesView(
                s.error,
                s.candidates,
                onUse = vm::useLines,
                onBack = {
                    // A fetch that may work next time leaves the job as it is; a page with no recipe is discarded (with Undo).
                    if (!s.error.transient) vm.cancel() else onDone()
                },
                onRetry =
                    if (!s.error.transient) {
                        null
                    } else {
                        {
                            // The same address again, as a new job; this one is done with.
                            container.applicationScope.launch {
                                val job = container.importJobs.get(jobId) ?: return@launch
                                if (job.input.url.isEmpty()) return@launch
                                val id = container.importJobs.enqueue(job.input)
                                container.importJobs.done(jobId)
                                withContext(Dispatchers.Main) { onReopen(id) }
                            }
                        }
                    },
            )
        is ReviewState.Added ->
            LaunchedEffect(s) {
                onDone()
                if (s.edited != null) {
                    show(container, snackbar, UiMessage("Saved changes to ${s.edited}"))
                    return@LaunchedEffect
                }
                // Outlives this screen: the snackbar and its Undo belong to the list.
                container.applicationScope.launch(Dispatchers.Main) {
                    val r = snackbar.showTimed("Added ${s.count} items", "Undo", 10)
                    if (r == SnackbarResult.ActionPerformed) container.repository.undoImport(s.useId)
                }
            }
        ReviewState.Cancelled ->
            LaunchedEffect(Unit) {
                onDone()
                // The job is only done for good once Undo has had its chance; Undo opens this review again.
                container.applicationScope.launch(Dispatchers.Main) {
                    val r = snackbar.showTimed("Import discarded", "Undo", 10)
                    if (r == SnackbarResult.ActionPerformed) onReopen(jobId) else vm.discardForGood()
                }
            }
        is ReviewState.AlreadyOnList ->
            AlertDialog(
                // Outside or Back: nothing happens, the job stays ready to review.
                onDismissRequest = onDone,
                title = { Text("Already on your list") },
                text = { Text("${s.title} is already on your list. The amounts will be added on top of what is already there.") },
                confirmButton = { TextButton(onClick = vm::addAgain) { Text("Add again") } },
                dismissButton = { TextButton(onClick = vm::cancel) { Text("Don't add") } },
            )
    }
    sheetKey?.let { key -> ReviewRowSheetHost(vm, key, onKey = { sheetKey = it }, onDismiss = { sheetKey = null }) }
}

/** The wait for the page, then for Claude, with a way out of a slow page. */
@Composable
private fun LoadingView(
    s: ReviewState.Loading,
    onCancel: () -> Unit,
    onPaste: () -> Unit,
) {
    Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically),
    ) {
        CircularProgressIndicator()
        Text(s.stage, Modifier.testTag("loading"), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        if (s.slow) {
            Text(
                "Taking a while. You can paste the ingredients instead.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
            FilledTonalButton(onClick = onPaste, modifier = Modifier.testTag("paste-instead")) { Text("Paste the ingredients") }
        }
        TextButton(onClick = onCancel) { Text("Cancel") }
    }
}

fun errorText(e: ImportError): String =
    when (e) {
        ImportError.BLOCKED -> "That address can't be fetched (it isn't a public https page)."
        ImportError.NOT_HTML -> "That link isn't a web page."
        ImportError.TOO_LARGE -> "That page is too large to read."
        ImportError.HTTP -> "The site refused the request (some sites block apps)."
        ImportError.OFFLINE -> "You're offline. Share the link again when you're connected, or paste the lines."
        ImportError.NO_RECIPE -> "No ingredient list found on that page."
        ImportError.NOT_A_RECIPE -> "That page doesn't look like a recipe."
        ImportError.PHOTO_NEEDS_KEY -> "Reading a photo needs your Claude key (Settings › Claude). Or type the ingredients below."
    }
