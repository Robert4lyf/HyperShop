package com.robert4lyf.hypershop.ui.common

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Step AA: how far above the foot of the screen the app's one snackbar sits. A [BottomActionBar] (the list's Finish
 * shopping and Done arranging, the review's Add to list) reports its height here while it is shown, so a message and
 * its Undo sit above the screen's main action, never over it, whatever the font size.
 */
class SnackbarLift {
    var height: Dp by mutableStateOf(0.dp)
        private set
    private var owner: Any? = null

    fun claim(
        by: Any,
        height: Dp,
    ) {
        owner = by
        this.height = height
    }

    fun release(by: Any) {
        if (owner !== by) return
        owner = null
        height = 0.dp
    }
}

/** Provided by the app shell; a bar outside it (a test of one screen) reports to a lift nobody reads. */
val LocalSnackbarLift = staticCompositionLocalOf { SnackbarLift() }
