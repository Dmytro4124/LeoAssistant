package com.dmytro.leo.data

import android.content.Context
import androidx.core.content.edit

/** The user's favourite places: "дім" → "вулиця Шевченка, 5, Кам'янець-Подільський". */
interface PlacesStore {
    fun all(): Map<String, String>
    fun save(name: String, address: String)

    /** The saved address for a name like "Дім", or null. */
    fun find(name: String): String? = all()[normalizeName(name)]

    /** Saved places as a note for the system prompt, or "" if there are none. */
    fun promptNote(): String {
        val places = all()
        if (places.isEmpty()) return ""
        return "Збережені місця користувача (використовуй адресу, коли він каже «додому», «на роботу» тощо):\n" +
            places.entries.joinToString("\n") { "- ${it.key}: ${it.value}" }
    }

    companion object {
        fun normalizeName(name: String) = name.trim().lowercase()
    }
}

class AndroidPlacesStore(context: Context) : PlacesStore {

    private val prefs = context.applicationContext.getSharedPreferences("places", Context.MODE_PRIVATE)

    override fun all(): Map<String, String> =
        prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap().toSortedMap()

    override fun save(name: String, address: String) {
        prefs.edit { putString(PlacesStore.normalizeName(name), address.trim()) }
    }
}
