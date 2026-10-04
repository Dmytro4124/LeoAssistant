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

class WeatherAppTest {

    private fun el(id: Int, role: String, label: String) = UiElement(id, role, label, password = false, path = listOf(id))

    private fun screen(vararg items: Pair<UiElement?, String>): ScreenResult.Ok {
        val lines = listOf("Програма: Погода") + items.map { (e, text) -> if (e != null) "[${e.id}] ${e.role} «${e.label}»" else "текст: «$text»" }
        return ScreenResult.Ok(Screen("Погода", lines.joinToString("\n"), items.mapNotNull { it.first }.associateBy { it.id }))
    }

    private val up = el(1, "кнопка", "Перейти вгору")

    private fun forecast(place: String, temp: String) = screen(
        up to "", null to place, null to "Оновлено 10:33", null to "Ясно", null to temp, null to "Щогодинний прогноз",
    )

    private val home = forecast("Поточне місцезнаходження", "14°")
    private val homeScrolled = screen(up to "", null to "Вологість", null to "59%")
    private val places = screen(
        el(2, "елемент", "Поточне місцезнаходження Ясно 14°") to "",
        el(3, "елемент", "Кам'янець-Подільський Ясно 12°") to "",
        el(4, "кнопка", "Додати бажане місцезнаходження") to "",
    )
    private val search = screen(el(5, "кнопка", "Назад") to "", el(6, "поле", "Пошук місцезнаходження") to "")
    private val results = screen(
        el(6, "поле", "Lviv") to "",
        el(7, "елемент", "Lviv, Львівська область") to "",
        el(8, "кнопка", "Зберегти це місце") to "",
    )

    /** The Weather app as a little state machine: clicks and typing move between its screens. */
    private inner class FakeWeather : ScreenAgent {
        var current: ScreenResult = home
        val typed = mutableListOf<String>()
        override suspend fun read(detailed: Boolean) = current
        override suspend fun click(id: Int): ScreenResult {
            current = when (id) {
                1 -> places
                3 -> forecast("Кам'янець-Подільський", "12°")
                4 -> search
                7 -> forecast("Lviv", "15°")
                else -> current
            }
            return current
        }
        override suspend fun type(id: Int, text: String): ScreenResult { typed += text; current = results; return current }
        override suspend fun scroll(id: Int?, forward: Boolean) = homeScrolled
        override suspend fun back() = current
        override fun element(id: Int): UiElement? = null
        override val currentPackage = PixelWeather.PACKAGE
    }

    @Test
    fun `current location is the home page, scrolled for more`() = runTest {
        val phone = FakePhone()
        val text = PixelWeather(phone, FakeWeather(), pages = 1).forecast(null)!!

        assertEquals(listOf(PixelWeather.PACKAGE), phone.openedApps)
        assertTrue(text.contains("14°") && text.contains("Вологість 59%".substringBefore(" ")))
        assertFalse("buttons are dropped", text.contains("Перейти вгору"))
    }

    @Test
    fun `a saved place is opened from the list`() = runTest {
        val text = PixelWeather(FakePhone(), FakeWeather(), pages = 0).forecast("Кам’янець-Подільський")!!
        assertTrue(text.contains("Кам'янець-Подільський") && text.contains("12°"))
    }

    @Test
    fun `another city is found through the app's search`() = runTest {
        val app = FakeWeather()
        val text = PixelWeather(FakePhone(), app, pages = 0).forecast("Львів")!!
        assertEquals(listOf("Львів"), app.typed)
        assertTrue(text.contains("Lviv") && text.contains("15°"))
    }

    @Test
    fun `without screen control the tool falls back to Open-Meteo`() = runTest {
        val blind = object : ScreenAgent by FakeWeather() {
            override suspend fun read(detailed: Boolean) = ScreenResult.NotEnabled
        }
        assertNull(PixelWeather(FakePhone(), blind, timeoutMs = 0).forecast(null))

        val tool = WeatherTool({ throw java.io.IOException() }, PixelWeather(FakePhone(), blind, timeoutMs = 0))
        val noCity = tool.execute(buildJsonObject { }) as ToolResult.Failure
        assertTrue(noCity.message.contains("спитай місто"))
        val offline = tool.execute(buildJsonObject { put("city", "Київ") }) as ToolResult.Failure
        assertTrue(offline.message.contains("немає зв'язку"))
    }
}
