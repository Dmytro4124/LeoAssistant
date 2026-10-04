package com.dmytro.leo.tools

import com.dmytro.leo.data.HttpGet
import com.dmytro.leo.data.Weather
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class InfoToolsTest {

    // Shapes copied from real responses (trimmed)
    private val geocoding = """{"results":[{"id":707753,"name":"Кам’янець-Подільський","latitude":48.67882,"longitude":26.58516,"country":"Україна","admin1":"Хмельницька область"}]}"""
    private val forecast = """
        {"current":{"time":"2026-10-03T22:45","interval":900,"temperature_2m":12.2,"weather_code":0,"wind_speed_10m":0.8},
         "daily":{"time":["2026-10-03","2026-10-04"],"weather_code":[1,61],"temperature_2m_max":[20.5,22.5],
                  "temperature_2m_min":[5.0,4.6],"precipitation_probability_max":[0,70]}}
    """.trimIndent()

    @Test
    fun `weather is described for the model in Ukrainian`() {
        val place = Weather.parsePlace(geocoding)!!
        val text = Weather.describe(place, forecast)

        assertTrue(text, text.startsWith("Кам’янець-Подільський (Хмельницька область)."))
        assertTrue(text, text.contains("Зараз: 12°, ясно, вітер 1 м/с."))
        assertTrue(text, text.contains("неділя 4.10: від 5° до 23°, невеликий дощ, ймовірність опадів 70%."))
    }

    @Test
    fun `city names get the apostrophe Open-Meteo knows`() {
        assertTrue(Weather.geocodingUrl("Кам'янець-Подільський").contains("%E2%80%99"))
    }
    @Test
    fun `weather tool chains geocoding and forecast, offline is explained`() = runTest {
        val urls = mutableListOf<String>()
        val http = HttpGet { url -> urls += url; if ("geocoding" in url) geocoding else forecast }
        val result = WeatherTool(http).execute(buildJsonObject { put("city", "Кам'янець-Подільський") })

        assertTrue(result is ToolResult.Success)
        assertTrue(urls[1].contains("latitude=48.67882"))

        val offline = WeatherTool({ throw IOException() }).execute(buildJsonObject { put("city", "Київ") })
        assertTrue((offline as ToolResult.Failure).message.contains("немає зв'язку"))
    }
}
