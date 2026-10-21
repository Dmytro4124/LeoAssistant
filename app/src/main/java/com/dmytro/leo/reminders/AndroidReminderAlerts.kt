package com.dmytro.leo.reminders

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.dmytro.leo.MainActivity
import com.dmytro.leo.R
import com.dmytro.leo.voice.SpeechOutput
import kotlinx.coroutines.withTimeoutOrNull

/** Reminder notifications with "Готово" / "Відкласти", and saying them aloud — in Leo's voice, or the TTS one. */
class AndroidReminderAlerts(
    context: Context,
    /** Created on first use (the TTS engine takes a moment to start). */
    private val speaker: () -> SpeechOutput,
    /** A voice conversation is on: it has the speaker, the notification is enough. */
    private val conversationActive: () -> Boolean,
    /** Leo's own voice (Gemini Live): true if it spoke; otherwise the TTS voice says it. */
    private val leoVoice: suspend (String) -> Boolean = { false },
) : ReminderAlerts {

    private val appContext = context.applicationContext
    private val notifications = appContext.getSystemService(NotificationManager::class.java)
    private val audio = appContext.getSystemService(AudioManager::class.java)

    override fun show(reminder: Reminder, text: String) {
        notifications.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Нагадування", NotificationManager.IMPORTANCE_HIGH).apply {
                description = "Нагадування й заплановані завдання Leo"
            }
        )
        val open = PendingIntent.getActivity(appContext, 0, Intent(appContext, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val builder = NotificationCompat.Builder(appContext, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle(if (reminder.task != null) "Leo виконав завдання" else "Нагадування")
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .addAction(0, "Готово", action(ReminderReceiver.ACTION_DONE, reminder.id))
        if (reminder.task == null) {
            builder.addAction(0, "Через 10 хв", action(ReminderReceiver.ACTION_SNOOZE_10, reminder.id))
            builder.addAction(0, "Через годину", action(ReminderReceiver.ACTION_SNOOZE_60, reminder.id))
        }
        notifications.notify(NOTIFICATION_BASE + reminder.id, builder.build())
    }

    override fun dismiss(id: Int) = notifications.cancel(NOTIFICATION_BASE + id)

    override suspend fun speak(text: String) {
        if (!isSpeakEnabled(appContext) || !canSpeakNow()) return
        val focus = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            .setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).build())
            .build()
        audio.requestAudioFocus(focus)
        try {
            val spoken = runCatching { leoVoice(text) }.getOrDefault(false)
            if (!spoken) withTimeoutOrNull(SPEAK_TIMEOUT_MS) { speaker().speak(text) }
        } finally {
            audio.abandonAudioFocusRequest(focus)
        }
    }

    /** Not on silent or vibrate, not in "do not disturb", not during a call or a conversation with Leo. */
    private fun canSpeakNow(): Boolean =
        audio.ringerMode == AudioManager.RINGER_MODE_NORMAL &&
            audio.mode == AudioManager.MODE_NORMAL &&
            notifications.currentInterruptionFilter == NotificationManager.INTERRUPTION_FILTER_ALL &&
            !conversationActive()

    private fun action(action: String, id: Int): PendingIntent = PendingIntent.getBroadcast(
        appContext, id,
        Intent(appContext, ReminderReceiver::class.java).setAction(action).putExtra(ReminderReceiver.EXTRA_ID, id),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )

    companion object {
        private const val CHANNEL_ID = "reminders"
        private const val NOTIFICATION_BASE = 10_000
        private const val SPEAK_TIMEOUT_MS = 30_000L
        private const val PREFS = "reminders"
        private const val KEY_SPEAK = "speak"

        fun isSpeakEnabled(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_SPEAK, true)

        fun setSpeakEnabled(context: Context, enabled: Boolean) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_SPEAK, enabled) }
    }
}
