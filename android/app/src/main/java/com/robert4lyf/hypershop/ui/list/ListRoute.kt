package com.robert4lyf.hypershop.ui.list

import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.ui.common.UiMessage
import com.robert4lyf.hypershop.ui.util.factoryFor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@Composable
fun ListRoute(
    container: AppContainer,
    snackbar: SnackbarHostState,
    onReview: (Long) -> Unit,
) {
    val vm: ListViewModel = viewModel(factory = factoryFor { ListViewModel(container) })
    val ui by vm.ui.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.events.collect { show(container, snackbar, it) } }
    val context = LocalContext.current
    val clipboard = remember(context) { context.getSystemService(ClipboardManager::class.java) }
    // Only the description is checked here; the clip itself is read when you tap the chip.
    var clipHasText by remember { mutableStateOf(false) }
    LifecycleResumeEffect(clipboard) {
        clipHasText = clipboard?.primaryClipDescription?.hasMimeType("text/*") == true
        onPauseOrDispose {}
    }
    val paste: (() -> Unit)? =
        if (clipHasText) {
            {
                val text = clipboard?.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                container.applicationScope.launch { container.importShared(text, null) }
            }
        } else {
            null
        }
    val header =
        HeaderActions(
            onAdd = vm::addTyped,
            suggestions = vm::suggestions,
            onReview = onReview,
            onSample = vm::addSample,
            onPasteLink = paste,
        )
    ListScreen(
        ui,
        ListActions(
            onToggle = vm::toggle,
            onDelete = vm::delete,
            onFinish = vm::finishShopping,
            onClearAll = vm::clearAll,
            onOpenItem = { vm.openItem(it.id) },
            onShare = {
                val send =
                    Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, vm.shareText())
                context.startActivity(Intent.createChooser(send, "Share list"))
            },
        ),
        header = { listHeader(ui, header) },
        empty = { EmptyState(header.onSample, header.onPasteLink) },
    )
    val sheet by vm.sheet.collectAsStateWithLifecycle()
    sheet?.let { s ->
        ItemSheetHost(
            s,
            ItemSheetActions(onMove = { a, always -> vm.moveItem(s.id, a, always) }, onEditQuantity = { vm.editQuantity(s.id, it) }),
            onDismiss = vm::closeItem,
        )
    }
    ui.plusOne?.let { PlusOneDialog(it, onYes = vm::confirmPlusOne, onNo = vm::dismissPlusOne) }
}

/** Shows [m] with Undo for [UiMessage.seconds], outliving the screen that asked. */
fun show(
    container: AppContainer,
    snackbar: SnackbarHostState,
    m: UiMessage,
) {
    container.applicationScope.launch(kotlinx.coroutines.Dispatchers.Main) {
        val r = snackbar.showTimed(m.text, m.undo?.let { "Undo" }, m.seconds)
        if (r == SnackbarResult.ActionPerformed) m.undo?.invoke()
    }
}

/**
 * Shows a snackbar for [seconds] once it is on screen. The clock starts when
 * this snackbar appears, not while it waits behind another, and it only ever
 * dismisses this one.
 */
suspend fun SnackbarHostState.showTimed(
    text: String,
    action: String?,
    seconds: Int,
): SnackbarResult =
    coroutineScope {
        val timer =
            launch {
                val mine = snapshotFlow { currentSnackbarData }.first { it?.visuals?.message == text }
                delay(seconds * 1000L)
                if (currentSnackbarData === mine) mine?.dismiss()
            }
        try {
            showSnackbar(text, actionLabel = action, duration = SnackbarDuration.Indefinite)
        } finally {
            timer.cancel()
        }
    }
