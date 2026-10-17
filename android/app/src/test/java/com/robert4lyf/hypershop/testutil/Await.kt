package com.robert4lyf.hypershop.testutil

import android.os.Looper
import kotlinx.coroutines.flow.StateFlow
import org.robolectric.Shadows.shadowOf

/** Runs the main looper (viewModelScope) until [flow] satisfies [ok], or fails after [timeoutMs]. */
fun <T> await(
    flow: StateFlow<T>,
    timeoutMs: Long = 10_000,
    ok: (T) -> Boolean,
): T {
    val end = System.currentTimeMillis() + timeoutMs
    while (System.currentTimeMillis() < end) {
        shadowOf(Looper.getMainLooper()).idle()
        val v = flow.value
        if (ok(v)) return v
        Thread.sleep(5)
    }
    throw AssertionError("timed out; last value ${flow.value}")
}
