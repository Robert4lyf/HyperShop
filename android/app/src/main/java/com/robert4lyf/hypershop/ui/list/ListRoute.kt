package com.robert4lyf.hypershop.ui.list

import android.content.Intent
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.platform.LocalContext
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
    val header =
        HeaderActions(
            onAdd = vm::addTyped,
            suggestions = vm::suggestions,
            onReview = onReview,
            onSample = vm::addSample,
        )
    ListScreen(
        ui,
        ListActions(
            onToggle = vm::toggle,
            onFinish = vm::finishShopping,
            onClearAll = vm::clearAll,
            onOpenItem = { vm.openItem(it.id) },
            onNeed = vm::needIt,
            onToggleView = vm::toggleCategorised,
            onShare = {
                val send =
                    Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, vm.shareText())
                context.startActivity(Intent.createChooser(send, "Share list"))
            },
        ),
        header = { listHeader(ui, header) },
        empty = { EmptyState(header.onSample) },
    )
    val sheet by vm.sheet.collectAsStateWithLifecycle()
    sheet?.let { s ->
        ItemSheetHost(
            s,
            ItemSheetActions(
                onMove = { a, always -> vm.moveItem(s.id, a, always) },
                onEditQuantity = { vm.editQuantity(s.id, it) },
                onDelete = { vm.delete(s.id, s.name) },
                onSwap = { to, always -> vm.swapItem(s.id, to, always) },
            ),
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
 * dismisses this one: it is found by its own visuals object, so two snackbars
 * with the same text can't be confused.
 */
suspend fun SnackbarHostState.showTimed(
    text: String,
    action: String?,
    seconds: Int,
): SnackbarResult {
    val visuals = TimedVisuals(text, action)
    return coroutineScope {
        val timer =
            launch {
                val mine = snapshotFlow { currentSnackbarData }.first { it?.visuals === visuals }
                delay(seconds * 1000L)
                if (currentSnackbarData === mine) mine?.dismiss()
            }
        try {
            showSnackbar(visuals)
        } finally {
            timer.cancel()
        }
    }
}

/** A class, not a data class: equal text must still be a different snackbar. */
private class TimedVisuals(
    override val message: String,
    override val actionLabel: String?,
) : SnackbarVisuals {
    override val withDismissAction: Boolean = false
    override val duration: SnackbarDuration = SnackbarDuration.Indefinite
}
