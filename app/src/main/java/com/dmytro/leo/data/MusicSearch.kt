package com.dmytro.leo.data

import com.dmytro.leo.brain.GeminiJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

enum class MusicType { Song, Artist, Album, Playlist }

/** One search result. Songs have [videoId]; artists, albums and playlists have [playlistId]. */
data class MusicItem(
    val title: String,
    val subtitle: String,
    val videoId: String? = null,
    val playlistId: String? = null,
)

fun interface MusicSearch {
    /** Best results first; empty if nothing found. Throws IOException without network. */
    suspend fun search(query: String, type: MusicType): List<MusicItem>
}

/**
 * Search through the internal API of music.youtube.com (the one the website uses).
 * Unofficial: no key and no quota, but it may change without notice.
 */
class YouTubeMusicSearch(private val httpClient: OkHttpClient) : MusicSearch {

    override suspend fun search(query: String, type: MusicType): List<MusicItem> = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            putJsonObject("context") {
                putJsonObject("client") {
                    put("clientName", "WEB_REMIX")
                    put("clientVersion", "1.20250101.01.00")
                    put("hl", "uk")
                    put("gl", "UA")
                }
            }
            put("query", query)
            put("params", FILTERS.getValue(type))
        }
        val request = Request.Builder()
            .url("https://music.youtube.com/youtubei/v1/search?prettyPrint=false")
            .header("User-Agent", "Mozilla/5.0")
            .header("Origin", "https://music.youtube.com")
            .post(body.toString().toRequestBody("application/json".toMediaType()))
            .build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("YouTube Music: HTTP ${response.code}")
            YouTubeMusicParser.parse(response.body.string())
        }
    }

    private companion object {
        // Result-type filters of the YouTube Music search page
        val FILTERS = mapOf(
            MusicType.Song to "EgWKAQIIAWoKEAkQBRAKEAMQBA%3D%3D",
            MusicType.Album to "EgWKAQIYAWoKEAkQChAFEAMQBA%3D%3D",
            MusicType.Artist to "EgWKAQIgAWoKEAkQChAFEAMQBA%3D%3D",
            MusicType.Playlist to "EgeKAQQoAEABagoQAxAEEAoQCRAF",
        )
    }
}

/** Pulls results out of the deeply nested search response. Pure, unit-tested. */
object YouTubeMusicParser {

    fun parse(json: String): List<MusicItem> {
        val root = try {
            GeminiJson.parseToJsonElement(json)
        } catch (e: IllegalArgumentException) {
            return emptyList()
        }
        return findAll(root, "musicResponsiveListItemRenderer").mapNotNull { item(it) }
    }

    private fun item(renderer: JsonObject): MusicItem? {
        val columns = renderer["flexColumns"] as? JsonArray ?: return null
        val title = columnText(columns, 0)
        if (title.isEmpty()) return null
        return MusicItem(
            title = title,
            subtitle = columnText(columns, 1),
            videoId = (renderer["playlistItemData"] as? JsonObject)?.string("videoId")
                ?: firstString(renderer, "videoId"),
            playlistId = findAll(renderer, "watchPlaylistEndpoint").firstNotNullOfOrNull { it.string("playlistId") }
                ?: firstString(renderer, "playlistId"),
        )
    }

    private fun columnText(columns: JsonArray, index: Int): String =
        (columns.getOrNull(index) as? JsonObject)
            ?.get("musicResponsiveListItemFlexColumnRenderer")?.jsonObject
            ?.get("text")?.jsonObject
            ?.get("runs")?.jsonArray
            ?.joinToString("") { it.jsonObject.string("text").orEmpty() }
            ?.trim()
            .orEmpty()

    /** Every object stored under [key], anywhere in the tree, in document order. */
    private fun findAll(element: JsonElement, key: String): List<JsonObject> = buildList {
        fun walk(e: JsonElement) {
            when (e) {
                is JsonObject -> e.forEach { (k, v) ->
                    if (k == key && v is JsonObject) add(v)
                    walk(v)
                }
                is JsonArray -> e.forEach(::walk)
                else -> Unit
            }
        }
        walk(element)
    }

    private fun firstString(element: JsonElement, key: String): String? {
        when (element) {
            is JsonObject -> {
                (element[key] as? JsonPrimitive)?.contentOrNull?.let { return it }
                element.values.forEach { child -> firstString(child, key)?.let { return it } }
            }
            is JsonArray -> element.forEach { child -> firstString(child, key)?.let { return it } }
            else -> Unit
        }
        return null
    }

    private fun JsonObject.string(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
}
