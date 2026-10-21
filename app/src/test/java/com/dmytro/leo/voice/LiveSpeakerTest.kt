package com.dmytro.leo.voice

import com.dmytro.leo.brain.live.LiveEvent
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveSpeakerTest {

    @Test
    fun `the text is sent once ready and the answer is played in Leo's voice`() = runTest {
        val api = FakeLiveApi()
        val player = FakePlayer()
        var spoken: Boolean? = null
        launch { spoken = LiveSpeaker(api, player, voiceName = { "Kore" }).speak("Нагадую: піти по хліб") }
        api.connection.push(LiveEvent.Ready)
        runCurrent()
        assertEquals(listOf("Нагадую: піти по хліб"), api.connection.texts)
        assertTrue("never listens", api.connection.audioChunksSent == 0)

        api.connection.push(LiveEvent.Audio(ByteArray(8)), LiveEvent.TurnComplete)
        runCurrent()
        assertEquals(true, spoken)
        assertEquals(1, player.played.size)
        assertEquals("Kore", api.setups.single().generationConfig.speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName)
        assertTrue(api.connection.closed)
    }

    @Test
    fun `nothing played means the caller falls back to the TTS voice`() = runTest {
        val api = FakeLiveApi()
        var spoken: Boolean? = null
        launch { spoken = LiveSpeaker(api, FakePlayer(), voiceName = { "Kore" }).speak("Нагадую") }
        api.connection.serverClose(LiveEvent.Closed(com.dmytro.leo.brain.GeminiError.NoNetwork))
        runCurrent()
        assertFalse(spoken!!)
    }
}
