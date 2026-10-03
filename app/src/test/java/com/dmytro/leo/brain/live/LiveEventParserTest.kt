package com.dmytro.leo.brain.live

import com.dmytro.leo.brain.GeminiError
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LiveEventParserTest {

    @Test
    fun `setup complete means ready`() {
        assertEquals(listOf(LiveEvent.Ready), LiveEventParser.parse("""{"setupComplete":{}}"""))
    }

    @Test
    fun `audio chunk is decoded from base64`() {
        val events = LiveEventParser.parse(
            """{"serverContent":{"modelTurn":{"parts":[{"inlineData":{"mimeType":"audio/pcm;rate=24000","data":"AQIDBA=="}}]}}}"""
        )
        val audio = events.single() as LiveEvent.Audio
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), audio.pcm)
    }

    @Test
    fun `transcripts of both sides`() {
        assertEquals(
            listOf(LiveEvent.InputTranscript("Привіт"), LiveEvent.OutputTranscript(" Вітаю")),
            LiveEventParser.parse("""{"serverContent":{"inputTranscription":{"text":"Привіт"},"outputTranscription":{"text":" Вітаю"}}}"""),
        )
    }

    @Test
    fun `interrupted and turn complete`() {
        assertEquals(listOf(LiveEvent.Interrupted), LiveEventParser.parse("""{"serverContent":{"interrupted":true}}"""))
        assertEquals(listOf(LiveEvent.TurnComplete), LiveEventParser.parse("""{"serverContent":{"turnComplete":true}}"""))
    }

    @Test
    fun `go away and unknown messages`() {
        assertEquals(listOf(LiveEvent.GoAway), LiveEventParser.parse("""{"goAway":{"timeLeft":"10s"}}"""))
        assertTrue(LiveEventParser.parse("""{"usageMetadata":{"totalTokenCount":5}}""").isEmpty())
        assertTrue(LiveEventParser.parse("not json").isEmpty())
    }

    @Test
    fun `close reasons`() {
        assertNull(LiveEventParser.closeError(1000, ""))
        assertEquals(GeminiError.RateLimited(null), LiveEventParser.closeError(1011, "You exceeded your current quota"))
        assertEquals(GeminiError.InvalidApiKey, LiveEventParser.closeError(1007, "API key not valid"))
        assertEquals(GeminiError.Server(1011), LiveEventParser.closeError(1011, "Internal error"))
    }
}
