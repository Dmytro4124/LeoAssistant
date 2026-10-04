package com.dmytro.leo.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.SizeF
import android.widget.RemoteViews
import com.dmytro.leo.LeoApp
import com.dmytro.leo.MainActivity
import com.dmytro.leo.R
import com.dmytro.leo.assistant.AssistantActivity
import com.dmytro.leo.assistant.VoiceState
import com.dmytro.leo.wake.WakeWordService

/**
 * Home-screen banner: a start/stop button for the conversation and a «Привіт, Leo» switch.
 * Redrawn whenever a conversation starts or ends and when the wake word is switched ([refresh]).
 */
class LeoWidget : AppWidgetProvider() {

    override fun onUpdate(context: Context, manager: AppWidgetManager, widgetIds: IntArray) {
        val views = views(context)
        widgetIds.forEach { manager.updateAppWidget(it, views) }
    }

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_STOP -> (context.applicationContext as LeoApp).container.voiceSession.stop()
            ACTION_TOGGLE_WAKE -> {
                if (WakeWordService.isEnabled(context)) {
                    WakeWordService.setEnabled(context, false)
                    WakeWordService.stop(context)
                } else {
                    WakeWordService.setEnabled(context, true)
                    // A tap on a widget lets the app start its microphone service from the background
                    WakeWordService.startIfEnabled(context)
                }
            }
            else -> super.onReceive(context, intent)
        }
    }

    companion object {
        private const val ACTION_STOP = "com.dmytro.leo.widget.STOP"
        private const val ACTION_TOGGLE_WAKE = "com.dmytro.leo.widget.TOGGLE_WAKE"
        private const val PREFS = "widget"
        private const val KEY_SYSTEM_STYLE = "system_style"

        /** Redraws every Leo widget on the home screen. */
        fun refresh(context: Context) {
            val manager = AppWidgetManager.getInstance(context)
            val ids = manager.getAppWidgetIds(ComponentName(context, LeoWidget::class.java))
            if (ids.isNotEmpty()) manager.updateAppWidget(ids, views(context))
        }

        /**
         * The system style (Material You colours, like themed launcher icons) instead of crimson and gold.
         * Android doesn't let apps read the launcher's "themed icons" switch, so it is a setting of Leo's own.
         */
        fun isSystemStyle(context: Context) =
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SYSTEM_STYLE, false)

        fun setSystemStyle(context: Context, on: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(KEY_SYSTEM_STYLE, on).apply()
            refresh(context)
        }

        /** The launcher picks the layout that fits: just the button in one cell, the full banner when wider. */
        private fun views(context: Context): RemoteViews {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return views(context, R.layout.widget_leo, system = false)
            val system = isSystemStyle(context)
            return RemoteViews(
                mapOf(
                    SizeF(56f, 56f) to views(context, if (system) R.layout.widget_leo_sys_small else R.layout.widget_leo_small, system),
                    SizeF(180f, 56f) to views(context, if (system) R.layout.widget_leo_sys else R.layout.widget_leo, system),
                )
            )
        }

        private fun views(context: Context, layout: Int, system: Boolean): RemoteViews {
            val container = (context.applicationContext as LeoApp).container
            val talking = container.voiceSession.state.value != VoiceState.Idle
            val wakeOn = WakeWordService.isEnabled(context)

            // Views missing from the small layout are skipped by RemoteViews
            return RemoteViews(context.packageName, layout).apply {
                // Starting needs the overlay (only a visible screen may open the microphone); stopping doesn't
                setOnClickPendingIntent(R.id.widget_button, if (talking) broadcast(context, ACTION_STOP) else assistantIntent(context))
                setImageViewResource(R.id.widget_button, if (talking) R.drawable.ic_widget_stop else R.drawable.ic_widget_mic)
                if (system && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    // Colour resources are resolved by the launcher, so they follow wallpaper and dark mode
                    setInt(R.id.widget_button, "setBackgroundResource", if (talking) R.drawable.widget_sys_button_active else R.drawable.widget_sys_button_idle)
                    setColorStateList(R.id.widget_button, "setImageTintList", if (talking) R.color.widget_sys_accent else R.color.widget_sys_on_accent)
                    setImageViewResource(R.id.widget_wake_switch, if (wakeOn) R.drawable.widget_sys_switch_on else R.drawable.widget_sys_switch_off)
                } else {
                    setInt(R.id.widget_button, "setBackgroundResource", if (talking) R.drawable.widget_button_active else R.drawable.widget_button_idle)
                    setInt(R.id.widget_button, "setColorFilter", context.getColor(if (talking) R.color.lion_gold_light else R.color.lion_crimson_dark))
                    setImageViewResource(R.id.widget_wake_switch, if (wakeOn) R.drawable.widget_switch_on else R.drawable.widget_switch_off)
                }
                setTextViewText(
                    R.id.widget_status,
                    context.getString(
                        when {
                            talking -> R.string.widget_status_active
                            wakeOn -> R.string.widget_status_wake
                            else -> R.string.widget_status_idle
                        }
                    ),
                )

                // The wake model (~78 MB) is downloaded from Settings the first time
                val wakeAction = if (wakeOn || container.wakeModel.isReady) broadcast(context, ACTION_TOGGLE_WAKE) else settingsIntent(context)
                setOnClickPendingIntent(R.id.widget_wake, wakeAction)
            }
        }

        private fun broadcast(context: Context, action: String): PendingIntent = PendingIntent.getBroadcast(
            context,
            action.hashCode(),
            Intent(context, LeoWidget::class.java).setAction(action),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun settingsIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            1,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_SETTINGS, true)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        fun assistantIntent(context: Context): PendingIntent = PendingIntent.getActivity(
            context,
            0,
            Intent(context, AssistantActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }
}
