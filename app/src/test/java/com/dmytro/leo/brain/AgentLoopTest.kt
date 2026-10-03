package com.dmytro.leo.brain

import com.dmytro.leo.tools.Param
import com.dmytro.leo.tools.Tool
import com.dmytro.leo.tools.ToolRegistry
import com.dmytro.leo.tools.ToolResult
import com.dmytro.leo.tools.objectSchema
import com.dmytro.leo.tools.string
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class AgentLoopTest {

    private val clock = Clock.fixed(Instant.parse("2026-10-03T15:30:00Z"), ZoneId.of("Europe/Kyiv"))

    private fun answer(text: String) = FakeGemini.answer(text)

    @Test
    fun `reply text is returned and the exchange is remembered`() = runTest {
        val gemini = FakeGemini(answer("Привіт!"), answer("Мене звати Лео."))
        val agent = AgentLoop(gemini, clock = clock)

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
        val agent = AgentLoop(gemini, clock = clock)

        val reply = agent.handle("Перше")
        agent.handle("Друге")

        assertEquals(AgentReply.Error(GeminiError.NoNetwork), reply)
        assertEquals(listOf("Друге"), gemini.requests[1].contents.map { it.parts.single().text })
    }

    @Test
    fun `blank model text is an error`() = runTest {
        val agent = AgentLoop(FakeGemini(answer("   ")), clock = clock)

        assertEquals(AgentReply.Error(GeminiError.EmptyResponse), agent.handle("?"))
    }

    @Test
    fun `system prompt has local date and time`() = runTest {
        val gemini = FakeGemini(answer("Ок"))
        AgentLoop(gemini, clock = clock).handle("Котра година?")

        val prompt = gemini.requests.single().systemInstruction!!.parts.single().text!!
        assertTrue(prompt, prompt.contains("субота, 3 жовтня 2026, 18:30"))
        assertTrue(prompt.contains(AgentConfig.NAME))
    }

    @Test
    fun `tool call is run and its result goes back to the model`() = runTest {
        val call = GeminiResult.Success(
            Content(
                role = "model",
                parts = listOf(Part(functionCall = FunctionCall("navigate_to", buildJsonObject { put("destination", "Київ") }), thoughtSignature = "s1")),
            )
        )
        val gemini = FakeGemini(call, answer("Будую маршрут до Києва."))
        val ran = mutableListOf<String>()
        val tool = object : Tool {
            override val name = "navigate_to"
            override val description = "маршрут"
            override val parameters = objectSchema(Param("destination", "куди"))
            override suspend fun execute(args: JsonObject): ToolResult {
                ran += args.string("destination")!!
                return ToolResult.Success("Навігацію запущено")
            }
        }
        val agent = AgentLoop(gemini, ToolRegistry(listOf(tool)), clock = clock)

        val reply = agent.handle("Поїхали в Київ") as AgentReply.Text

        assertEquals("Будую маршрут до Києва.", reply.text)
        assertEquals(listOf("Київ"), ran)
        assertEquals("navigate_to", reply.actions.single().name)
        // Second request: user, model's call (with signature), our function response
        val second = gemini.requests[1].contents
        assertEquals(listOf("user", "model", "user"), second.map { it.role })
        assertEquals("s1", second[1].parts.single().thoughtSignature)
        assertEquals("Навігацію запущено", second[2].parts.single().functionResponse!!.response["result"]!!.jsonPrimitive.content)
        assertEquals("navigate_to", gemini.requests[0].tools!!.single().functionDeclarations.single().name)
    }

    @Test
    fun `endless tool calls stop after the step limit`() = runTest {
        val call = GeminiResult.Success(Content(role = "model", parts = listOf(Part(functionCall = FunctionCall("x")))))
        val gemini = FakeGemini(*Array(5) { call })
        val agent = AgentLoop(gemini, clock = clock)

        val reply = agent.handle("?")

        assertTrue(reply is AgentReply.Error)
        assertEquals(5, gemini.requests.size)
    }

    @Test
    fun `reset forgets history`() = runTest {
        val gemini = FakeGemini(answer("1"), answer("2"))
        val agent = AgentLoop(gemini, clock = clock)

        agent.handle("a")
        agent.reset()
        agent.handle("b")

        assertEquals(1, gemini.requests[1].contents.size)
    }
}
