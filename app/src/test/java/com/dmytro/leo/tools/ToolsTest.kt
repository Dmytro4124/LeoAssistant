package com.dmytro.leo.tools

import com.dmytro.leo.data.AppEntry
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolsTest {

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { put(it.first, it.second) } }

    @Test
    fun `navigation link encodes the destination and mode`() {
        assertEquals(
            "google.navigation:q=%D0%94%D1%96%D0%BC%2C%20%D0%9A%D0%B8%D1%97%D0%B2&mode=w",
            MapsLinks.navigation("Дім, Київ", "walking"),
        )
        assertTrue(MapsLinks.navigation("Київ", null).endsWith("&mode=d"))
        assertTrue(MapsLinks.navigation("Київ", "transit").contains("travelmode=transit"))
    }

    @Test
    fun `navigate_to opens Google Maps navigation`() = runTest {
        val phone = FakePhone()
        val result = NavigateTool(FakePlaces(), phone).execute(args("destination" to "Будинок культури, Кам'янець-Подільський"))

        assertTrue(result is ToolResult.Success)
        val (uri, pkg) = phone.openedUris.single()
        assertTrue(uri.startsWith("google.navigation:q="))
        assertEquals("com.google.android.apps.maps", pkg)
    }

    @Test
    fun `navigate_to uses a saved place`() = runTest {
        val places = FakePlaces()
        val phone = FakePhone()
        SavePlaceTool(places).execute(args("name" to "Дім", "address" to "вулиця Шевченка, 5"))

        NavigateTool(places, phone).execute(args("destination" to "дім"))

        assertEquals(MapsLinks.navigation("вулиця Шевченка, 5", null), phone.openedUris.single().first)
    }

    @Test
    fun `without Google Maps any map app is used`() = runTest {
        val phone = FakePhone { _, pkg -> pkg == null }
        val result = NavigateTool(FakePlaces(), phone).execute(args("destination" to "Київ"))

        assertTrue(result is ToolResult.Success)
        assertTrue(phone.openedUris.single().first.startsWith("geo:"))
    }

    @Test
    fun `open_app launches the matching app or lists similar ones`() = runTest {
        val apps = listOf(AppEntry("YouTube Music", "music.pkg"), AppEntry("Telegram", "tg.pkg"))
        val phone = FakePhone()
        val tool = OpenAppTool({ apps }, phone)

        assertTrue(tool.execute(args("name" to "ютуб музик")) is ToolResult.Success)
        assertEquals(listOf("music.pkg"), phone.openedApps)

        val missing = tool.execute(args("name" to "Instagram"))
        assertTrue(missing is ToolResult.Failure)
    }

    @Test
    fun `missing argument is a failure, not a crash`() = runTest {
        assertTrue(NavigateTool(FakePlaces(), FakePhone()).execute(JsonObject(emptyMap())) is ToolResult.Failure)
    }

    @Test
    fun `registry reports unknown tools and exceptions as failures`() = runTest {
        val broken = object : Tool {
            override val name = "broken"
            override val description = "падає"
            override val parameters = objectSchema()
            override suspend fun execute(args: JsonObject): ToolResult = error("boom")
        }
        val registry = ToolRegistry(listOf(broken))

        assertTrue(registry.execute("nope", JsonObject(emptyMap())) is ToolResult.Failure)
        assertTrue(registry.execute("broken", JsonObject(emptyMap())) is ToolResult.Failure)
        assertEquals("broken", registry.declarations.single().functionDeclarations.single().name)
    }

    @Test
    fun `saved places go into the prompt`() {
        val places = FakePlaces()
        assertEquals("", places.promptNote())
        places.save("Робота", "вулиця Соборна, 1")
        assertTrue(places.promptNote().contains("- робота: вулиця Соборна, 1"))
    }
}
