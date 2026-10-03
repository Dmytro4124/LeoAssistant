package com.dmytro.leo.tools

import android.content.Context
import android.media.AudioManager
import android.view.KeyEvent
import kotlin.math.roundToInt

enum class MediaKey { Play, Pause, Next, Previous, Stop }

sealed interface VolumeChange {
    data object Up : VolumeChange
    data object Down : VolumeChange
    data class Set(val percent: Int) : VolumeChange
    data object Mute : VolumeChange
    data object Unmute : VolumeChange
}

/** Controls whatever is playing (any player) and the media volume. Tests use a fake. */
interface MediaActions {
    fun press(key: MediaKey)
    /** Applies the change and returns the new media volume in percent. */
    fun changeVolume(change: VolumeChange): Int
}

class AndroidMediaActions(context: Context) : MediaActions {

    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    override fun press(key: MediaKey) {
        val code = when (key) {
            MediaKey.Play -> KeyEvent.KEYCODE_MEDIA_PLAY
            MediaKey.Pause -> KeyEvent.KEYCODE_MEDIA_PAUSE
            MediaKey.Next -> KeyEvent.KEYCODE_MEDIA_NEXT
            MediaKey.Previous -> KeyEvent.KEYCODE_MEDIA_PREVIOUS
            MediaKey.Stop -> KeyEvent.KEYCODE_MEDIA_STOP
        }
        // Delivered to the app with the active media session, like a headset button
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, code))
        audioManager.dispatchMediaKeyEvent(KeyEvent(KeyEvent.ACTION_UP, code))
    }

    override fun changeVolume(change: VolumeChange): Int {
        val stream = AudioManager.STREAM_MUSIC
        val max = audioManager.getStreamMaxVolume(stream)
        val current = audioManager.getStreamVolume(stream)
        val step = maxOf(1, (max * STEP_PERCENT / 100.0).roundToInt())
        when (change) {
            VolumeChange.Up -> audioManager.setStreamVolume(stream, minOf(max, current + step), FLAGS)
            VolumeChange.Down -> audioManager.setStreamVolume(stream, maxOf(0, current - step), FLAGS)
            is VolumeChange.Set ->
                audioManager.setStreamVolume(stream, (max * change.percent.coerceIn(0, 100) / 100.0).roundToInt(), FLAGS)
            VolumeChange.Mute -> audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_MUTE, FLAGS)
            VolumeChange.Unmute -> audioManager.adjustStreamVolume(stream, AudioManager.ADJUST_UNMUTE, FLAGS)
        }
        if (audioManager.isStreamMute(stream)) return 0
        return (audioManager.getStreamVolume(stream) * 100.0 / max).roundToInt()
    }

    private companion object {
        const val STEP_PERCENT = 15
        const val FLAGS = AudioManager.FLAG_SHOW_UI // show the system volume bar
    }
}
