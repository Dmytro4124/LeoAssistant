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

class GoogleSearchToolTest {

    /** Returns the given screens one per read, then keeps returning the last one. */
    private class Screens(vararg val results: ScreenResult) : ScreenAgent {
        var reads = 0
        override suspend fun read(detailed: Boolean) = results[minOf(reads++, results.size - 1)]
        override suspend fun click(id: Int) = results.last()
        override suspend fun type(id: Int, text: String) = results.last()
        override suspend fun scroll(id: Int?, forward: Boolean) = results.last()
        override suspend fun back() = results.last()
        override fun element(id: Int): UiElement? = null
        override val currentPackage = GoogleSearchTool.GOOGLE_APP
    }

    private fun google(vararg lines: String) = ScreenResult.Ok(Screen("Google", (listOf(
        "Програма: Google",
        "[1] кнопка «Ви ввійшли як Дмитро»",
        "[2] поле «населення Києва»",
        "[3] вкладка «Усі»",
    ) + lines).joinToString("\n"), emptyMap()))

    private val loading = google()
    private val partial = google("текст: «Огляд від ШІ»", "текст: «Населення Києва —»")
    private val full = google("текст: «Огляд від ШІ»", "текст: «Населення Києва — 2,95 мільйона»", "[4] кнопка «Про цей результат»", "[5] кнопка", "[6] кнопка «Схожі запитання»")

    private val query = buildJsonObject { put("query", "населення Києва") }

    @Test
    fun `waits until the results stop changing and returns them without the top of the screen`() = runTest {
        val phone = FakePhone()
        val result = GoogleSearchTool(phone, Screens(loading, partial, full, full)).execute(query) as ToolResult.Success

        assertEquals(listOf("населення Києва"), phone.searches)
        assertTrue(result.message.contains("2,95 мільйона"))
        assertFalse("account and tabs are left out", result.message.contains("Дмитро") || result.message.contains("Усі"))
        assertFalse(result.message.contains("Про цей результат") || result.message.contains("[5]"))
        assertTrue("labelled buttons stay", result.message.contains("[6] кнопка «Схожі запитання»"))
    }

    @Test
    fun `results of an earlier search are not taken for the new ones`() {
        val old = (full as ScreenResult.Ok).screen.text
        assertNull(GoogleResults.extract(old, "погода у Львові"))
        assertTrue(GoogleResults.extract(old, "Населення КИЄВА")!!.startsWith("текст: «Огляд від ШІ»"))
    }

    @Test
    fun `without screen control the results are just shown`() = runTest {
        val result = GoogleSearchTool(FakePhone(), Screens(ScreenResult.NotEnabled)).execute(query) as ToolResult.Success
        assertTrue(result.message.contains("керування екраном вимкнене"))
    }

    @Test
    fun `results that never load are reported`() = runTest {
        assertTrue(GoogleSearchTool(FakePhone(), Screens(loading)).execute(query) is ToolResult.Failure)
    }
}
