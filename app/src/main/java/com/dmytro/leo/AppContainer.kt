package com.dmytro.leo

import android.content.Context
import com.dmytro.leo.brain.AgentConfig
import com.dmytro.leo.brain.AgentLoop
import com.dmytro.leo.brain.GeminiApi
import com.dmytro.leo.brain.GeminiClient
import com.dmytro.leo.brain.live.LiveClient
import com.dmytro.leo.voice.AndroidAudioFocus
import com.dmytro.leo.voice.AndroidMicStream
import com.dmytro.leo.voice.AndroidPcmPlayer
import com.dmytro.leo.voice.AndroidSpeaker
import com.dmytro.leo.voice.AndroidSpeechInput
import com.dmytro.leo.voice.AndroidVoiceSettings
import com.dmytro.leo.voice.AudioFocus
import com.dmytro.leo.voice.LiveConversation
import com.dmytro.leo.voice.SpeechInput
import com.dmytro.leo.voice.SpeechOutput
import com.dmytro.leo.voice.VoiceSettings
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency injection: every shared object is created here, once per app process.
 * Screens get what they need from [LeoApp.container].
 */
class AppContainer(context: Context) {

    private val appContext = context.applicationContext

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    val gemini: GeminiApi = GeminiClient(
        apiKey = BuildConfig.GEMINI_API_KEY,
        model = AgentConfig.MODEL,
        httpClient = httpClient,
    )

    val agentLoop = AgentLoop(gemini)

    val speechInput: SpeechInput = AndroidSpeechInput(appContext)
    val speaker: SpeechOutput by lazy { AndroidSpeaker(appContext) }
    val audioFocus: AudioFocus = AndroidAudioFocus(appContext)

    val voiceSettings: VoiceSettings = AndroidVoiceSettings(appContext)
    val liveConversation = LiveConversation(
        live = LiveClient(BuildConfig.GEMINI_API_KEY, httpClient),
        mic = AndroidMicStream(),
        player = AndroidPcmPlayer(appContext),
    )
}
