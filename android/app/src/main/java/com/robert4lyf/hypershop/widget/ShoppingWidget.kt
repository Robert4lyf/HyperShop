package com.robert4lyf.hypershop.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.robert4lyf.hypershop.EntryRequest
import com.robert4lyf.hypershop.HyperShopApplication
import com.robert4lyf.hypershop.MainActivity
import com.robert4lyf.hypershop.R
import com.robert4lyf.hypershop.core.merge.Display
import com.robert4lyf.hypershop.di.AppContainer
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** RemoteViews keeps the widget usable without a running activity or a new UI dependency. */
class ShoppingWidget : AppWidgetProvider() {
    override fun onUpdate(
        context: Context,
        manager: AppWidgetManager,
        ids: IntArray,
    ) {
        val pending = goAsync()
        val c = (context.applicationContext as HyperShopApplication).container
        c.applicationScope.launch {
            try {
                withTimeoutOrNull(8_000) {
                    c.awaitReady()
                    refresh(c)
                }
            } finally {
                pending.finish()
            }
        }
    }

    /** The widget's two taps. Distinct actions and request codes, so neither replaces the other. */
    data class Taps(
        val openList: PendingIntent,
        val add: PendingIntent,
    )

    companion object {
        private const val REQUEST_ADD = 20
        private const val REQUEST_OPEN_LIST = 21

        /** An activity intent for [action]; single top, so an open app takes it in onNewIntent. */
        fun entryIntent(
            context: Context,
            action: String,
        ): Intent =
            Intent(context, MainActivity::class.java)
                .setAction(action)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

        fun intents(context: Context): Taps {
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            return Taps(
                openList =
                    PendingIntent.getActivity(
                        context,
                        REQUEST_OPEN_LIST,
                        entryIntent(context, EntryRequest.ACTION_OPEN_LIST),
                        flags,
                    ),
                add = PendingIntent.getActivity(context, REQUEST_ADD, entryIntent(context, EntryRequest.ACTION_ADD), flags),
            )
        }

        suspend fun refresh(c: AppContainer) {
            val context = c.context
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, ShoppingWidget::class.java))
            if (ids.isEmpty()) return
            val list = c.repository.snapshot()
            val rows = list.liveItems.filter { !list.isDone(it.id) }
            val text =
                if (rows.isEmpty()) {
                    "Your list is empty. Tap Add item to start."
                } else {
                    rows.take(4).joinToString("\n") { item ->
                        Display.row(list, item, item.catalogId?.let { c.catalog.byId[it] }).main
                    } + if (rows.size > 4) "\n+ ${rows.size - 4} more · tap to open the list" else "\nTap to open the list"
                }
            val views = RemoteViews(context.packageName, R.layout.shopping_widget)
            views.setTextViewText(R.id.widget_items, text)
            views.setContentDescription(
                R.id.widget_items,
                if (rows.isEmpty()) "Shopping list is empty. Open HyperShop" else "Open the shopping list, ${rows.size} to buy",
            )
            // Two actions, two intents: the preview opens the list as it is, Add item opens the Add field.
            val taps = intents(context)
            views.setOnClickPendingIntent(R.id.widget_items, taps.openList)
            views.setOnClickPendingIntent(R.id.widget_add, taps.add)
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }
}
