package com.splitice.searchcard

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.RemoteViews

class SearchWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) {
            val launch = launchPendingIntent(context, id)
            val views = RemoteViews(context.packageName, R.layout.search_widget)
            views.setOnClickPendingIntent(R.id.search_widget, launch)
            manager.updateAppWidget(id, views)
        }
    }
    override fun onAppWidgetOptionsChanged(context: Context, manager: AppWidgetManager, id: Int, options: Bundle) {
        onUpdate(context, manager, intArrayOf(id))
    }

    companion object {
        const val ACTION_OPEN_WIDGET = "com.splitice.searchcard.OPEN_WIDGET"

        internal fun launchPendingIntent(context: Context, id: Int): PendingIntent = PendingIntent.getActivity(
            context, id,
            Intent(context, MainActivity::class.java).setAction(ACTION_OPEN_WIDGET)
                .putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, id)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_NO_ANIMATION),
            // RemoteViews fills in sourceBounds at click time. The destination and action stay explicit.
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE,
        )
    }
}
