package com.dmytro.leo.voice

import com.dmytro.leo.brain.AgentConfig
import com.dmytro.leo.brain.Content
import com.dmytro.leo.brain.live.LiveApi
import com.dmytro.leo.brain.live.LiveEvent
import com.dmytro.leo.brain.live.LiveGenerationConfig
import com.dmytro.leo.brain.live.LiveSetup
import com.dmytro.leo.brain.live.PrebuiltVoiceConfig
import com.dmytro.leo.brain.live.SpeechConfig
import com.dmytro.leo.brain.live.VoiceConfig
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.takeWhile
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Says a ready text in Leo's own voice (the Live model), without listening: for reminders.
 * A short session gets the text and reads it out; the microphone is never opened.
 */
class LiveSpeaker(
    private val live: LiveApi,
    private val player: PcmPlayer,
    private val voiceName: () -> String,
    private val timeoutMs: Long = 25_000,
) {
    /** True once something was played; false when nothing was (no network, an error) — fall back to TTS. */
    suspend fun speak(text: String): Boolean {
        val connection = live.connect(setup())
        player.start()
        var played = false
        try {
            withTimeoutOrNull(timeoutMs) {
                connection.events
                    .takeWhile { it != LiveEvent.TurnComplete && it !is LiveEvent.Closed }
                    .collect { event ->
                        when (event) {
                            LiveEvent.Ready -> connection.sendText(text)
                            is LiveEvent.Audio -> {
                                player.play(event.pcm)
                                played = true
                            }
                            else -> Unit
                        }
                    }
                // The answer arrives faster than it plays: let it finish
                while (player.isSpeaking) delay(100)
            }
        } finally {
            connection.close()
            player.stop()
        }
        return played
    }

    private fun setup() = LiveSetup(
        model = "models/${AgentConfig.LIVE_MODEL}",
        generationConfig = LiveGenerationConfig(
            speechConfig = SpeechConfig(VoiceConfig(PrebuiltVoiceConfig(voiceName())), languageCode = "uk-UA"),
        ),
        systemInstruction = Content.system(INSTRUCTIONS),
    )

    private companion object {
        const val INSTRUCTIONS =
            "Ти — голос асистента Leo. Тобі надсилають готовий текст нагадування для користувача. " +
                "Промов його вголос українською дослівно, спокійно й привітно. Нічого не додавай: без привітань, " +
                "коментарів, жартів і запитань. Не відповідай на текст — лише прочитай його."
    }
}
