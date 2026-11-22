package com.robert4lyf.hypershop.ui.common

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import kotlinx.coroutines.flow.first

/**
 * Step AD.1: a column whose rows are moved by dragging a handle, built on Compose foundation alone. While a row is
 * held it follows the finger and its neighbours swap past it; the move is written once, on release, as "put it at
 * index N" ([onMove]). Near the top or bottom of [scroll]'s viewport the content scrolls, so a row can travel
 * further than one screen. Each row also carries TalkBack's "Move up / down / to top / to bottom" ([moveActions]),
 * and Ctrl or Alt + ↑ / ↓ on a focused handle moves it one place.
 */
@Stable
class ReorderState internal constructor() {
    internal var dragging by mutableStateOf<Any?>(null)
    internal var offset by mutableFloatStateOf(0f)
    internal val heights = mutableStateMapOf<Any, Int>()
    internal val tops = mutableStateMapOf<Any, Float>()

    /** The order shown while a row is held, and after release until the new order comes back. */
    internal var order by mutableStateOf<List<Any>>(emptyList())

    /** The [order] given when the last move was written: while it is still the one given, [order] is shown. */
    internal var committedFrom by mutableStateOf<List<Any>?>(null)
}

@Composable
fun rememberReorderState(): ReorderState = remember { ReorderState() }

/** The scrolling container the rows are in: its [scroll] and where it is on screen. */
@Stable
class ReorderScroll internal constructor(
    val scroll: ScrollState,
) {
    internal var viewport by mutableStateOf<Rect?>(null)
}

@Composable
fun rememberReorderScroll(scroll: ScrollState): ReorderScroll = remember(scroll) { ReorderScroll(scroll) }

/** Put on the scrolling container, so a held row near its edge scrolls it. */
fun Modifier.reorderViewport(s: ReorderScroll): Modifier = onGloballyPositioned { s.viewport = it.boundsInRoot() }

/** TalkBack's moves for the row at [index] of [count]: only the ones that would move it. */
fun moveActions(
    name: String,
    index: Int,
    count: Int,
    moveTo: (Int) -> Unit,
): List<CustomAccessibilityAction> =
    buildList {
        if (index > 0) {
            add(CustomAccessibilityAction("Move $name up") { moveTo(index - 1).let { true } })
            add(CustomAccessibilityAction("Move $name to top") { moveTo(0).let { true } })
        }
        if (index < count - 1) {
            add(CustomAccessibilityAction("Move $name down") { moveTo(index + 1).let { true } })
            add(CustomAccessibilityAction("Move $name to bottom") { moveTo(count - 1).let { true } })
        }
    }

/**
 * The rows of [items], in order, each drawn by [row] with the handle's modifier to put on its [DragHandle]. [key]
 * must be stable for an item, so a held row keeps its gesture as it changes place.
 */
