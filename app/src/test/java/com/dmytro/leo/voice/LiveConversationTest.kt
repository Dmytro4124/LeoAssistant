package com.dmytro.leo.voice

import com.dmytro.leo.brain.Content
import com.dmytro.leo.brain.FunctionCall
import com.dmytro.leo.brain.GeminiError
import com.dmytro.leo.brain.live.LiveEvent
import com.dmytro.leo.tools.ToolResult
import com.dmytro.leo.voice.LiveConversation.Update
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LiveConversationTest {

    private val api = FakeLiveApi()
    private val server = api.connection
    private val mic = FakeMic()
    private val player = FakePlayer()
    private val updates = mutableListOf<Update>()

    private fun TestScope.conversation() =
        LiveConversation(api, mic, player, idleTimeoutMs = 15_000, now = { testScheduler.currentTime })

    private fun TestScope.start(history: List<Content> = emptyList()) =
        launch { conversation().run(session(history)) { updates += it } }

    private fun session(history: List<Content> = emptyList()) = LiveConversation.Session(
        voiceName = "Kore",
        systemPrompt = "Ти Лео",
        history = history,
        tools = emptyList(),
        runTool = { name, _ -> ToolResult.Success("$name виконано") },
    )

    @Test
    fun `a full turn streams audio and reports both transcripts`() = runTest {
        val history = listOf(Content.user("Мене звати Дмитро"))
        val job = start(history)
        server.push(LiveEvent.Ready)
        runCurrent()

        assertEquals("history is sent on start", history, server.history)
        assertTrue("mic streams to the server", mic.recording && server.audioChunksSent == 1)

        server.push(
            LiveEvent.InputTranscript("Як мене"),
            LiveEvent.InputTranscript(" звати?"),
            LiveEvent.Audio(ByteArray(10)),
            LiveEvent.OutputTranscript("Тебе звати"),
            LiveEvent.OutputTranscript(" Дмитро."),
            LiveEvent.TurnComplete,
        )
        runCurrent()

        assertEquals(1, player.played.size)
        assertTrue(Update.UserTranscript("Як мене звати?") in updates)
        assertTrue(Update.AgentTranscript("Тебе звати Дмитро.") in updates)
        assertEquals(Update.TurnFinished("Як мене звати?", "Тебе звати Дмитро."), updates.last { it is Update.TurnFinished })
        assertEquals("Kore", api.setups.single().generationConfig.speechConfig.voiceConfig.prebuiltVoiceConfig.voiceName)

        job.cancel()
        runCurrent()
        assertTrue(server.closed)
        assertFalse("mic is released", mic.recording)
        assertFalse("speaker is released", player.started)
    }

    @Test
    fun `talking over the model stops playback`() = runTest {
        val job = start()
        server.push(LiveEvent.Ready, LiveEvent.Audio(ByteArray(10)), LiveEvent.Interrupted)
        runCurrent()

        assertEquals(1, player.interrupts)
        job.cancel()
    }

    @Test
    fun `connection failure before setup can fall back`() = runTest {
        start()
        server.serverClose(LiveEvent.Closed(GeminiError.RateLimited(null)))
        runCurrent()

        assertEquals(Update.Failed(GeminiError.RateLimited(null), beforeReady = true), updates.last())
    }

    @Test
    fun `silence ends the session`() = runTest {
        val job = start()
        server.push(LiveEvent.Ready)
        runCurrent()

        advanceTimeBy(14_000)
        assertFalse(server.closed)

        advanceTimeBy(2_000)
        runCurrent()
        assertTrue(server.closed)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `session ends after the model confirms music or navigation`() = runTest {
        val job = launch {
            conversation().run(session().let {
                LiveConversation.Session(it.voiceName, it.systemPrompt, it.history, it.tools) { _, _ ->
                    ToolResult.Success("Вмикаю", endsConversation = true)
                }
            }) { updates += it }
        }
        server.push(LiveEvent.Ready, LiveEvent.ToolCall(listOf(FunctionCall("play_music"))))
        runCurrent()
        assertFalse("not before the confirmation is spoken", server.closed)

        server.push(LiveEvent.Audio(ByteArray(10)), LiveEvent.OutputTranscript("Вмикаю"), LiveEvent.TurnComplete)
        advanceTimeBy(300)
        runCurrent()
        assertTrue(server.closed)
        assertTrue(job.isCompleted)
    }

    @Test
    fun `tool calls are run and answered with the same id`() = runTest {
        val job = start()
        val args = buildJsonObject { put("destination", "Будинок культури") }
        server.push(LiveEvent.Ready, LiveEvent.ToolCall(listOf(FunctionCall("navigate_to", args, id = "call-1"))))
        runCurrent()

        val response = server.toolResponses.single()
        assertEquals("call-1", response.id)
        assertEquals("navigate_to виконано", response.response["result"]!!.jsonPrimitive.content)
        assertTrue(updates.any { it is Update.ToolUsed && it.action.name == "navigate_to" })
        job.cancel()
    }
}
