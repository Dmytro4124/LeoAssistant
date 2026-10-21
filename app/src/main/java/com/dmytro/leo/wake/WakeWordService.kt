package com.dmytro.leo.wake

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.IBinder
import android.os.SystemClock
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.dmytro.leo.LeoApp
import com.dmytro.leo.widget.LeoWidget
import com.dmytro.leo.MainActivity
import com.dmytro.leo.R
import com.dmytro.leo.assistant.VoiceSession
import com.dmytro.leo.assistant.VoiceState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.vosk.LibVosk
import org.vosk.LogLevel
import org.vosk.Model
import org.vosk.Recognizer

/**
 * Listens for "Привіт, Leo" all the time, fully offline (Vosk), and starts a voice
 * conversation when it hears it. Pauses while a conversation runs. Nothing leaves the
 * phone until the wake phrase is heard.
 */
class WakeWordService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var model: Model? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_TURN_OFF) {
            setEnabled(this, false)
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } catch (e: RuntimeException) {
            // Not allowed now (e.g. started from the background): it starts again next time Leo is opened
            stopSelf()
            return START_NOT_STICKY
        }
        if (model == null) scope.launch { listenForever() }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        model?.close()
        model = null
        super.onDestroy()
    }

    private suspend fun listenForever() {
        val container = (application as LeoApp).container
        val wakeModel = container.wakeModel
        if (!wakeModel.isReady) {
            stopSelf()
            return
        }
        LibVosk.setLogLevel(LogLevel.WARNINGS)
        val loaded = Model(wakeModel.directory.absolutePath).also { model = it }
        val session = container.voiceSession

        while (scope.isActive) {
            // The conversation has the microphone; wait until it's over
            session.state.first { it == VoiceState.Idle && !session.isActive }
            delay(300)
            if (listenOnce(loaded) { session.isActive }) {
                beep()
                withContext(Dispatchers.Main) { session.start(openingPhrase = VoiceSession.WAKE_GREETING) }
                delay(1000) // let the conversation take the mic before waiting on it
            }
        }
    }

    /** Reads the mic until the wake phrase (true) or until a conversation starts elsewhere (false). */
    private fun listenOnce(model: Model, conversationStarted: () -> Boolean): Boolean {
        // Free transcription (no grammar): the model has no "лео", see WakePhrase
        val recognizer = Recognizer(model, WakeMic.SAMPLE_RATE.toFloat())
        val learned = learnedPhrases(this)
        val matcher = WakePhrase.Matcher(learned)
        try {
            WakeMic().use { mic ->
                val buffer = ByteArray(WakeMic.CHUNK)
                Log.d(TAG, "listening (recorder state ${mic.state}, ${learned.size} learned phrases)")
                while (scope.isActive && !conversationStarted()) {
                    val read = mic.read(buffer)
                    if (read <= 0) {
                        Log.w(TAG, "mic read returned $read")
                        continue
                    }
                    AudioGain.apply(buffer, read, gain(this))
                    val isFinal = recognizer.acceptWaveForm(buffer, read)
                    val heard = if (isFinal) recognizer.result else recognizer.partialResult
                    val text = WakePhrase.textOf(heard)
                    // For tuning: only phrases with a greeting are logged, never other speech
                    if (isFinal && text.isNotBlank() && WakePhrase.hasGreeting(text)) Log.d(TAG, "heard: \"$text\"")
                    if (matcher.hear(text, isFinal, SystemClock.elapsedRealtime())) {
                        Log.d(TAG, "wake phrase: $text")
                        return true
                    }
                }
                return false
            }
        } finally {
            recognizer.close()
        }
    }


    private fun beep() {
        val tone = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 80)
        tone.startTone(ToneGenerator.TONE_PROP_ACK, 150)
        scope.launch {
            delay(400)
            tone.release()
        }
    }

    private fun notification(): Notification {
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "Пробудження «Привіт, Leo»", NotificationManager.IMPORTANCE_MIN).apply {
                description = "Показується, поки Leo чекає на ключову фразу"
            }
        )
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val off = PendingIntent.getService(
            this, 1, Intent(this, WakeWordService::class.java).setAction(ACTION_TURN_OFF), PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_mic)
            .setContentTitle("Leo чекає на «Привіт, Leo»")
            .setContentText("Розпізнавання офлайн — нікуди не надсилається")
            .setContentIntent(open)
            .addAction(R.drawable.ic_stop, "Вимкнути", off)
            .setOngoing(true)
            .setSilent(true)
            .build()
    }

    companion object {
        private const val TAG = "LeoWake"
        private const val CHANNEL_ID = "wake_word"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_TURN_OFF = "com.dmytro.leo.WAKE_OFF"
        private const val PREFS = "wake_word"
        private const val KEY_ENABLED = "enabled"
        private const val KEY_SENSITIVITY = "sensitivity"
        private const val KEY_GAIN = "calibrated_gain"
        private const val KEY_LEARNED = "learned_phrases"

        fun isEnabled(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_ENABLED, false)

        fun setEnabled(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_ENABLED, enabled) }
            LeoWidget.refresh(context)
        }

        fun sensitivity(context: Context): WakeSensitivity =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_SENSITIVITY, null)
                ?.let { name -> WakeSensitivity.entries.firstOrNull { it.name == name } } ?: WakeSensitivity.Medium

        /** Takes effect right away: the listener reads it for every chunk of audio. */
        fun setSensitivity(context: Context, value: WakeSensitivity) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY_SENSITIVITY, value.name) }

        /** The microphone gain in use: the chosen level's, or the calibrated one for [WakeSensitivity.Personal]. */
        fun gain(context: Context): Float {
            val level = sensitivity(context)
            return if (level == WakeSensitivity.Personal) {
                context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getFloat(KEY_GAIN, WakeSensitivity.Medium.gain)
            } else level.gain
        }

        fun isCalibrated(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).contains(KEY_GAIN)

        /** How the recognizer hears this user's phrase (from calibration); used at every sensitivity. */
        fun learnedPhrases(context: Context): Set<String> =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_LEARNED, null).orEmpty()

        /** Saves the calibration and switches to it. The listener picks the phrases up on its next round. */
        fun saveCalibration(context: Context, result: WakeCalibration.Result) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                putFloat(KEY_GAIN, result.gain)
                putStringSet(KEY_LEARNED, result.learned)
                putString(KEY_SENSITIVITY, WakeSensitivity.Personal.name)
            }

        fun clearCalibration(context: Context) =
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit {
                remove(KEY_GAIN)
                remove(KEY_LEARNED)
                if (sensitivity(context) == WakeSensitivity.Personal) putString(KEY_SENSITIVITY, WakeSensitivity.Medium.name)
            }

        /** Call while one of our screens is visible: Android allows starting the mic service only then. */
        fun startIfEnabled(context: Context) {
            if (!isEnabled(context) || !(context.applicationContext as LeoApp).container.wakeModel.isReady) return
            try {
                ContextCompat.startForegroundService(context, Intent(context, WakeWordService::class.java))
            } catch (e: IllegalStateException) {
                // Not allowed right now; next time
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, WakeWordService::class.java))
        }
    }
}
