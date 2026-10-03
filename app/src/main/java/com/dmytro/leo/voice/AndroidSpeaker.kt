package com.dmytro.leo.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Text-to-speech with the Google TTS engine, Ukrainian voice. One instance per app. */
class AndroidSpeaker(context: Context) : SpeechOutput {

    private val _status = MutableStateFlow(SpeakerStatus.Initializing)
    override val status: StateFlow<SpeakerStatus> = _status.asStateFlow()

    // utteranceId → waiter; completed from TTS callbacks (a background thread)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private val tts: TextToSpeech = TextToSpeech(context.applicationContext, ::onInit, GOOGLE_TTS)

    private fun onInit(initStatus: Int) {
        if (initStatus != TextToSpeech.SUCCESS) {
            _status.value = SpeakerStatus.Failed
            return
        }
        tts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        tts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = finish(utteranceId)
            override fun onStop(utteranceId: String, interrupted: Boolean) = finish(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finish(utteranceId)
            override fun onError(utteranceId: String, errorCode: Int) = finish(utteranceId)
        })

        _status.value = when (tts.setLanguage(UKRAINIAN)) {
            TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED -> SpeakerStatus.MissingUkrainianVoice
            else -> SpeakerStatus.Ready
        }
    }

    private fun finish(utteranceId: String) {
        pending.remove(utteranceId)?.complete(Unit)
    }

    override suspend fun speak(text: String) {
        // The engine starts asynchronously; wait for it on the first phrase
        if (status.first { it != SpeakerStatus.Initializing } != SpeakerStatus.Ready) return

        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        try {
            if (tts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS) {
                done.await()
            }
        } finally {
            // Cancelled mid-phrase (e.g. the user tapped "stop"): silence the engine
            if (!done.isCompleted) tts.stop()
            pending.remove(id)
        }
    }

    companion object {
        const val GOOGLE_TTS = "com.google.android.tts"
        private val UKRAINIAN: Locale = Locale.forLanguageTag("uk-UA")
    }
}
