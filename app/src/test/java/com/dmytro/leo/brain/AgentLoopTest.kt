package com.dmytro.leo.brain

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class AgentLoopTest {

    /** Returns queued results and remembers every request. */
    private class FakeGemini(vararg results: GeminiResult) : GeminiApi {
        private val queue = ArrayDeque(results.toList())
        val requests = mutableListOf<GenerateContentRequest>()

        override suspend fun generateContent(request: GenerateContentRequest): GeminiResult {
            requests += request
            return queue.removeFirst()
        }
    }

    private val clock = Clock.fixed(Instant.parse("2026-10-03T15:30:00Z"), ZoneId.of("Europe/Kyiv"))

    private fun answer(text: String) =
        GeminiResult.Success(Content(role = "model", parts = listOf(Part(text = text, thoughtSignature = "sig"))))

    @Test
    fun `reply text is returned and the exchange is remembered`() = runTest {
        val gemini = FakeGemini(answer("Привіт!"), answer("Мене звати Лео."))
        val agent = AgentLoop(gemini, clock)

        assertEquals(AgentReply.Text("Привіт!"), agent.handle("Привіт"))
        agent.handle("Як тебе звати?")

        val second = gemini.requests[1].contents
        assertEquals(listOf("user", "model", "user"), second.map { it.role })
        assertEquals("Як тебе звати?", second.last().parts.single().text)
        // the model turn is sent back unchanged, signature included
        assertEquals("sig", second[1].parts.single().thoughtSignature)
    }

    @Test
    fun `failed exchange is not remembered`() = runTest {
        val gemini = FakeGemini(GeminiResult.Failure(GeminiError.NoNetwork), answer("Так"))
        val agent = AgentLoop(gemini, clock)

        val reply = agent.handle("Перше")
        agent.handle("Друге")

        assertEquals(AgentReply.Error(GeminiError.NoNetwork), reply)
        assertEquals(listOf("Друге"), gemini.requests[1].contents.map { it.parts.single().text })
    }

    @Test
    fun `blank model text is an error`() = runTest {
        val agent = AgentLoop(FakeGemini(answer("   ")), clock)

        assertEquals(AgentReply.Error(GeminiError.EmptyResponse), agent.handle("?"))
    }

    @Test
    fun `system prompt has local date and time`() = runTest {
        val gemini = FakeGemini(answer("Ок"))
        AgentLoop(gemini, clock).handle("Котра година?")

        val prompt = gemini.requests.single().systemInstruction!!.parts.single().text!!
        assertTrue(prompt, prompt.contains("субота, 3 жовтня 2026, 18:30"))
        assertTrue(prompt.contains(AgentConfig.NAME))
    }

    @Test
    fun `reset forgets history`() = runTest {
        val gemini = FakeGemini(answer("1"), answer("2"))
        val agent = AgentLoop(gemini, clock)

        agent.handle("a")
        agent.reset()
        agent.handle("b")

        assertEquals(1, gemini.requests[1].contents.size)
    }
}
