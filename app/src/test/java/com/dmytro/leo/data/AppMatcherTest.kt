package com.dmytro.leo.data

import com.dmytro.leo.data.AppMatcher.Match
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppMatcherTest {

    private val apps = listOf(
        AppEntry("YouTube", "com.google.android.youtube"),
        AppEntry("YouTube Music", "com.google.android.apps.youtube.music"),
        AppEntry("Telegram", "org.telegram.messenger"),
        AppEntry("Google Maps", "com.google.android.apps.maps"),
        AppEntry("Камера", "com.google.android.GoogleCamera"),
        AppEntry("Налаштування", "com.android.settings"),
        AppEntry("Viber", "com.viber.voip"),
    )

    private fun found(query: String) = (AppMatcher.find(query, apps) as? Match.Found)?.app?.label

    @Test
    fun `exact and case-insensitive names`() {
        assertEquals("YouTube Music", found("YouTube Music"))
        assertEquals("Telegram", found("telegram"))
        assertEquals("Налаштування", found("налаштування"))
    }

    @Test
    fun `spoken Ukrainian names find Latin labels`() {
        assertEquals("YouTube Music", found("ютуб музик"))
        assertEquals("YouTube", found("ютуб"))
        assertEquals("Telegram", found("телеграм"))
        assertEquals("Viber", found("вайбер"))
    }

    @Test
    fun `partial names`() {
        assertEquals("Google Maps", found("maps"))
        assertEquals("Камера", found("камеру"))
    }

    @Test
    fun `abbreviated labels`() {
        val phoneApps = listOf(
            AppEntry("YT Music", "app.revanced.android.apps.youtube.music"),
            AppEntry("RVX Music", "app.rvx.android.apps.youtube.music"),
            AppEntry("Таблиці", "com.google.android.apps.docs.editors.sheets"),
        )
        val match = AppMatcher.find("YouTube Music", phoneApps)
        assertEquals("YT Music", (match as Match.Found).app.label)
    }

    @Test
    fun `unknown app gives no confident match`() {
        val match = AppMatcher.find("Instagram", apps)
        assertTrue(match is Match.Unsure)
    }
}
