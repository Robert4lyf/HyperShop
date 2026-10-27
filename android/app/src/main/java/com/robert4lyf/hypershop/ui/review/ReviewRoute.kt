package com.robert4lyf.hypershop.ui.review

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.robert4lyf.hypershop.ui.common.cardLift
import com.robert4lyf.hypershop.ui.list.show
import com.robert4lyf.hypershop.ui.list.showTimed
import com.robert4lyf.hypershop.ui.theme.card
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
    /** Opens the review of another job in this one's place ("Try again"). */
    onReopen: (Long) -> Unit = {},
    /** Undo after Cancel: opens the review again, unless another is showing by then. */
    onUndo: (Long) -> Unit = onReopen,
) {
    // Claude may only run while this screen is in the foreground. One hold on the gate per
    // screen: adding the observer replays ON_START when already started, hence the flag.
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        var held = false
        val hold = { on: Boolean ->
            if (on != held) {
                held = on
                if (on) container.reviewGate.acquire() else container.reviewGate.release()
            }
        }
        val obs =
            LifecycleEventObserver { _, e ->
                if (e == Lifecycle.Event.ON_START) hold(true)
                if (e == Lifecycle.Event.ON_STOP) hold(false)
            }
        lifecycle.addObserver(obs)
        onDispose {
            lifecycle.removeObserver(obs)
            hold(false)
        }
    }
    val vm: ReviewViewModel = viewModel(key = "review-$jobId", factory = factoryFor { ReviewViewModel(container, jobId) })
    val state by vm.state.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.events.collect { show(container, snackbar, it) } }
    var sheetKey by rememberSaveable { mutableStateOf<String?>(null) }
    var confirmDiscard by rememberSaveable { mutableStateOf(false) }
    if (confirmDiscard) {
        AlertDialog(
            onDismissRequest = { confirmDiscard = false },
            title = { Text("Discard your changes?") },
            text = { Text("The recipe stays as it was saved.") },
            confirmButton = {
                TextButton(onClick = {
                    confirmDiscard = false
                    vm.cancel()
                }, modifier = Modifier.testTag("discard")) { Text("Discard") }
            },
            dismissButton = { TextButton(onClick = { confirmDiscard = false }) { Text("Keep editing") } },
        )
    }
    // Back on an edit or a library re-add is Cancel: left as it was, it sat on the
    // list as "1 recipe ready to review" for something that was never an import.
    val live = state is ReviewState.Ready || state is ReviewState.Loading
    BackHandler(enabled = live && vm.isThrowaway) {
        // As the on-screen Cancel: an edit with changes asks first (review B.11).
        if ((state as? ReviewState.Ready)?.ui?.changed == true) confirmDiscard = true else vm.cancel()
    }
    when (val s = state) {
        is ReviewState.Loading -> LoadingView(s, onCancel = vm::cancel, onPaste = vm::pasteInstead)
        is ReviewState.Ready ->
            ReviewScreen(
                ui = s.ui,
                onInStock = vm::setInStock,
                onRow = {
                    sheetKey = it
                    vm.noteSheetOpened()
                },
                onServings = vm::setServings,
                onAdd = vm::confirm,
                onSaveForLater = vm::saveForLater,
                // A cancelled import has Undo on its snackbar; only an edit of a saved recipe (no Undo) asks first.
                onCancel = { if (s.ui.changed && vm.isThrowaway) confirmDiscard = true else vm.cancel() },
                onRetryClaude = vm::retryWithClaude,
                onAisleInStock = vm::aisleInStock,
                onClearInStock = vm::clearInStock,
                edits = ReviewEdits(onTitle = vm::setTitle, onAddLine = vm::addLine, onBook = vm::setBook),
                onStopRetry = vm::stopRetry,
                // A shared import can wait on the card; an edit or a library re-add cannot (nothing to come back to).
                onLater = if (vm.isThrowaway) null else onDone,
            )
        is ReviewState.Failed ->
            ManualLinesView(
                s.error,
                s.candidates,
                onUse = vm::useLines,
                onBack = {
                    if (s.error.transient) {
                        // A fetch that may work next time is done with ("Try again" makes a new job); it would sit unlisted otherwise.
                        container.applicationScope.launch { container.importJobs.done(jobId) }
                        onDone()
                    } else {
                        // A page with no recipe is discarded (with Undo).
                        vm.cancel()
                    }
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
                if (s.savedForLater != null) {
                    show(container, snackbar, UiMessage("Saved ${s.savedForLater} to your recipes"))
                    return@LaunchedEffect
                }
                // Outlives this screen: the snackbar and its Undo belong to the list.
                container.applicationScope.launch(Dispatchers.Main) {
                    val r = snackbar.showTimed("Added ${s.count} items", "Undo", 6)
                    if (r == SnackbarResult.ActionPerformed) container.repository.undoImport(s.useId)
                }
            }
        ReviewState.Cancelled ->
            LaunchedEffect(Unit) {
                onDone()
                if (vm.isThrowaway) {
                    // Nothing was changed on the list, so "Import discarded" and its Undo would be noise.
                    container.applicationScope.launch(Dispatchers.Main) { vm.discardForGood() }
                    return@LaunchedEffect
                }
                // The job is only done for good once Undo has had its chance; Undo puts it back and opens it again.
                container.applicationScope.launch(Dispatchers.Main) {
                    val r = snackbar.showTimed("Import discarded", "Undo", 6)
                    if (r == SnackbarResult.ActionPerformed) {
                        container.importJobs.restore(jobId)
                        onUndo(jobId)
                    } else {
                        vm.discardForGood()
                    }
                }
            }
        is ReviewState.AlreadyOnList ->
            AlertDialog(
                // Outside or Back: a shared import stays ready to review; a library "Add to list" (a throwaway job)
                // is dropped, or it would sit on the Shopping tab as "1 recipe ready to review" (review B.13).
                onDismissRequest = { if (vm.isThrowaway) vm.cancel() else onDone() },
                title = { Text("Already on your shopping list") },
                text = { Text("${s.title} is already on your shopping list.") },
                confirmButton = { TextButton(onClick = vm::addAgain) { Text("Add again") } },
                dismissButton = { TextButton(onClick = vm::cancel) { Text("Don't add") } },
            )
    }
    sheetKey?.let { key -> ReviewRowSheetHost(vm, key, onKey = { sheetKey = it }, onDismiss = { sheetKey = null }) }
}

