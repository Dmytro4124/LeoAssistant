package com.dmytro.leo.tools

import com.dmytro.leo.data.HttpGet
import com.dmytro.leo.data.Weather
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.JsonObject
import java.io.IOException

// Internet answers from free open sources. Google Search grounding is not in the free tier.

/** Runs a fetch; network and format problems become a Failure for the model to explain. */
private suspend fun online(source: String, block: suspend () -> ToolResult): ToolResult = try {
    block()
} catch (e: IOException) {
    ToolResult.Failure("Не вдалося отримати дані ($source): немає зв'язку")
} catch (e: SerializationException) {
    ToolResult.Failure("$source повернув незрозумілу відповідь")
} catch (e: IllegalArgumentException) {
    ToolResult.Failure("$source повернув незрозумілу відповідь")
}

/**
 * Weather from the phone's own Weather app ([app], read from its screen), or from Open-Meteo
 * when the app or screen control isn't available.
 */
class WeatherTool(private val http: HttpGet, private val app: PixelWeather? = null) : Tool {
    override val name = "get_weather"
    override val description =
        "Погода зараз, по годинах і на найближчі дні — з додатка «Погода» на телефоні. " +
            "Без міста — для поточного місцезнаходження. Перекажи коротко лише те, що питали (напр. лише завтра). " +
            "Додаток лишається відкритим: користувач бачить прогноз на екрані."
    override val parameters = objectSchema(
        Param("city", "Місто українською, якщо назване: «Кам'янець-Подільський», «Київ». Без міста — де користувач зараз", required = false),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val requested = args.string("city")
        app?.forecast(requested)?.let { screen ->
            return ToolResult.Success("З додатка «Погода» (${requested ?: "поточне місцезнаходження"}):\n$screen")
        }
        val city = requested ?: return ToolResult.Failure(
            "Додаток «Погода» недоступний (немає або вимкнене керування екраном), а місто не вказане: спитай місто"
        )
        return online("Open-Meteo") {
            val place = Weather.parsePlace(http.get(Weather.geocodingUrl(city)))
                ?: return@online ToolResult.Failure("Місто «$city» не знайдено")
            ToolResult.Success(Weather.describe(place, http.get(Weather.forecastUrl(place))))
        }
    }
}
