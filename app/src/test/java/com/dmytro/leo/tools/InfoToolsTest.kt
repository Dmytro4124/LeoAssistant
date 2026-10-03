package com.dmytro.leo.tools

import com.dmytro.leo.data.ExchangeRates
import com.dmytro.leo.data.HttpGet
import com.dmytro.leo.data.News
import com.dmytro.leo.data.Weather
import com.dmytro.leo.data.Wikipedia
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
    private val nbu = """[
        { "r030":36,"txt":"Австралійський долар","rate":31.2322,"cc":"AUD","exchangedate":"05.10.2026","special":null },
        { "r030":840,"txt":"Долар США","rate":41.4567,"cc":"USD","exchangedate":"05.10.2026","special":null },
        { "r030":978,"txt":"Євро","rate":48.1,"cc":"EUR","exchangedate":"05.10.2026","special":null }
    ]"""
    private val rss = """<rss><channel><title>Google Новини</title>
        <item><title>Нова дорога до фортеці - Суспільне</title><link>x</link></item>
        <item><title>&quot;Динамо&quot; перемогло &amp; вийшло далі - Спорт</title></item>
    </channel></rss>"""

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
    fun `exchange rates`() {
        assertEquals(
            "Офіційний курс НБУ на 05.10.2026: 1 USD (Долар США) = 41.46 грн; 1 EUR (Євро) = 48.10 грн",
            ExchangeRates.describe(nbu, emptyList()),
        )
        assertTrue(ExchangeRates.describe(nbu, listOf("aud")).contains("31.23"))
        assertTrue(ExchangeRates.describe(nbu, listOf("XYZ")).startsWith("НБУ не встановлює"))
    }

    @Test
    fun `news headlines from RSS`() {
        assertEquals("- Нова дорога до фортеці - Суспільне\n- \"Динамо\" перемогло & вийшло далі - Спорт", News.describe(rss))
        assertTrue(News.url("Кам'янець").contains("search?"))
    }

    @Test
    fun `wikipedia search and summary`() {
        assertEquals("Говерла", Wikipedia.parseKey("""{"pages":[{"id":1,"key":"Говерла","title":"Говерла"}]}"""))
        assertEquals(null, Wikipedia.parseKey("""{"pages":[]}"""))
        assertEquals(
            "Говерла: Найвища вершина України.",
            Wikipedia.parseSummary("""{"title":"Говерла","extract":"Найвища вершина України."}"""),
        )
    }

    @Test
    fun `weather tool chains geocoding and forecast, offline is explained`() = runTest {
        val urls = mutableListOf<String>()
        val http = HttpGet { url -> urls += url; if ("geocoding" in url) geocoding else forecast }
        val result = WeatherTool(http).execute(buildJsonObject { put("city", "Кам'янець-Подільський") })

        assertTrue(result is ToolResult.Success)
        assertTrue(urls[1].contains("latitude=48.67882"))

        val offline = WeatherTool { throw IOException() }.execute(buildJsonObject { put("city", "Київ") })
        assertTrue((offline as ToolResult.Failure).message.contains("немає зв'язку"))
    }
}
