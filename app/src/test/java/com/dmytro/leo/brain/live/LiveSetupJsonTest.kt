package com.dmytro.leo.brain.live

import com.dmytro.leo.brain.GeminiJson
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSetupJsonTest {

    private fun json(setup: LiveSetup) = GeminiJson.encodeToString(LiveClientMessage(setup = setup))

    private val base = LiveSetup(
        model = "models/test",
        generationConfig = LiveGenerationConfig(speechConfig = SpeechConfig(VoiceConfig(PrebuiltVoiceConfig("Puck")))),
    )

    @Test
    fun `transcripts are switched on even without settings`() {
        val sent = json(base)
        assertTrue(sent, sent.contains("\"inputAudioTranscription\":{}"))
        assertTrue(sent, sent.contains("\"outputAudioTranscription\":{}"))
    }

    @Test
    fun `the user's language goes with the input transcription`() {
        val sent = json(base.copy(inputAudioTranscription = AudioTranscriptionConfig(languageCodes = listOf("uk-UA"))))
        assertTrue(sent, sent.contains("\"inputAudioTranscription\":{\"languageCodes\":[\"uk-UA\"]}"))
    }
}
