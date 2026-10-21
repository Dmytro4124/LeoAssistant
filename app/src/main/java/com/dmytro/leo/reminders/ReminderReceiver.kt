package com.dmytro.leo.reminders

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.LocationManager
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dmytro.leo.LeoApp
import com.dmytro.leo.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/** Alarms, arriving at a place, the notification buttons, and reboots all come here. */
class ReminderReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val reminders = (context.applicationContext as LeoApp).container.reminders
        val id = intent.getIntExtra(EXTRA_ID, -1)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED -> reminders.rescheduleAll()
            ACTION_DONE -> reminders.done(id)
            ACTION_SNOOZE_10 -> reminders.snooze(id, 10)
            ACTION_SNOOZE_60 -> reminders.snooze(id, 60)
            ACTION_FIRE -> {
                // A task can take a while (Leo uses the network and other tools): a short foreground service.
                // Exact alarms are allowed to start one from the background.
                if (reminders.store.get(id)?.task != null) {
                    ContextCompat.startForegroundService(context, Intent(context, ReminderTaskService::class.java).putExtra(EXTRA_ID, id))
                } else {
                    inBackground { reminders.fire(id) }
                }
            }
            ACTION_NEAR -> {
                if (intent.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false)) inBackground { reminders.arrived(id) }
            }
        }
    }

    /** Notification and speech take a few seconds: keep the receiver alive until they're done. */
    private fun inBackground(block: suspend () -> Unit) {
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.Main).launch {
            try {
                withTimeoutOrNull(BACKGROUND_LIMIT_MS) { block() }
            } finally {
                pending.finish()
                cancel()
            }
        }
    }

    companion object {
        const val ACTION_FIRE = "com.dmytro.leo.REMINDER_FIRE"
        const val ACTION_NEAR = "com.dmytro.leo.REMINDER_NEAR"
        const val ACTION_DONE = "com.dmytro.leo.REMINDER_DONE"
        const val ACTION_SNOOZE_10 = "com.dmytro.leo.REMINDER_SNOOZE_10"
        const val ACTION_SNOOZE_60 = "com.dmytro.leo.REMINDER_SNOOZE_60"
        const val EXTRA_ID = "id"
        private const val BACKGROUND_LIMIT_MS = 40_000L
    }
}

/** Carries out a scheduled task ("о 8:00 розкажи погоду"): up to 3 minutes, as Android allows. */
class ReminderTaskService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE)
        } catch (e: RuntimeException) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val id = intent?.getIntExtra(ReminderReceiver.EXTRA_ID, -1) ?: -1
        scope.launch {
            try {
                (application as LeoApp).container.reminders.fire(id)
            } finally {
                stopSelf(startId)
            }
        }
        return START_NOT_STICKY
    }

    // A short service must stop when its time is up (Android 14 and 15+ call different versions)
    override fun onTimeout(startId: Int) {
        stopSelf()
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Заплановані завдання", NotificationManager.IMPORTANCE_MIN)
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Leo виконує заплановане завдання")
            .setSilent(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "reminder_tasks"
        const val NOTIFICATION_ID = 3
    }
}
