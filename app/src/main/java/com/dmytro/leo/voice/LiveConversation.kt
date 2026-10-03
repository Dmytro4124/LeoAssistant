package com.dmytro.leo.voice

import com.dmytro.leo.brain.AgentConfig
import com.dmytro.leo.brain.Content
import com.dmytro.leo.brain.GeminiError
import com.dmytro.leo.brain.SystemPrompt
import com.dmytro.leo.brain.live.ContextWindowCompression
import com.dmytro.leo.brain.live.LiveApi
import com.dmytro.leo.brain.live.LiveEvent
import com.dmytro.leo.brain.live.LiveGenerationConfig
import com.dmytro.leo.brain.live.LiveSetup
import com.dmytro.leo.brain.live.PrebuiltVoiceConfig
import com.dmytro.leo.brain.live.SlidingWindow
import com.dmytro.leo.brain.live.SpeechConfig
import com.dmytro.leo.brain.live.VoiceConfig
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.time.Clock
import java.time.ZonedDateTime
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * One spoken conversation with the Live model: the microphone streams to Gemini, its
 * voice streams to the speaker, and both sides arrive as text transcripts for the chat.
 * The session ends when [run]'s caller is cancelled, or after [idleTimeoutMs] of silence.
 */
class LiveConversation(
    private val live: LiveApi,
    private val mic: MicStream,
    private val player: PcmPlayer,
    private val clock: Clock = Clock.systemDefaultZone(),
    private val idleTimeoutMs: Long = 15_000,
    private val now: () -> Long = System::currentTimeMillis,
) {

    sealed interface Update {
        /** The model is listening; [level] is the microphone loudness in dB (about -2..10). */
        data class Listening(val level: Float) : Update
        data object Speaking : Update
        /** Text of the user's current turn so far. */
        data class UserTranscript(val text: String) : Update
        /** Text of the model's current turn so far. */
        data class AgentTranscript(val text: String) : Update
        data class TurnFinished(val user: String, val agent: String) : Update
        /** [beforeReady] = the session never started, so another voice mode can be tried. */
        data class Failed(val error: GeminiError, val beforeReady: Boolean) : Update
    }

    suspend fun run(voiceName: String, history: List<Content>, onUpdate: (Update) -> Unit) = coroutineScope {
        val connection = live.connect(setup(voiceName))
        player.start()

        val userText = StringBuilder()
        val agentText = StringBuilder()
        var ready = false
        var lastActivity = now()
        var micJob: Job? = null

        fun finishTurn() {
            val user = userText.toString().trim()
            val agent = agentText.toString().trim()
            if (user.isNotEmpty() || agent.isNotEmpty()) onUpdate(Update.TurnFinished(user, agent))
            userText.clear()
            agentText.clear()
        }

        // Switches the UI back to "listening" when playback ends, and ends a silent session
        val watchdog = launch {
            var wasSpeaking = false
            while (isActive) {
                delay(200)
                val speaking = player.isSpeaking
                if (speaking) lastActivity = now()
                if (wasSpeaking && !speaking) onUpdate(Update.Listening(0f))
                wasSpeaking = speaking
                if (ready && !speaking && now() - lastActivity > idleTimeoutMs) {
                    connection.close()
                    break
                }
            }
        }

        try {
            connection.events.collect { event ->
                when (event) {
                    LiveEvent.Ready -> {
                        ready = true
                        lastActivity = now()
                        connection.sendHistory(history)
                        onUpdate(Update.Listening(0f))
                        micJob = launch {
                            mic.stream().collect { chunk ->
                                connection.sendAudio(chunk)
                                if (!player.isSpeaking) onUpdate(Update.Listening(loudnessDb(chunk)))
                            }
                        }
                    }
                    is LiveEvent.InputTranscript -> {
                        // A new user phrase after the model's answer starts a new turn
                        if (agentText.isNotEmpty()) finishTurn()
                        userText.append(event.text)
                        lastActivity = now()
                        onUpdate(Update.UserTranscript(userText.toString().trim()))
                    }
                    is LiveEvent.Audio -> {
                        player.play(event.pcm)
                        lastActivity = now()
                        onUpdate(Update.Speaking)
                    }
                    is LiveEvent.OutputTranscript -> {
                        agentText.append(event.text)
                        onUpdate(Update.AgentTranscript(agentText.toString().trim()))
                    }
                    LiveEvent.Interrupted -> {
                        player.interrupt()
                        onUpdate(Update.Listening(0f))
                    }
                    LiveEvent.TurnComplete -> {
                        finishTurn()
                        lastActivity = now()
                    }
                    LiveEvent.GoAway -> connection.close()
                    is LiveEvent.Closed -> {
                        event.error?.let { onUpdate(Update.Failed(it, beforeReady = !ready)) }
                    }
                }
            }
            // Let the last answer finish playing before closing the speaker
            while (player.isSpeaking) delay(100)
        } finally {
            finishTurn()
            micJob?.cancel()
            watchdog.cancel()
            connection.close()
            player.stop()
        }
    }

    private fun setup(voiceName: String) = LiveSetup(
        model = "models/${AgentConfig.LIVE_MODEL}",
        generationConfig = LiveGenerationConfig(
            speechConfig = SpeechConfig(VoiceConfig(PrebuiltVoiceConfig(voiceName))),
        ),
        systemInstruction = Content.system(SystemPrompt.build(ZonedDateTime.now(clock))),
        // Keeps long conversations under the free-tier tokens-per-minute limit
        contextWindowCompression = ContextWindowCompression(
            slidingWindow = SlidingWindow(targetTokens = 16_000),
            triggerTokens = 24_000,
        ),
    )

    /** RMS loudness of 16-bit PCM, scaled like SpeechRecognizer's rmsdB (about -2..10). */
    private fun loudnessDb(pcm: ByteArray): Float {
        var sum = 0.0
        val samples = pcm.size / 2
        for (i in 0 until samples) {
            val sample = (pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)
            sum += sample.toDouble() * sample
        }
        val rms = sqrt(sum / maxOf(samples, 1))
        return (20 * log10(maxOf(rms, 1.0)) / 6 - 3).toFloat()
    }
}
