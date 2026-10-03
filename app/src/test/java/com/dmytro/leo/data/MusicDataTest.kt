package com.dmytro.leo.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MusicDataTest {

    /** Trimmed real response shape: a song result and an artist result with a mix. */
    private val searchResponse = """
        {"contents":{"tabbedSearchResultsRenderer":{"tabs":[{"tabRenderer":{"content":{"sectionListRenderer":{"contents":[
          {"musicShelfRenderer":{"title":{"runs":[{"text":"Пісні"}]},"contents":[
            {"musicResponsiveListItemRenderer":{
              "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Обійми","navigationEndpoint":{"watchEndpoint":{"videoId":"8wWrzr3_46Y"}}}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Океан Ельзи"},{"text":" • "},{"text":"Земля"}]}}}
              ],
              "playlistItemData":{"videoId":"8wWrzr3_46Y"}
            }},
            {"musicResponsiveListItemRenderer":{
              "flexColumns":[
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Океан Ельзи"}]}}},
                {"musicResponsiveListItemFlexColumnRenderer":{"text":{"runs":[{"text":"Виконавець"}]}}}
              ],
              "overlay":{"musicItemThumbnailOverlayRenderer":{"content":{"musicPlayButtonRenderer":{
                "playNavigationEndpoint":{"watchPlaylistEndpoint":{"playlistId":"RDEMBYoXRAybw4y6xpYJv6NKRQ"}}}}}}
            }}
          ]}}
        ]}}}}]}}}
    """.trimIndent()

    @Test
    fun `search results are parsed in order`() {
        val items = YouTubeMusicParser.parse(searchResponse)

        assertEquals(2, items.size)
        assertEquals(MusicItem("Обійми", "Океан Ельзи • Земля", videoId = "8wWrzr3_46Y"), items[0])
        assertEquals("RDEMBYoXRAybw4y6xpYJv6NKRQ", items[1].playlistId)
        assertNull(items[1].videoId)
    }

    @Test
    fun `broken response gives no results`() {
        assertEquals(emptyList<MusicItem>(), YouTubeMusicParser.parse("<html>"))
        assertEquals(emptyList<MusicItem>(), YouTubeMusicParser.parse("{}"))
    }

    @Test
    fun `patched builds replace official apps`() {
        val installed = setOf("com.google.android.apps.youtube.music", "app.rvx.android.apps.youtube.music", "org.telegram.messenger")
        assertEquals("app.rvx.android.apps.youtube.music", PreferredApps.youTubeMusic(installed))
        assertEquals("com.google.android.apps.youtube.music", PreferredApps.youTubeMusic(setOf("com.google.android.apps.youtube.music")))
        assertNull(PreferredApps.youTubeMusic(setOf("org.telegram.messenger")))

        val apps = listOf(
            AppEntry("YT Music", "com.google.android.apps.youtube.music"),
            AppEntry("RVX Music", "app.rvx.android.apps.youtube.music"),
        )
        // "YouTube Music" finds the official label, but opens the patched build
        val match = AppMatcher.find("YouTube Music", PreferredApps.apply(apps)) as AppMatcher.Match.Found
        assertEquals("app.rvx.android.apps.youtube.music", match.app.packageName)
    }

    @Test
    fun `playlist id from shared links`() {
        assertEquals("PLabc_123-x", PlaylistStore.playlistIdFrom("https://music.youtube.com/playlist?list=PLabc_123-x&si=XYZ"))
        assertEquals("PLabc", PlaylistStore.playlistIdFrom("Слухай: https://youtube.com/playlist?list=PLabc"))
        assertEquals("OLAK5uy_k", PlaylistStore.playlistIdFrom("OLAK5uy_k"))
        assertEquals("PLabc", PlaylistStore.playlistIdFrom("VLPLabc"))
        assertNull(PlaylistStore.playlistIdFrom("https://music.youtube.com/watch?v=8wWrzr3_46Y"))
    }
}
