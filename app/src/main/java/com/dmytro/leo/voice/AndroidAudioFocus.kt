package com.dmytro.leo.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager

/**
 * Transient audio focus for one voice exchange: music pauses while the assistant
 * listens and talks, and resumes afterwards. Pausing (not ducking) keeps the music
 * out of the microphone, which helps recognition.
 */
class AndroidAudioFocus(context: Context) : AudioFocus {

    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val request = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        .build()

    override fun acquire() {
        audioManager.requestAudioFocus(request)
    }

    override fun release() {
        audioManager.abandonAudioFocusRequest(request)
    }
}
