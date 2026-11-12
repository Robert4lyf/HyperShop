package com.robert4lyf.hypershop

import android.content.Intent

/**
 * Where an outside entry point (the home-screen widget, the launcher shortcut) asks the app to go. Held by the
 * container until the app shell, composed only once start-up is ready, takes it.
 */
enum class EntryRequest {
    /** The shopping list as it is: no field focused, no mode changed. The widget's item preview. */
    LIST,

    /** The shopping list in planning mode with the Add field focused: the widget's and launcher's Add item. */
    ADD,
    ;

    companion object {
        /** Opens the shopping list (the widget's preview of it). */
        const val ACTION_OPEN_LIST = "com.robert4lyf.hypershop.action.OPEN_LIST"

        /** Opens the Add field (the launcher shortcut, res/xml/shortcuts.xml, and the widget's Add item). */
        const val ACTION_ADD = "com.robert4lyf.hypershop.action.ADD"

        /**
         * What [intent] asks for, or null for anything else. Reopened from Recents, it asked already when it
         * first arrived, so it asks nothing again.
         */
        fun from(intent: Intent?): EntryRequest? {
            if (intent == null || intent.flags and Intent.FLAG_ACTIVITY_LAUNCHED_FROM_HISTORY != 0) return null
            return when (intent.action) {
                ACTION_OPEN_LIST -> LIST
                ACTION_ADD -> ADD
                else -> null
            }
        }
    }
}
