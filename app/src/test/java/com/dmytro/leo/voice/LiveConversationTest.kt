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
        LiveConversation(api, mic, player, now = { testScheduler.currentTime })

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
    fun `the wake phrase is sent first and shown as the user's turn`() = runTest {
        val job = launch {
            conversation().run(session().let {
                LiveConversation.Session(it.voiceName, it.systemPrompt, it.history, it.tools, it.runTool, openingPhrase = "Привіт, Лео!")
            }) { updates += it }
        }
        server.push(LiveEvent.Ready)
        runCurrent()
        assertEquals(listOf("Привіт, Лео!"), server.texts)
        assertFalse("the mic can't cut off the greeting's answer", mic.recording)

        server.push(LiveEvent.Audio(ByteArray(4)), LiveEvent.OutputTranscript("Привіт! Чим допомогти?"), LiveEvent.TurnComplete)
        runCurrent()
        assertEquals(Update.TurnFinished("Привіт, Лео!", "Привіт! Чим допомогти?"), updates.last { it is Update.TurnFinished })
        assertTrue("listening once answered", mic.recording)
        job.cancel()
    }

    @Test
    fun `the mic starts anyway if the greeting gets no answer`() = runTest {
        val job = launch {
            conversation().run(session().let {
                LiveConversation.Session(it.voiceName, it.systemPrompt, it.history, it.tools, it.runTool, openingPhrase = "Привіт, Лео!")
            }) { updates += it }
        }
        server.push(LiveEvent.Ready)
        runCurrent()
        assertFalse(mic.recording)
        advanceTimeBy(7_000)
        runCurrent()
        assertTrue(mic.recording)
        job.cancel()
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
    fun `silence does not end the session`() = runTest {
        val job = start()
        server.push(LiveEvent.Ready)
        runCurrent()

        advanceTimeBy(10 * 60_000)
        runCurrent()
        assertFalse(server.closed)
        assertFalse(job.isCompleted)
        job.cancel()
    }

    @Test
    fun `saying goodbye ends the session after the farewell`() = runTest {
        var end: LiveConversation.End? = null
        val job = launch {
            end = conversation().run(session().let {
                LiveConversation.Session(
                    it.voiceName, it.systemPrompt, it.history, it.tools,
                    runTool = { _, _ -> com.dmytro.leo.tools.EndConversationTool().execute(buildJsonObject {}) },
                )
            }) { updates += it }
        }
        server.push(LiveEvent.Ready, LiveEvent.InputTranscript("Все, дякую"), LiveEvent.ToolCall(listOf(FunctionCall("end_conversation"))))
        runCurrent()
        assertFalse("not before the goodbye is spoken", server.closed)

        server.push(LiveEvent.Audio(ByteArray(10)), LiveEvent.OutputTranscript("Бувай!"), LiveEvent.TurnComplete)
        advanceTimeBy(300)
        runCurrent()
        assertTrue(job.isCompleted)
        assertEquals(LiveConversation.End.Finished, end)
    }

    @Test
    fun `a session dropped by the server can be resumed`() = runTest {
        var end: LiveConversation.End? = null
        launch { end = conversation().run(session()) { updates += it } }
        server.push(LiveEvent.Ready, LiveEvent.GoAway)
        runCurrent()
        assertEquals(LiveConversation.End.Dropped, end)
    }

    @Test
    fun `session ends after the model confirms music or navigation`() = runTest {
        val job = launch {
            conversation().run(session().let {
                LiveConversation.Session(
                    it.voiceName, it.systemPrompt, it.history, it.tools,
                    runTool = { _, _ -> ToolResult.Success("Вмикаю", endsConversation = true) },
                )
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
    fun `each new spoken phrase counts as a user turn`() = runTest {
        var turns = 0
        val job = launch {
            conversation().run(session().let {
                LiveConversation.Session(it.voiceName, it.systemPrompt, it.history, it.tools, it.runTool, onUserTurn = { turns++ })
            }) { updates += it }
        }
        server.push(
            LiveEvent.Ready,
            LiveEvent.InputTranscript("Напиши"), LiveEvent.InputTranscript(" Олегу"),
            LiveEvent.OutputTranscript("Надіслати?"), LiveEvent.TurnComplete,
            LiveEvent.InputTranscript("Так"),
        )
        runCurrent()

        assertEquals("one per phrase, not per transcript chunk", 2, turns)
        job.cancel()
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