@Composable
fun <T> ReorderColumn(
    items: List<T>,
    key: (T) -> Any,
    onMove: (T, Int) -> Unit,
    state: ReorderState,
    scroll: ReorderScroll?,
    modifier: Modifier = Modifier,
    spacing: Dp = 0.dp,
    row: @Composable (item: T, index: Int, handle: Modifier, held: Boolean) -> Unit,
) {
    val keys = items.map(key)
    // By key, not by value: an item may be null (a group row for things in no group).
    val byKey = items.associateBy(key)
    val spacingPx = with(LocalDensity.current) { spacing.toPx() }
    val edge = with(LocalDensity.current) { 56.dp.toPx() }
    if (state.committedFrom != null && state.committedFrom != keys) state.committedFrom = null
    val shown = if (state.dragging != null || state.committedFrom == keys) state.order else keys
    val currentKeys by rememberUpdatedState(keys)
    val currentOnMove by rememberUpdatedState(onMove)
    val currentByKey by rememberUpdatedState(byKey)

    fun swapPast() {
        val k = state.dragging ?: return
        val order = state.order.toMutableList()
        while (true) {
            val i = order.indexOf(k)
            if (state.offset > 0 && i < order.lastIndex) {
                val step = (state.heights[order[i + 1]] ?: return) + spacingPx
                if (state.offset < step / 2) break
                order[i] = order[i + 1].also { order[i + 1] = k }
                state.offset -= step
            } else if (state.offset < 0 && i > 0) {
                val step = (state.heights[order[i - 1]] ?: return) + spacingPx
                if (-state.offset < step / 2) break
                order[i] = order[i - 1].also { order[i - 1] = k }
                state.offset += step
            } else {
                break
            }
        }
        state.order = order
    }

    fun release() {
        val k = state.dragging ?: return
        val from = currentKeys.indexOf(k)
        val to = state.order.indexOf(k)
        state.dragging = null
        state.offset = 0f
        if (from >= 0 && to >= 0 && from != to) {
            state.committedFrom = currentKeys
            if (k in currentByKey) {
                @Suppress("UNCHECKED_CAST")
                currentOnMove(currentByKey[k] as T, to)
            }
        }
    }

    // A held row near the viewport's edge scrolls it, and stays under the finger.
    LaunchedEffect(state.dragging, scroll) {
        val k = state.dragging ?: return@LaunchedEffect
        if (scroll == null) return@LaunchedEffect
        while (state.dragging == k) {
            val view = scroll.viewport
            val laidTop = state.tops[k]
            val by =
                if (view == null || laidTop == null) {
                    0f
                } else {
                    val top = laidTop + state.offset
                    val bottom = top + (state.heights[k] ?: 0)
                    when {
                        bottom > view.bottom - edge && scroll.scroll.canScrollForward -> 12f
                        top < view.top + edge && scroll.scroll.canScrollBackward -> -12f
                        else -> 0f
                    }
                }
            if (by == 0f) {
                // Nothing to scroll: wait for the finger (or the layout) to move rather than ask for every frame.
                val seen = Triple(state.offset, state.tops[k], scroll.viewport)
                snapshotFlow { Triple(state.offset, state.tops[k], scroll.viewport) to state.dragging }
                    .first { (now, held) -> now != seen || held != k }
                continue
            }
            withFrameNanos { }
            val moved = scroll.scroll.scrollBy(by)
            state.offset += moved
            swapPast()
        }
    }

    Column(modifier) {
        shown.forEachIndexed { index, k ->
            if (k !in byKey) return@forEachIndexed
            @Suppress("UNCHECKED_CAST")
            val item = byKey[k] as T
            key(k) {
                val held = state.dragging == k
                val handle =
                    Modifier.pointerInput(k) {
                        detectDragGestures(
                            onDragStart = {
                                state.order = currentKeys
                                state.offset = 0f
                                state.dragging = k
                            },
                            onDrag = { change, amount ->
                                change.consume()
                                state.offset += amount.y
                                swapPast()
                            },
                            onDragEnd = { release() },
                            onDragCancel = { release() },
                        )
                    }.focusable().onKeyEvent { e ->
                        val modded = e.isCtrlPressed || e.isAltPressed
                        if (!modded || e.type != KeyEventType.KeyDown) return@onKeyEvent false
                        val i = currentKeys.indexOf(k)
                        val to =
                            when (e.key) {
                                Key.DirectionUp -> i - 1
                                Key.DirectionDown -> i + 1
                                else -> return@onKeyEvent false
                            }
                        if (to !in currentKeys.indices || k !in currentByKey) return@onKeyEvent false
                        @Suppress("UNCHECKED_CAST")
                        currentOnMove(currentByKey[k] as T, to)
                        true
                    }
                Box(
                    Modifier
                        .padding(bottom = if (index < shown.lastIndex) spacing else 0.dp)
                        .onGloballyPositioned {
                            state.heights[k] = it.size.height
                            state.tops[k] = it.positionInRoot().y
                        }.zIndex(if (held) 1f else 0f)
                        .graphicsLayer { translationY = if (held) state.offset else 0f },
                ) { row(item, index, handle, held) }
            }
        }
    }
}

/** The drag handle drawn at a row's end: 48 dp, its own TalkBack label left to the row's actions. */
@Composable
fun DragHandle(
    handle: Modifier,
    tag: String,
) {
    Box(handle.size(48.dp).testTag(tag), contentAlignment = Alignment.Center) {
        Icon(Icons.Rounded.DragHandle, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The semantics for a reorderable row: its moves as custom actions. */
fun Modifier.reorderActions(actions: List<CustomAccessibilityAction>): Modifier = semantics { customActions = actions }
