package com.dmytro.leo.data

import android.content.Context
import androidx.core.content.edit

/** The user's own playlists by name: "музика" → YouTube playlist id. */
interface PlaylistStore {
    fun all(): Map<String, String>
    fun save(name: String, playlistId: String)
    fun remove(name: String)

    fun find(name: String): String? = all()[normalizeName(name)]

    /** Saved playlist names for the system prompt, or "" if there are none. */
    fun promptNote(): String {
        val names = all().keys
        if (names.isEmpty()) return ""
        return "Збережені плейлисти користувача (вмикай через play_music з type=playlist): ${names.joinToString()}"
    }

    companion object {
        fun normalizeName(name: String) = name.trim().trim('«', '»', '"').lowercase()

        /**
         * The playlist id from a shared link ("https://music.youtube.com/playlist?list=PL…&si=…")
         * or a bare id. Null if there is none.
         */
        fun playlistIdFrom(linkOrId: String): String? {
            val text = linkOrId.trim()
            Regex("[?&]list=([A-Za-z0-9_-]+)").find(text)?.let { return it.groupValues[1] }
            return text.removePrefix("VL").takeIf { Regex("(PL|OLAK5uy_|RD|LM)[A-Za-z0-9_-]*").matches(it) }
        }
    }
}

class AndroidPlaylistStore(context: Context) : PlaylistStore {

    private val prefs = context.applicationContext.getSharedPreferences("playlists", Context.MODE_PRIVATE)

    override fun all(): Map<String, String> =
        prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap().toSortedMap()

    override fun remove(name: String) {
        prefs.edit { remove(name) }
    }

    override fun save(name: String, playlistId: String) {
        prefs.edit { putString(PlaylistStore.normalizeName(name), playlistId) }
    }
}
