package com.dmytro.leo.tools

import com.dmytro.leo.data.ExchangeRates
import com.dmytro.leo.data.HttpGet
import com.dmytro.leo.data.News
import com.dmytro.leo.data.Weather
import com.dmytro.leo.data.Wikipedia
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

class WeatherTool(private val http: HttpGet) : Tool {
    override val name = "get_weather"
    override val description =
        "Погода зараз і прогноз на 7 днів для міста. Якщо місто не назване — візьми місто з адреси «дім», " +
            "а якщо її немає, спитай. Перекажи коротко лише те, що питали (напр. лише завтра)."
    override val parameters = objectSchema(Param("city", "Місто українською: «Кам'янець-Подільський», «Київ»"))

    override suspend fun execute(args: JsonObject): ToolResult {
        val city = args.string("city") ?: return ToolResult.Failure("Не вказано місто")
        return online("Open-Meteo") {
            val place = Weather.parsePlace(http.get(Weather.geocodingUrl(city)))
                ?: return@online ToolResult.Failure("Місто «$city» не знайдено")
            ToolResult.Success(Weather.describe(place, http.get(Weather.forecastUrl(place))))
        }
    }
}

class ExchangeRateTool(private val http: HttpGet) : Tool {
    override val name = "get_exchange_rate"
    override val description = "Офіційний курс гривні від НБУ на сьогодні."
    override val parameters = objectSchema(
        Param("currencies", "Коди валют через кому: «USD», «EUR,PLN». За замовчуванням USD і EUR", required = false),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val codes = args.string("currencies")?.split(',', ' ')?.map { it.trim() }?.filter { it.length == 3 }.orEmpty()
        return online("НБУ") { ToolResult.Success(ExchangeRates.describe(http.get(ExchangeRates.URL), codes)) }
    }
}

class NewsTool(private val http: HttpGet) : Tool {
    override val name = "get_news"
    override val description =
        "Свіжі заголовки новин (Google Новини, українською): головні або на тему. " +
            "Перекажи 2–3 найважливіші коротко, без переліку джерел."
    override val parameters = objectSchema(Param("topic", "Тема, напр. «Кам'янець-Подільський», «футбол». Без теми — головні новини", required = false))

    override suspend fun execute(args: JsonObject): ToolResult =
        online("Google Новини") { ToolResult.Success(News.describe(http.get(News.url(args.string("topic"))))) }
}

class WikipediaTool(private val http: HttpGet) : Tool {
    override val name = "wikipedia"
    override val description =
        "Коротка довідка з української Вікіпедії: хто така людина, що таке явище, історія місця. " +
            "Для свіжих подій використовуй get_news."
    override val parameters = objectSchema(Param("query", "Про що: «Говерла», «Леся Українка»"))

    override suspend fun execute(args: JsonObject): ToolResult {
        val query = args.string("query") ?: return ToolResult.Failure("Не вказано, що шукати")
        return online("Вікіпедія") {
            val key = Wikipedia.parseKey(http.get(Wikipedia.searchUrl(query)))
                ?: return@online ToolResult.Failure("У Вікіпедії нічого не знайдено про «$query»")
            Wikipedia.parseSummary(http.get(Wikipedia.summaryUrl(key)))?.let { ToolResult.Success(it) }
                ?: ToolResult.Failure("У статті «$key» немає короткого опису")
        }
    }
}
