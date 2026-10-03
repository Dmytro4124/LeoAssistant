package com.dmytro.leo.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.dmytro.leo.LeoApp
import com.dmytro.leo.MainActivity
import com.dmytro.leo.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Foreground service of type "microphone": while a voice conversation runs, Android keeps
 * our microphone working even when Maps or a messenger is on screen. Shows "Лео слухає"
 * with a Stop button, and stops itself when the conversation ends.
 */
class VoiceSessionService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val session = (application as LeoApp).container.voiceSession
        if (intent?.action == ACTION_STOP) {
            session.stop()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: RuntimeException) {
            // No mic permission, or not allowed from the background: the conversation still
            // works while our screen is open, it just can't outlive it
            stopSelf()
            return START_NOT_STICKY
        }
        scope.launch {
            session.state.first { it == VoiceState.Idle && !session.isActive }
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Розмова з Лео", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Показується, поки Лео слухає"
            }
        )
        val openChat = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, VoiceSessionService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Лео слухає")
            .setContentText("Говори — або натисни «Зупинити»")
            .setContentIntent(openChat)
            .addAction(R.drawable.ic_stop, "Зупинити", stop)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val CHANNEL_ID = "voice_session"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_STOP = "com.dmytro.leo.STOP_VOICE"
    }
}

/** Starts [VoiceSessionService]; it stops itself when the conversation ends. */
class ServiceForegroundKeeper(context: Context) : ForegroundKeeper {
    private val appContext = context.applicationContext

    override fun start() {
        try {
            ContextCompat.startForegroundService(appContext, Intent(appContext, VoiceSessionService::class.java))
        } catch (e: IllegalStateException) {
            // Started from the background: not allowed, the conversation works without it
        }
    }

    override fun stop() = Unit
}
