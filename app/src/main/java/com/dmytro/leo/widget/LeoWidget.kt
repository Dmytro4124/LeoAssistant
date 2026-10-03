package com.dmytro.leo.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.Context
import android.content.Intent
import android.widget.RemoteViews
import com.dmytro.leo.R
import com.dmytro.leo.assistant.AssistantActivity

/** Home-screen mic button: opens the assistant overlay, which starts listening at once. */
class LeoWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        val views = RemoteViews(context.packageName, R.layout.widget_mic).apply {
            setOnClickPendingIntent(R.id.widget_button, assistantIntent(context))
        }
        widgetIds.forEach { manager.updateAppWidget(it, views) }
    }

    companion object {
        fun assistantIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
