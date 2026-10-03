package com.dmytro.leo.tools

import com.dmytro.leo.data.PlacesStore
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

private const val GOOGLE_MAPS = "com.google.android.apps.maps"

/** Links understood by Google Maps. Pure functions, unit-tested. */
object MapsLinks {

    /** Turn-by-turn navigation. Transit has no navigation mode, so it opens the route instead. */
    fun navigation(destination: String, mode: String?): String = when (mode) {
        "transit" -> "https://www.google.com/maps/dir/?api=1&destination=${encode(destination)}&travelmode=transit"
        else -> "google.navigation:q=${encode(destination)}&mode=${MODES[mode] ?: "d"}"
    }

    fun search(query: String): String = "geo:0,0?q=${encode(query)}"

    private val MODES = mapOf("driving" to "d", "walking" to "w", "bicycling" to "b", "two_wheeler" to "l")

    private fun encode(text: String) = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
}

class NavigateTool(
    private val places: PlacesStore,
    private val phone: PhoneActions,
) : Tool {

    override val name = "navigate_to"
    override val description =
        "Побудувати маршрут і запустити навігацію в Google Maps. " +
            "Якщо користувач називає місто, додай його до адреси."
    override val parameters = objectSchema(
        Param("destination", "Куди їхати: адреса або назва місця з містом, напр. «Будинок культури, Кам'янець-Подільський», або назва збереженого місця"),
        Param(
            "mode", "Спосіб пересування; за замовчуванням driving",
            required = false, enum = listOf("driving", "walking", "bicycling", "transit", "two_wheeler"),
        ),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val asked = args.string("destination") ?: return ToolResult.Failure("Не вказано, куди їхати")
        // "дім" → the saved address, if there is one
        val destination = places.find(asked) ?: asked
        val link = MapsLinks.navigation(destination, args.string("mode"))

        return when {
            phone.openUri(link, GOOGLE_MAPS) -> ToolResult.Success("Навігацію до «$destination» запущено", endsConversation = true)
            // No Google Maps: let any map app show the place
            phone.openUri(MapsLinks.search(destination)) -> ToolResult.Success("Google Maps немає, місце відкрито в іншій карті", endsConversation = true)
            else -> ToolResult.Failure("На телефоні немає додатка карт")
        }
    }
}

class SearchPlaceTool(private val phone: PhoneActions) : Tool {

    override val name = "search_place"
    override val description = "Показати місце або знайти заклади на карті без навігації: «де найближча аптека», «покажи фортецю на карті»."
    override val parameters = objectSchema(
        Param("query", "Що шукати, з містом, якщо воно відоме: «аптека», «Кам'янець-Подільська фортеця»"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val query = args.string("query") ?: return ToolResult.Failure("Не вказано, що шукати")
        val link = MapsLinks.search(query)
        return if (phone.openUri(link, GOOGLE_MAPS) || phone.openUri(link)) {
            ToolResult.Success("Карту з «$query» відкрито")
        } else {
            ToolResult.Failure("На телефоні немає додатка карт")
        }
    }
}

class SavePlaceTool(private val places: PlacesStore) : Tool {

    override val name = "save_place"
    override val description =
        "Запам'ятати адресу улюбленого місця, щоб потім будувати туди маршрут за назвою: " +
            "«запам'ятай, що мій дім — вулиця Шевченка, 5»."
    override val parameters = objectSchema(
        Param("name", "Коротка назва в називному відмінку: дім, робота, дача, батьки"),
        Param("address", "Повна адреса з містом"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val placeName = args.string("name") ?: return ToolResult.Failure("Не вказано назву місця")
        val address = args.string("address") ?: return ToolResult.Failure("Не вказано адресу")
        places.save(placeName, address)
        return ToolResult.Success("Запам'ятав: $placeName — $address")
    }
}
