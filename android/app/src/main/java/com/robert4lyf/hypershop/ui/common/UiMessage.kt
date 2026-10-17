package com.robert4lyf.hypershop.ui.common

/** A snackbar request; [undo] runs when "Undo" is tapped. */
data class UiMessage(
    val text: String,
    val undo: (suspend () -> Unit)? = null,
    val seconds: Int = 10,
)
