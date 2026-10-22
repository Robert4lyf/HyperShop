package com.robert4lyf.hypershop.ui.common

/**
 * A snackbar request; [undo] runs when "Undo" is tapped. A message with Undo
 * stays 10 s so there is time to act; a plain notice goes after 4 s.
 */
data class UiMessage(
    val text: String,
    val undo: (suspend () -> Unit)? = null,
    val seconds: Int = if (undo != null) 10 else 4,
    /** The action's label: "Undo", or what else a tap does ("Add 1 more"). */
    val action: String = "Undo",
)
