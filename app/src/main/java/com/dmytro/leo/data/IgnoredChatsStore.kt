package com.dmytro.leo.data

import android.content.Context
import androidx.core.content.edit

/**
 * Chats check_messages skips: bots, mailings, anything the user said to ignore.
 * Telegram doesn't mark bots in its notifications, so the user tells Leo which ones they are.
 * Chat title (lowercase) → app name.
 */
interface IgnoredChatsStore {
    fun all(): Map<String, String>
    fun add(title: String, app: String)
    fun remove(title: String)

    fun isIgnored(title: String) = normalize(title) in all()

    companion object {
        fun normalize(title: String) = title.trim().trim('«', '»', '"').lowercase()
    }
}

class AndroidIgnoredChatsStore(context: Context) : IgnoredChatsStore {

    private val prefs = context.applicationContext.getSharedPreferences("ignored_chats", Context.MODE_PRIVATE)

    override fun all(): Map<String, String> =
        prefs.all.mapNotNull { (key, value) -> (value as? String)?.let { key to it } }.toMap().toSortedMap()

    override fun add(title: String, app: String) {
        prefs.edit { putString(IgnoredChatsStore.normalize(title), app) }
    }

    override fun remove(title: String) {
        prefs.edit { remove(title) }
    }
}
