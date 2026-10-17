package com.robert4lyf.hypershop.ui.list

import android.content.Intent
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.ui.common.UiMessage
import com.robert4lyf.hypershop.ui.util.factoryFor
import kotlinx.coroutines.delay
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
    val header = HeaderActions(onAdd = vm::addTyped, suggestions = vm::suggestions, onReview = onReview, onSample = vm::addSample)
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
        val dismiss =
            launch {
                delay(m.seconds * 1000L)
                snackbar.currentSnackbarData?.dismiss()
            }
        val r = snackbar.showSnackbar(m.text, actionLabel = m.undo?.let { "Undo" }, duration = SnackbarDuration.Indefinite)
        dismiss.cancel()
        if (r == SnackbarResult.ActionPerformed) m.undo?.invoke()
    }
}
