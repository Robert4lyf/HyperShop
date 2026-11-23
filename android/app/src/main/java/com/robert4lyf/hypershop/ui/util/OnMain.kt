package com.robert4lyf.hypershop.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.withContext

/*
 * Every coroutine the UI starts comes back to the main thread, whatever thread woke it.
 *
 * The view models' state is built on Dispatchers.Default and handed on there (`flowOn` fuses into `stateIn`), the
 * container's flows are set from its own scope, Room and DataStore answer on theirs, and a photo is decoded on IO. On a
 * phone the composition's own dispatcher brings each of them back to the main thread. Compose's test clock does not:
 * it resumes the coroutine on the thread that woke it and can run a frame there, which crashed the device tests
 * ("Only the original thread that created a view hierarchy can touch its views", "performMeasureAndLayout called
 * during measure layout") and held the release back. So the UI collects and waits with the main dispatcher itself.
 * `tools/verify.sh` refuses the plain forms outside this file.
 */

/** [collectAsStateWithLifecycle], on the main thread. */
@Composable
fun <T> StateFlow<T>.collectOnMain(): State<T> = collectAsStateWithLifecycle(context = Dispatchers.Main.immediate)

/** [collectAsState] (not paused in the background), on the main thread. */
@Composable
fun <T> StateFlow<T>.collectAlwaysOnMain(): State<T> = collectAsState(context = Dispatchers.Main.immediate)

/** [collectAsState] of a flow with a first value, on the main thread. */
@Composable
fun <T> Flow<T>.collectAlwaysOnMain(initial: T): State<T> = collectAsState(initial, Dispatchers.Main.immediate)

/** [rememberCoroutineScope] whose coroutines run, and come back, on the main thread. */
@Composable
fun rememberMainScope(): CoroutineScope = rememberCoroutineScope { Dispatchers.Main.immediate }

/** For an effect's body (LaunchedEffect, produceState) that waits on something off the main thread. */
suspend fun <T> onMain(block: suspend CoroutineScope.() -> T): T = withContext(Dispatchers.Main.immediate, block)
