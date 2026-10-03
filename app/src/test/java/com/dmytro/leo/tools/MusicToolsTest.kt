package com.dmytro.leo.tools

import com.dmytro.leo.data.AppEntry
import com.dmytro.leo.data.MusicItem
import com.dmytro.leo.data.MusicSearch
import com.dmytro.leo.data.MusicType
import com.dmytro.leo.data.PlaylistStore
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class MusicToolsTest {

    private class FakePlaylists : PlaylistStore {
        private val map = sortedMapOf<String, String>()
        override fun all(): Map<String, String> = map
        override fun remove(name: String) {
            map.remove(name)
        }

        override fun save(name: String, playlistId: String) {
            map[PlaylistStore.normalizeName(name)] = playlistId
        }
    }

    private class FakeMedia : MediaActions {
        val keys = mutableListOf<MediaKey>()
        var volume = 50
        override fun press(key: MediaKey) { keys += key }
        override fun changeVolume(change: VolumeChange): Int {
            volume = when (change) {
                VolumeChange.Up -> volume + 15
                VolumeChange.Down -> volume - 15
                is VolumeChange.Set -> change.percent
                VolumeChange.Mute -> 0
                VolumeChange.Unmute -> 50
            }
            return volume
        }
    }

    private val rvx = "app.rvx.android.apps.youtube.music"
    private val apps = listOf(AppEntry("YT Music", "com.google.android.apps.youtube.music"), AppEntry("RVX Music", rvx))
    private val playlists = FakePlaylists()
    private val phone = FakePhone()
    private val searches = mutableListOf<Pair<String, MusicType>>()

    private fun tool(results: List<MusicItem> = emptyList(), offline: Boolean = false) = PlayMusicTool(
        search = MusicSearch { query, type ->
            searches += query to type
            if (offline) throw IOException("no network")
            results
        },
        playlists = playlists,
        apps = { apps },
        phone = phone,
    )

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { put(it.first, it.second) } }

    @Test
    fun `a song plays by its video id in the preferred player`() = runTest {
        val result = tool(listOf(MusicItem("Обійми", "Океан Ельзи", videoId = "abc"))).execute(args("query" to "Обійми"))

        assertEquals(MusicLinks.track("abc") to rvx, phone.openedUris.single())
        assertTrue((result as ToolResult.Success).endsConversation)
        assertEquals(MusicType.Song, searches.single().second)
    }

    @Test
    fun `an artist plays its mix`() = runTest {
        tool(listOf(MusicItem("Океан Ельзи", "Виконавець", playlistId = "RDEM1"))).execute(args("query" to "Океан Ельзи", "type" to "artist"))

        assertEquals(MusicLinks.list("RDEM1"), phone.openedUris.single().first)
        assertEquals(MusicType.Artist, searches.single().second)
    }

    @Test
    fun `a saved playlist plays without searching`() = runTest {
        SavePlaylistTool(playlists).execute(args("name" to "Музика", "link" to "https://music.youtube.com/playlist?list=PLmine&si=1"))

        tool().execute(args("query" to "музика", "type" to "playlist"))

        assertEquals(MusicLinks.list("PLmine"), phone.openedUris.single().first)
        assertTrue(searches.isEmpty())
    }

    @Test
    fun `nothing found and no network are explained`() = runTest {
        assertTrue(tool(emptyList()).execute(args("query" to "абвгд")) is ToolResult.Failure)
        assertTrue(tool(offline = true).execute(args("query" to "Обійми")) is ToolResult.Failure)
        assertTrue(phone.openedUris.isEmpty())
    }

    @Test
    fun `media keys and volume`() = runTest {
        val media = FakeMedia()
        MediaControlTool(media).execute(args("action" to "next"))
        MediaControlTool(media).execute(args("action" to "pause"))
        assertEquals(listOf(MediaKey.Next, MediaKey.Pause), media.keys)

        val result = SetVolumeTool(media).execute(args("action" to "set", "level" to "30%"))
        assertEquals(ToolResult.Success("Гучність музики: 30%"), result)
        assertTrue(SetVolumeTool(media).execute(args("action" to "set")) is ToolResult.Failure)
    }
}
