package com.robert4lyf.hypershop.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
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

    companion object {
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
                    } + if (rows.size > 4) "\n+ ${rows.size - 4} more · open for the current list" else "\nTap to open the current list"
                }
            val views = RemoteViews(context.packageName, R.layout.shopping_widget)
            views.setTextViewText(R.id.widget_items, text)
            val intent =
                Intent(context, MainActivity::class.java).setAction(MainActivity.ACTION_ADD)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            val add = PendingIntent.getActivity(context, 20, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            views.setOnClickPendingIntent(R.id.widget_add, add)
            views.setOnClickPendingIntent(R.id.widget_items, add)
            ids.forEach { manager.updateAppWidget(it, views) }
        }
    }
}
