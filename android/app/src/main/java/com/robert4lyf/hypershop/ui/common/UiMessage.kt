package com.robert4lyf.hypershop.ui.common

/**
 * A snackbar request; [undo] runs when "Undo" is tapped. A message with Undo stays 5 s, a plain notice 3 s
 * (X.4: ten seconds over the bottom of the list hid the row being worked on). A newer one replaces it.
 */
data class UiMessage(
    val text: String,
    val undo: (suspend () -> Unit)? = null,
    val seconds: Int = if (undo != null) 5 else 3,
    /** The action's label: "Undo", or what else a tap does ("Add 1 more"). */
    val action: String = "Undo",
)
