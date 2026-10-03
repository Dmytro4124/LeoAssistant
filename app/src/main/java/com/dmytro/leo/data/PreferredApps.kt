package com.dmytro.leo.data

/**
 * Patched builds (ReVanced, RVX) replace the official Google apps for the user:
 * "YouTube Music" means RVX Music if it is installed.
 */
object PreferredApps {

    private const val GOOGLE_PREFIX = "com.google.android."
    private val PATCHED_PREFIXES = listOf("app.rvx.android.", "app.revanced.android.")

    const val YOUTUBE_MUSIC = "com.google.android.apps.youtube.music"

    /** The installed patched build of an official app, e.g. app.rvx.android.apps.youtube.music. */
    fun patchedFor(officialPackage: String, installed: Set<String>): String? {
        if (!officialPackage.startsWith(GOOGLE_PREFIX)) return null
        val suffix = officialPackage.removePrefix(GOOGLE_PREFIX)
        return PATCHED_PREFIXES.map { it + suffix }.firstOrNull { it in installed }
    }

    /** Official entries whose patched build is installed now open the patched one. */
    fun apply(apps: List<AppEntry>): List<AppEntry> {
        val installed = apps.map { it.packageName }.toSet()
        return apps.map { app -> patchedFor(app.packageName, installed)?.let { app.copy(packageName = it) } ?: app }
    }

    /** Package to play YouTube Music links in, or null if neither build is installed. */
    fun youTubeMusic(installed: Set<String>): String? =
        patchedFor(YOUTUBE_MUSIC, installed) ?: YOUTUBE_MUSIC.takeIf { it in installed }
}
