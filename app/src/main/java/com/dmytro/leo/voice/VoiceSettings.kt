package com.dmytro.leo.voice

import android.content.Context
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VoiceMode {
    /** Gemini Live hears and speaks by itself (natural voice, can be interrupted). */
    Live,
    /** SpeechRecognizer → text model → TextToSpeech. Works when Live is unavailable. */
    Classic,
}

/** A prebuilt voice of the Live model. */
data class LiveVoice(val name: String, val description: String)

/** Which voice mode and Live voice are used. */
interface VoiceSettings {
    val mode: StateFlow<VoiceMode>
    val liveVoice: StateFlow<String>
    fun useLive(voiceName: String)
    fun useClassic()

    companion object {
        const val DEFAULT_LIVE_VOICE = "Charon"

        val LIVE_VOICES = listOf(
            LiveVoice("Charon", "чоловічий, спокійний"),
            LiveVoice("Puck", "чоловічий, бадьорий"),
            LiveVoice("Orus", "чоловічий, впевнений"),
            LiveVoice("Fenrir", "чоловічий, енергійний"),
            LiveVoice("Kore", "жіночий, впевнений"),
            LiveVoice("Aoede", "жіночий, легкий"),
            LiveVoice("Leda", "жіночий, молодий"),
            LiveVoice("Zephyr", "жіночий, яскравий"),
        )
    }
}

/** [VoiceSettings] remembered between launches in SharedPreferences. */
class AndroidVoiceSettings(context: Context) : VoiceSettings {

    private val prefs = context.applicationContext.getSharedPreferences("voice_mode", Context.MODE_PRIVATE)

    private val _mode = MutableStateFlow(
        prefs.getString(KEY_MODE, null)?.let { runCatching { VoiceMode.valueOf(it) }.getOrNull() } ?: VoiceMode.Live
    )
    override val mode: StateFlow<VoiceMode> = _mode.asStateFlow()

    private val _liveVoice = MutableStateFlow(prefs.getString(KEY_LIVE_VOICE, null) ?: VoiceSettings.DEFAULT_LIVE_VOICE)
    override val liveVoice: StateFlow<String> = _liveVoice.asStateFlow()

    override fun useLive(voiceName: String) {
        prefs.edit {
            putString(KEY_MODE, VoiceMode.Live.name)
            putString(KEY_LIVE_VOICE, voiceName)
        }
        _mode.value = VoiceMode.Live
        _liveVoice.value = voiceName
    }

    override fun useClassic() {
        prefs.edit { putString(KEY_MODE, VoiceMode.Classic.name) }
        _mode.value = VoiceMode.Classic
    }

    private companion object {
        const val KEY_MODE = "mode"
        const val KEY_LIVE_VOICE = "live_voice"
    }
}
