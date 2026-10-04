package com.dmytro.leo.tools

import com.dmytro.leo.accessibility.ScreenResult
import com.dmytro.leo.accessibility.Snapshot
import com.dmytro.leo.accessibility.UiElement
import com.dmytro.leo.brain.FakeGemini
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LookAtScreenToolTest {

    private class Shooter(private val shot: Snapshot) : ScreenAgent {
        override suspend fun read(detailed: Boolean) = ScreenResult.NoWindow
        override suspend fun click(id: Int) = ScreenResult.NoWindow
        override suspend fun type(id: Int, text: String) = ScreenResult.NoWindow
        override suspend fun scroll(id: Int?, forward: Boolean) = ScreenResult.NoWindow
        override suspend fun back() = ScreenResult.NoWindow
        override suspend fun screenshot() = shot
        override fun element(id: Int): UiElement? = null
        override val currentPackage: String? = null
    }

    @Test
    fun `the screenshot and the question go to Gemini, its answer comes back`() = runTest {
        val gemini = FakeGemini(FakeGemini.answer("Це Софійський собор у Києві."))
        val result = LookAtScreenTool(Shooter(Snapshot.Image(byteArrayOf(1, 2, 3))), gemini)
            .execute(buildJsonObject { put("question", "що це за будівля") }) as ToolResult.Success

        assertEquals("На знімку екрана: Це Софійський собор у Києві.", result.message)
        val parts = gemini.requests.single().contents.single().parts
        assertTrue(parts[0].text!!.contains("«що це за будівля»"))
        assertEquals("image/jpeg", parts[1].inlineData!!.mimeType)
        assertEquals("AQID", parts[1].inlineData!!.data)
    }

    @Test
    fun `no screenshot of a bank, and none without screen control`() = runTest {
        val gemini = FakeGemini()
        val bank = LookAtScreenTool(Shooter(Snapshot.Blocked("monobank")), gemini).execute(buildJsonObject { })
        assertTrue((bank as ToolResult.Failure).message.contains("monobank"))
        val off = LookAtScreenTool(Shooter(Snapshot.NotEnabled), gemini).execute(buildJsonObject { })
        assertTrue((off as ToolResult.Failure).message.contains("керування екраном"))
        assertTrue("nothing was sent", gemini.requests.isEmpty())
    }
}
