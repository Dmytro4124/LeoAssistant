package com.dmytro.leo.voice

import android.content.Context
import android.media.AudioAttributes
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.Voice
import androidx.core.content.edit
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Text-to-speech through any installed engine (Google TTS, RHVoice, ...), Ukrainian voice.
 * One instance per app. The chosen engine and voice are remembered between launches.
 */
class AndroidSpeaker(context: Context) : SpeechOutput {

    private val appContext = context.applicationContext
    private val prefs = appContext.getSharedPreferences("voice", Context.MODE_PRIVATE)

    private val _status = MutableStateFlow(SpeakerStatus.Initializing)
    override val status: StateFlow<SpeakerStatus> = _status.asStateFlow()

    private val _voices = MutableStateFlow<List<TtsVoice>>(emptyList())
    override val voices: StateFlow<List<TtsVoice>> = _voices.asStateFlow()

    private val _currentVoice = MutableStateFlow<TtsVoice?>(null)
    override val currentVoice: StateFlow<TtsVoice?> = _currentVoice.asStateFlow()

    // utteranceId → waiter; completed from TTS callbacks (a background thread)
    private val pending = ConcurrentHashMap<String, CompletableDeferred<Unit>>()

    private var tts: TextToSpeech? = null
    private var engine: String = ""
    private var voicesDiscovered = false

    init {
        connect(
            engine = prefs.getString(KEY_ENGINE, null) ?: GOOGLE_TTS,
            voiceName = prefs.getString(KEY_VOICE, null),
        )
    }

    // ---- engine connection ----

    private fun connect(engine: String, voiceName: String?) {
        tts?.shutdown()
        finishAll()
        this.engine = engine
        _status.value = SpeakerStatus.Initializing

        // On failure the callback may fire inside the constructor, before `created` is set
        var created: TextToSpeech? = null
        created = TextToSpeech(appContext, { initStatus ->
            val instance = created
            when {
                instance == null -> _status.value = SpeakerStatus.Failed
                // Ignore late callbacks from an engine we already switched away from
                tts === instance -> onConnected(instance, initStatus, voiceName)
            }
        }, engine)
        tts = created
    }

    private fun onConnected(engineTts: TextToSpeech, initStatus: Int, voiceName: String?) {
        if (initStatus != TextToSpeech.SUCCESS) {
            _status.value = SpeakerStatus.Failed
            return
        }
        engineTts.setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANT)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                .build()
        )
        engineTts.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String) = Unit
            override fun onDone(utteranceId: String) = finish(utteranceId)
            override fun onStop(utteranceId: String, interrupted: Boolean) = finish(utteranceId)
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String) = finish(utteranceId)
            override fun onError(utteranceId: String, errorCode: Int) = finish(utteranceId)
        })

        // The saved voice if it still exists, otherwise the engine's default Ukrainian voice
        val saved = voiceName?.let { name -> engineTts.voices.orEmpty().find { it.name == name } }
        val ok = if (saved != null) {
            engineTts.setVoice(saved) == TextToSpeech.SUCCESS
        } else {
            engineTts.setLanguage(UKRAINIAN) !in listOf(TextToSpeech.LANG_MISSING_DATA, TextToSpeech.LANG_NOT_SUPPORTED)
        }

        _status.value = if (ok) SpeakerStatus.Ready else SpeakerStatus.MissingUkrainianVoice
        _currentVoice.value = engineTts.voice?.takeIf { ok }?.let { TtsVoice(engine, it.name, "") }

        if (!voicesDiscovered) {
            voicesDiscovered = true
            discoverVoices(engineTts.engines)
        }
    }

    // ---- voice list ----

    /** Each installed engine is briefly started to read its list of Ukrainian voices. */
    private fun discoverVoices(engines: List<TextToSpeech.EngineInfo>) {
        engines.forEach { info ->
            var probe: TextToSpeech? = null
            probe = TextToSpeech(appContext, { initStatus ->
                val instance = probe ?: return@TextToSpeech
                if (initStatus == TextToSpeech.SUCCESS) {
                    val found = instance.voices.orEmpty()
                        .filter { it.isUsableUkrainian() }
                        .map { TtsVoice(info.name, it.name, label(info, it)) }
                    _voices.update { current -> (current + found).distinctBy { it.engine to it.name }.sortedBy { it.label } }
                }
                instance.shutdown()
            }, info.name)
        }
    }

    private fun Voice.isUsableUkrainian() =
        locale.language == "uk" &&
            !isNetworkConnectionRequired && // network variants are the same voices, only slower
            "notInstalled" !in features &&
            // aliases of other voices in the list: Google "uk-UA-language", RHVoice "Ukrainian"
            !name.endsWith("-language") &&
            !name.equals(locale.getDisplayLanguage(Locale.ENGLISH), ignoreCase = true)

    private fun label(engine: TextToSpeech.EngineInfo, voice: Voice): String =
        if (engine.name == GOOGLE_TTS) {
            // Google names look like "uk-ua-x-hfd-local"; its engine label is very long
            "Google — " + voice.name.removePrefix("uk-ua-x-").removeSuffix("-local")
        } else {
            "${engine.label} — ${voice.name}"
        }

    override fun selectVoice(voice: TtsVoice) {
        prefs.edit {
            putString(KEY_ENGINE, voice.engine)
            putString(KEY_VOICE, voice.name)
        }
        val current = tts
        val engineVoice = current?.voices?.find { it.name == voice.name }
        val sameEngine = voice.engine == engine && status.value != SpeakerStatus.Initializing
        if (sameEngine && current != null && engineVoice != null) {
            current.stop()
            if (current.setVoice(engineVoice) == TextToSpeech.SUCCESS) {
                _currentVoice.value = voice
                _status.value = SpeakerStatus.Ready
            }
        } else {
            connect(voice.engine, voice.name)
        }
    }

    // ---- speaking ----

    private fun finish(utteranceId: String) {
        pending.remove(utteranceId)?.complete(Unit)
    }

    private fun finishAll() {
        pending.values.forEach { it.complete(Unit) }
        pending.clear()
    }

    override suspend fun speak(text: String) {
        // The engine starts asynchronously; wait for it on the first phrase
        if (status.first { it != SpeakerStatus.Initializing } != SpeakerStatus.Ready) return
        val engineTts = tts ?: return

        val id = UUID.randomUUID().toString()
        val done = CompletableDeferred<Unit>()
        pending[id] = done
        try {
            if (engineTts.speak(text, TextToSpeech.QUEUE_FLUSH, null, id) == TextToSpeech.SUCCESS) {
                done.await()
            }
        } finally {
            // Cancelled mid-phrase (e.g. the user tapped "stop"): silence the engine
            if (!done.isCompleted) engineTts.stop()
            pending.remove(id)
        }
    }

    companion object {
        const val GOOGLE_TTS = "com.google.android.tts"
        private const val KEY_ENGINE = "engine"
        private const val KEY_VOICE = "voice"
        private val UKRAINIAN: Locale = Locale.forLanguageTag("uk-UA")
    }
}
