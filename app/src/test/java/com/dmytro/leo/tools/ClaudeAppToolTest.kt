package com.dmytro.leo.tools

import com.dmytro.leo.accessibility.Screen
import com.dmytro.leo.accessibility.ScreenResult
import com.dmytro.leo.accessibility.UiElement
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ClaudeAppToolTest {

    private fun el(id: Int, role: String, label: String) = UiElement(id, role, label, password = false, path = listOf(id))

    /** A Claude screen from elements (null element = plain text line). */
    private fun claude(vararg items: Pair<UiElement?, String>): ScreenResult.Ok {
        val lines = listOf("Програма: Claude") + items.map { (e, text) -> if (e != null) "[${e.id}] ${e.role} «${e.label}»" else "текст: «$text»" }
        return ScreenResult.Ok(Screen("Claude", lines.joinToString("\n"), items.mapNotNull { it.first }.associateBy { it.id }))
    }

    private val question = "Що таке чорна діра?"
    private val composed = claude(el(1, "кнопка", "Open menu") to "", el(2, "поле", question) to "", el(3, "кнопка", "Send") to "")
    private val streaming = claude(null to question, null to "Чорна діра —", el(4, "кнопка", "Stop response") to "")
    private val finished = claude(
        null to question,
        null to "Чорна діра — це область простору, з якої ніщо не може вирватися.",
        null to "Навіть світло.",
        el(5, "кнопка", "Copy message") to "",
        el(6, "кнопка", "Retry message") to "",
        null to "Claude is AI and can make mistakes.",
        el(7, "поле", "Reply to Claude…") to "",
    )

    private inner class FakeClaude : ScreenAgent {
        val screens = ArrayDeque(listOf<ScreenResult>(composed))
        val clicks = mutableListOf<Int>()
        val typed = mutableListOf<String>()
        override suspend fun read(detailed: Boolean) = if (screens.size > 1) screens.removeFirst() else screens.first()
        override suspend fun click(id: Int): ScreenResult {
            clicks += id
            if (id == 3) { screens.clear(); screens += listOf(streaming, streaming, finished) }
            return screens.first()
        }
        override suspend fun type(id: Int, text: String): ScreenResult { typed += text; return screens.first() }
        override suspend fun scroll(id: Int?, forward: Boolean) = screens.first()
        override suspend fun back() = screens.first()
        override fun element(id: Int): UiElement? = null
        override val currentPackage = ClaudeAppTool.PACKAGE
    }

    @Test
    fun `a new chat is opened with the question, sent, and the finished answer read back`() = runTest {
        val phone = FakePhone()
        val app = FakeClaude()
        val result = ClaudeAppTool(phone, app, pollMs = 10).execute(buildJsonObject { put("question", question) }) as ToolResult.Success

        assertEquals(ClaudeAppTool.PACKAGE, phone.openedUris.single().second)
        assertTrue(phone.openedUris.single().first.startsWith("https://claude.ai/new?q=%D0%A9%D0%BE%20"))
        assertEquals(listOf(3), app.clicks)
        assertTrue(result.message.contains("ніщо не може вирватися.\nНавіть світло."))
        assertFalse("app buttons are not part of the answer", result.message.contains("Copy message") || result.message.contains("can make mistakes"))
    }

    @Test
    fun `the answer is complete only up to its Copy button`() {
        val text = (finished as ScreenResult.Ok).screen.text
        assertEquals("Чорна діра — це область простору, з якої ніщо не може вирватися.\nНавіть світло.", ClaudeScreen.answer(text, question))
        assertNull("still streaming", ClaudeScreen.answer((streaming as ScreenResult.Ok).screen.text, question))
        assertFalse(ClaudeScreen.isFinished(streaming.screen, question))
        assertTrue(ClaudeScreen.isFinished(finished.screen, question))
        // An older answer's Copy button doesn't count while this question has no answer yet
        val asked = claude(null to "Старе питання", null to "Стара відповідь", el(5, "кнопка", "Copy message") to "", null to question)
        assertFalse(ClaudeScreen.isFinished(asked.screen, question))
    }

    @Test
    fun `a follow-up is typed into the open chat`() = runTest {
        val app = FakeClaude()
        app.screens.clear()
        app.screens += claude(null to "Старе питання", null to "Стара відповідь", el(5, "кнопка", "Copy message") to "", el(2, "поле", "Reply to Claude…") to "", el(3, "кнопка", "Send") to "")
        val phone = FakePhone()
        ClaudeAppTool(phone, app, pollMs = 10).execute(buildJsonObject { put("question", question); put("follow_up", "true") })

        assertEquals(listOf(question), app.typed)
        assertTrue("no new chat", phone.openedUris.isEmpty())
        assertEquals("back to the open chat", listOf(ClaudeAppTool.PACKAGE), phone.openedApps)
    }

    @Test
    fun `texts match even when the field shortens them`() {
        assertTrue(ClaudeScreen.sameText("Розкажи про історію Кам'янця…", "Розкажи про історію Кам'янця-Подільського"))
        assertFalse(ClaudeScreen.sameText("Привіт", "Привіт, як справи?"))
    }
}
