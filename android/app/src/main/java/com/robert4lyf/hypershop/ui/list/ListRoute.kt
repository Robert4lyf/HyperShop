package com.robert4lyf.hypershop.ui.list

import android.content.ClipboardManager
import android.content.Intent
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.SnackbarVisuals
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.robert4lyf.hypershop.di.AppContainer
import com.robert4lyf.hypershop.ui.common.UiMessage
import com.robert4lyf.hypershop.ui.common.shareNote
import com.robert4lyf.hypershop.ui.recipes.UseDetailContent
import com.robert4lyf.hypershop.ui.util.factoryFor
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ListRoute(
    container: AppContainer,
    snackbar: SnackbarHostState,
    onReview: (Long) -> Unit,
    /** "Plan this week's shop": the Plan screen. */
    onPlan: () -> Unit = {},
    /** Bumped when the launcher shortcut asks for the Add field (missing 7 of the review). */
    focusAdd: Int = 0,
) {
    val vm: ListViewModel = viewModel(factory = factoryFor { ListViewModel(container) })
    val ui by vm.ui.collectAsStateWithLifecycle()
    val prep by vm.prep.collectAsStateWithLifecycle()
    LaunchedEffect(vm) { vm.events.collect { show(container, snackbar, it) } }
    LaunchedEffect(vm) { vm.reviews.collect(onReview) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    // Built once per view model: a new object on every recomposition would recompose the whole header.
    val header =
        remember(vm, context, onReview, onPlan) {
            HeaderActions(
                onAdd = vm::addTyped,
                suggestions = vm::suggestions,
                onReview = onReview,
                onSample = vm::addSample,
                onPick = vm::addSuggested,
                onPasteLink = {
                    // Read only when tapped, so Android shows no "pasted from your clipboard" for nothing.
                    // Nothing, or no https link, ends in the usual "no link" snackbar.
                    val clip = context.getSystemService(ClipboardManager::class.java)?.primaryClip
                    val text = clip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(context)?.toString()
                    container.applicationScope.launch { container.importShared(text, null) }
                },
                onPlan = onPlan,
                onLastShop = vm::addLastShop,
            )
        }
    ListScreen(
        ui,
        ListActions(
            onToggle = vm::toggle,
            onFinish = vm::finishShopping,
            onClearAll = vm::clearAll,
            onOpenItem = { vm.openItem(it.id) },
            onNeed = vm::needIt,
            onView = vm::setView,
            onMode = vm::setMode,
            onShop = vm::startShopping,
            onUnavailable = vm::unavailable,
            onAvailable = vm::available,
            onArrange = vm::arrange,
            onMoveItem = { r, by -> vm.moveItem(r, by) },
            onMoveItemTo = { r, top -> vm.moveItemToEnd(r, top) },
            onMoveGroup = { a, g, by -> vm.moveGroup(a, g, by) },
            onUseOpen = vm::openUse,
            onUseEdit = vm::editUse,
            onUseRename = { s, t -> vm.renameUse(s, t) },
            onUseRemove = vm::removeUse,
            onShare = {
                val send =
                    Intent(Intent.ACTION_SEND)
                        .setType("text/plain")
                        .putExtra(Intent.EXTRA_TEXT, vm.shareText())
                context.startActivity(Intent.createChooser(send, "Share list"))
            },
        ),
        header = { listHeader(ui, header, prep, focusAdd) },
        empty = { EmptyState(header.onSample, header.onPasteLink, prep, header.onPlan, header.onLastShop) },
    )
    val carry by vm.carryOver.collectAsStateWithLifecycle()
    carry?.let { n ->
        AlertDialog(
            onDismissRequest = vm::keepLeftovers,
            title = { Text(if (n == 1) "1 item not bought" else "$n items not bought") },
            text = { Text("Keep it on the list for the next trip, or take it off?") },
            confirmButton = { TextButton(onClick = vm::keepLeftovers, modifier = Modifier.testTag("carry-keep")) { Text("Keep") } },
            dismissButton = { TextButton(onClick = vm::dropLeftovers, modifier = Modifier.testTag("carry-drop")) { Text("Remove") } },
        )
    }
    val useDetail by vm.useDetail.collectAsStateWithLifecycle()
    useDetail?.let { d ->
        ModalBottomSheet(onDismissRequest = vm::closeUse) {
            UseDetailContent(
                d,
                photo = ui.recipes.firstOrNull { it.libraryKey != null && it.libraryKey == d.use.libraryKey }?.photo,
                onEdit = {
                    vm.closeUse()
                    vm.editUse(d.use)
                },
                onRemove = {
                    vm.closeUse()
                    vm.removeUse(d.use)
                },
            )
        }
    }
    val sheet by vm.sheet.collectAsStateWithLifecycle()
    sheet?.let { s ->
        ItemSheetHost(
            s,
            ItemSheetActions(
                onMove = { a, always -> vm.moveItem(s.id, a, always) },
                onEditQuantity = { vm.editQuantity(s.id, it) },
                onDelete = { vm.delete(s.id, s.name) },
                onSwap = { to, always -> vm.swapItem(s.id, to, always) },
                onToggle = {
                    // The sheet shows the state it opened with, so it closes; the list (and the snackbar) show the result.
                    vm.toggle(s.id)
                    vm.closeItem()
                },
                onWrong = { expected, share ->
                    scope.launch {
                        val text = vm.wrongNote(s, expected)
                        if (share) shareNote(context, text)
                    }
                },
            ),
            onDismiss = vm::closeItem,
        )
    }
}

/**
 * Shows [m] with Undo for [UiMessage.seconds], outliving the screen that asked. A newer message replaces it, even
 * one still waiting its turn: the request before is cancelled, so nothing queues up behind the row being tapped.
 */
fun show(
    container: AppContainer,
    snackbar: SnackbarHostState,
    m: UiMessage,
) = show(container.applicationScope, snackbar, m)

/** [show] on [scope]: the app's own, so the snackbar outlives the screen that asked. */
fun show(
    scope: kotlinx.coroutines.CoroutineScope,
    snackbar: SnackbarHostState,
    m: UiMessage,
) {
    val previous = showing[snackbar]
    val job =
        scope.launch(kotlinx.coroutines.Dispatchers.Main) {
            previous?.cancel()
            val r = snackbar.showTimed(m.text, m.undo?.let { m.action }, m.seconds)
            if (r == SnackbarResult.ActionPerformed) m.undo?.invoke()
        }
    showing[snackbar] = job
}

/** The latest request per host (main thread only), so the next one can take its place. */
private val showing = java.util.WeakHashMap<SnackbarHostState, kotlinx.coroutines.Job>()

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
    // The newest replaces what is showing (X.4): queued, five "at home" taps played back one after another for a
    // minute, each over the row being tapped. Its Undo goes with it; the newest action's Undo is the one shown.
    currentSnackbarData?.dismiss()
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
    // Every snackbar can be swiped away or closed, not only waited out.
    override val withDismissAction: Boolean = true
    override val duration: SnackbarDuration = SnackbarDuration.Indefinite
}