/** The wait for the page, then for Claude: a card with the stage, and a way out of a slow page. */
@Composable
private fun LoadingView(
    s: ReviewState.Loading,
    onCancel: () -> Unit,
    onPaste: () -> Unit,
) {
    Box(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
        Surface(
            Modifier.fillMaxWidth().cardLift(),
            shape = RoundedCornerShape(22.dp),
            color = MaterialTheme.colorScheme.card,
        ) {
            Column(
                Modifier.padding(horizontal = 24.dp, vertical = 28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                CircularProgressIndicator()
                Text(s.stage, Modifier.testTag("loading"), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
                if (s.slow) {
                    Text(
                        "Taking a while",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                    FilledTonalButton(onClick = onPaste, modifier = Modifier.testTag("paste-instead")) { Text("Paste the ingredients") }
                }
                TextButton(onClick = onCancel) { Text("Cancel") }
            }
        }
    }
}

fun errorText(e: ImportError): String =
    when (e) {
        ImportError.BLOCKED -> "That address can't be fetched."
        ImportError.NOT_HTML -> "That link isn't a web page."
        ImportError.TOO_LARGE -> "That page is too large to read."
        ImportError.HTTP -> "The site refused the request."
        ImportError.OFFLINE -> "You're offline."
        ImportError.NO_RECIPE -> "No ingredient list found on that page."
        ImportError.NOT_A_RECIPE -> "That page doesn't look like a recipe."
        ImportError.PHOTO_NEEDS_KEY -> "Reading a photo needs a Claude key."
    }
