package com.dmytro.leo.tools

import com.dmytro.leo.accessibility.Screen
import com.dmytro.leo.accessibility.ScreenResult
import com.dmytro.leo.accessibility.UiElement
import kotlinx.coroutines.delay

/**
 * Reads the forecast from the phone's own Weather app (Pixel «Погода») through the screen:
 * the home page is the current location; another city is a saved place from the list, or
 * found through the app's search (shown without saving it). Null when the app or screen
 * control isn't available, so the caller can fall back to Open-Meteo.
 */
class PixelWeather(
    private val phone: PhoneActions,
    private val screen: ScreenAgent,
    private val pollMs: Long = 500,
    private val timeoutMs: Long = 8_000,
    private val pages: Int = 3,
) {
    suspend fun forecast(city: String?): String? {
        if (!phone.openApp(PACKAGE, fresh = true)) return null
        var current = awaitScreen { isForecast(it) } ?: return null

        if (city != null && !WeatherScreen.isPlace(current.text, city)) {
            current = openPlace(city) ?: return null
        }

        // The page is a long list: scroll a few times and keep every line once
        val lines = LinkedHashSet<String>()
        lines += WeatherScreen.lines(current.text)
        repeat(pages) {
            val next = screen.scroll(null, forward = true) as? ScreenResult.Ok ?: return@repeat
            lines += WeatherScreen.lines(next.screen.text)
        }
        return lines.joinToString("\n").takeIf { it.isNotBlank() }
    }

    /** Locations list → a saved place, or search → the first match. */
    private suspend fun openPlace(city: String): Screen? {
        val home = (screen.read(detailed = true) as? ScreenResult.Ok)?.screen ?: return null
        val up = home.find { it.label == UP } ?: return null
        screen.click(up.id)
        val list = awaitScreen { screen -> screen.find { it.label.startsWith(ADD_PLACE) } != null } ?: return null

        val saved = list.elements.values.firstOrNull { WeatherScreen.isPlace(it.label, city) }
        if (saved != null) {
            screen.click(saved.id)
            return awaitScreen { isForecast(it) }
        }

        screen.click(list.find { it.label.startsWith(ADD_PLACE) }!!.id)
        val search = awaitScreen { screen -> screen.find { it.role.startsWith("поле") } != null } ?: return null
        screen.type(search.find { it.role.startsWith("поле") }!!.id, city)
        val results = awaitScreen { WeatherScreen.firstResult(it.elements.values) != null } ?: return null
        screen.click(WeatherScreen.firstResult(results.elements.values)!!.id)
        return awaitScreen { isForecast(it) }
    }

    private fun isForecast(screen: Screen) = HOURLY in screen.text || UPDATED in screen.text

    private fun Screen.find(predicate: (UiElement) -> Boolean) = elements.values.firstOrNull(predicate)

    /** The screen once [ready] holds, or null on timeout / when the screen can't be read. */
    private suspend fun awaitScreen(ready: (Screen) -> Boolean): Screen? {
        var waited = 0L
        while (waited <= timeoutMs) {
            when (val result = screen.read(detailed = true)) {
                is ScreenResult.Ok -> if (ready(result.screen)) return result.screen
                ScreenResult.NotEnabled, is ScreenResult.Blocked -> return null
                else -> Unit
            }
            delay(pollMs)
            waited += pollMs
        }
        return null
    }

    companion object {
        const val PACKAGE = "com.google.android.apps.weather"
        private const val UP = "Перейти вгору"
        private const val ADD_PLACE = "Додати бажане місцезнаходження"
        private const val HOURLY = "Щогодинний прогноз"
        private const val UPDATED = "Оновлено"
    }
}

/** Pure Kotlin, unit-tested. */
object WeatherScreen {

    private val NOISE = setOf("Перейти вгору", "Зберегти", "Призупинити анімацію", "Відновити анімацію", "Google Погода")

    /** "[3] кнопка «Ясно»" / "текст: «14°»" → "Ясно" / "14°"; app name and buttons dropped. */
    fun lines(screenText: String): List<String> = screenText.lines()
        .drop(1) // "Програма: Погода"
        .mapNotNull { line ->
            val text = Regex("«(.*)»").find(line)?.groupValues?.get(1) ?: return@mapNotNull null
            text.trim().takeIf { it.isNotEmpty() && it !in NOISE && !it.startsWith("Ви ввійшли") }
        }

    /** Whether [text] names [city]: «Кам’янець-Подільський» matches «кам'янець», «Львів» matches «Львів». */
    fun isPlace(text: String, city: String): Boolean {
        val key = normalize(city).take(5)
        return key.length >= 3 && normalize(text).contains(key)
    }

    /** First search result row: "Lviv, Львівська область" (not its «Зберегти це місце» button). */
    fun firstResult(elements: Collection<UiElement>): UiElement? =
        elements.firstOrNull { ',' in it.label && !it.label.startsWith("Зберегти") && !it.role.startsWith("поле") }

    private fun normalize(s: String) = s.lowercase().replace('’', '\'').replace('ʼ', '\'')
}
