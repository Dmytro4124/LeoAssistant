package com.dmytro.leo.tools

import com.dmytro.leo.accessibility.Screen
import com.dmytro.leo.accessibility.ScreenResult
import com.dmytro.leo.accessibility.UiElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UiToolsTest {

    private class FakeScreen(var result: ScreenResult) : ScreenAgent {
        val elements = mapOf(
            1 to UiElement(1, "перемикач", "Перемішування", password = false, path = listOf(0)),
            2 to UiElement(2, "кнопка", "Видалити плейлист", password = false, path = listOf(1)),
            3 to UiElement(3, "поле пароля", "Пароль", password = true, path = listOf(2)),
        )
        val clicks = mutableListOf<Int>()
        val typed = mutableListOf<Pair<Int, String>>()

        override suspend fun read(detailed: Boolean) = result
        override suspend fun click(id: Int) = result.also { clicks += id }
        override suspend fun type(id: Int, text: String) = result.also { typed += id to text }
        override suspend fun scroll(id: Int?, forward: Boolean) = result
        override suspend fun back() = result
        override fun element(id: Int) = elements[id]
        override val currentPackage = "app.rvx.android.apps.youtube.music"
    }

    private val screen = Screen("RVX Music", "Програма: RVX Music\n[1] перемикач «Перемішування» (вимкнено)", emptyMap())
    private val fake = FakeScreen(ScreenResult.Ok(screen))
    private val turns = UserTurns()
    private val limiter = UiStepLimiter(turns, max = 3)
    private val gate = ConfirmationGate(turns)

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { put(it.first, it.second) } }

    @Test
    fun `reading and clicking return the new screen`() = runTest {
        assertEquals(ToolResult.Success(screen.text), UiReadScreenTool(fake, limiter).execute(args()))
        assertEquals(ToolResult.Success(screen.text), UiClickTool(fake, limiter, gate).execute(args("element" to "[1]")))
        assertEquals(listOf(1), fake.clicks)
    }

    @Test
    fun `risky buttons need the user's yes`() = runTest {
        val click = UiClickTool(fake, limiter, gate)
        val ask = click.execute(args("element" to "2")) as ToolResult.NeedsConfirmation
        assertTrue(fake.clicks.isEmpty())

        turns.next() // "так"
        assertTrue(click.execute(args("element" to "2", "confirmation" to ask.token)) is ToolResult.Success)
        assertEquals(listOf(2), fake.clicks)
    }

    @Test
    fun `nothing is typed into password fields`() = runTest {
        val result = UiTypeTool(fake, limiter).execute(args("element" to "3", "text" to "1234"))
        assertTrue(result is ToolResult.Failure)
        assertTrue(fake.typed.isEmpty())
    }

    @Test
    fun `steps are limited per user phrase`() = runTest {
        val read = UiReadScreenTool(fake, limiter)
        repeat(3) { assertTrue(read.execute(args()) is ToolResult.Success) }
        assertTrue((read.execute(args()) as ToolResult.Failure).message.startsWith("Забагато дій"))

        turns.next() // the user said something: a fresh budget
        assertTrue(read.execute(args()) is ToolResult.Success)
    }

    @Test
    fun `blocked apps and a disabled service are explained`() = runTest {
        fake.result = ScreenResult.Blocked("monobank")
        assertTrue((UiReadScreenTool(fake, limiter).execute(args()) as ToolResult.Failure).message.contains("monobank"))

        fake.result = ScreenResult.NotEnabled
        assertTrue((UiBackTool(fake, limiter).execute(args()) as ToolResult.Failure).message.contains("керування екраном"))
    }
}
