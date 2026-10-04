package com.dmytro.leo.data

import com.dmytro.leo.brain.GeminiJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Locale
import kotlin.math.roundToInt

/** Plain HTTP GET; tests use a fake. Throws IOException without network. */
fun interface HttpGet {
    suspend fun get(url: String): String
}

class OkHttpGet(private val client: OkHttpClient) : HttpGet {
    override suspend fun get(url: String): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("User-Agent", "LeoVoiceAssistant/1.0 (Android)").build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code}")
            response.body.string()
        }
    }
}

internal fun encode(text: String): String = URLEncoder.encode(text, "UTF-8").replace("+", "%20")

private val UKRAINIAN = Locale.forLanguageTag("uk-UA")

// ---------------- Weather: Open-Meteo (free, no key) ----------------

data class Place(val name: String, val region: String?, val latitude: Double, val longitude: Double)

object Weather {

    /** Open-Meteo knows "Кам’янець" only with the typographic apostrophe. */
    fun geocodingUrl(city: String): String =
        "https://geocoding-api.open-meteo.com/v1/search?count=1&language=uk&name=" +
            encode(city.trim().replace('\'', '’').replace('ʼ', '’'))

    fun parsePlace(json: String): Place? {
        val result = (GeminiJson.parseToJsonElement(json).jsonObject["results"] as? JsonArray)?.firstOrNull()?.jsonObject
            ?: return null
        return Place(
            name = result["name"]!!.jsonPrimitive.content,
            region = result["admin1"]?.jsonPrimitive?.contentOrNull,
            latitude = result["latitude"]!!.jsonPrimitive.double,
            longitude = result["longitude"]!!.jsonPrimitive.double,
        )
    }

    fun forecastUrl(place: Place): String =
        "https://api.open-meteo.com/v1/forecast?latitude=${place.latitude}&longitude=${place.longitude}" +
            "&current=temperature_2m,weather_code,wind_speed_10m" +
            "&daily=weather_code,temperature_2m_max,temperature_2m_min,precipitation_probability_max" +
            "&timezone=auto&forecast_days=7&wind_speed_unit=ms"

    /** A short text for the model: now + the next days, with Ukrainian weekday names. */
    fun describe(place: Place, json: String): String {
        val root = GeminiJson.parseToJsonElement(json).jsonObject
        val current = root["current"]?.jsonObject
        val daily = root["daily"]!!.jsonObject
        val days = daily["time"]!!.jsonArray.map { LocalDate.parse(it.jsonPrimitive.content) }

        return buildString {
            append(place.name)
            place.region?.let { append(" ($it)") }
            append(".\n")
            current?.let {
                val temp = it["temperature_2m"]!!.jsonPrimitive.double.roundToInt()
                val code = it["weather_code"]!!.jsonPrimitive.int
                val wind = it["wind_speed_10m"]?.jsonPrimitive?.doubleOrNull?.roundToInt()
                append("Зараз: $temp°, ${codeText(code)}")
                wind?.let { w -> append(", вітер $w м/с") }
                append(".\n")
            }
            days.forEachIndexed { i, day ->
                val max = daily["temperature_2m_max"]!!.jsonArray[i].jsonPrimitive.double.roundToInt()
                val min = daily["temperature_2m_min"]!!.jsonArray[i].jsonPrimitive.double.roundToInt()
                val code = daily["weather_code"]!!.jsonArray[i].jsonPrimitive.int
                val rain = daily["precipitation_probability_max"]?.jsonArray?.getOrNull(i)?.jsonPrimitive?.intOrNull
                append("${weekday(day)} ${day.format(DAY_FORMAT)}: від $min° до $max°, ${codeText(code)}")
                rain?.let { append(", ймовірність опадів $it%") }
                append(".\n")
            }
        }.trim()
    }

    private val DAY_FORMAT = DateTimeFormatter.ofPattern("d.MM")

    private fun weekday(date: LocalDate) = date.dayOfWeek.getDisplayName(TextStyle.FULL, UKRAINIAN)

    /** WMO weather codes used by Open-Meteo. */
    fun codeText(code: Int): String = when (code) {
        0 -> "ясно"
        1 -> "переважно ясно"
        2 -> "мінлива хмарність"
        3 -> "хмарно"
        45, 48 -> "туман"
        51, 53, 55 -> "мряка"
        56, 57 -> "крижана мряка"
        61 -> "невеликий дощ"
        63 -> "дощ"
        65 -> "сильний дощ"
        66, 67 -> "крижаний дощ"
        71 -> "невеликий сніг"
        73 -> "сніг"
        75 -> "сильний сніг"
        77 -> "снігова крупа"
        80, 81 -> "зливи"
        82 -> "сильні зливи"
        85, 86 -> "снігопад"
        95 -> "гроза"
        96, 99 -> "гроза з градом"
        else -> "погода невідома"
    }
}
