package com.dmytro.leo.data

import android.content.Context
import androidx.core.content.edit

/** A chat that has no phone number: an Instagram DM, a Discord channel, a Telegram group. */
data class SavedChat(val messenger: Messenger, val link: String)

/** Chats by name: "лера" → Instagram DM with @lera. */
interface ChatStore {
    fun all(): Map<String, SavedChat>
    fun save(name: String, chat: SavedChat)
    fun remove(name: String)
    fun find(name: String): SavedChat? = all()[normalizeName(name)]

    fun promptNote(): String {
        val chats = all()
        if (chats.isEmpty()) return ""
        return "Збережені чати (send_message з chat=назва): " +
            chats.entries.joinToString { "${it.key} (${it.value.messenger.name})" }
    }

    companion object {
        fun normalizeName(name: String) = name.trim().lowercase()

        /**
         * A shared link → the chat it opens, or null if it isn't a chat link.
         * instagram.com/<user> or ig.me/m/<user> → Instagram DM;
         * discord.com/channels/<server|@me>/<channel>[/<message>] → that Discord channel;
         * t.me/<name> → Telegram chat, group or channel.
         */
        fun chatFromLink(text: String): SavedChat? {
            Regex("""https?://(?:www\.)?(?:instagram\.com|ig\.me/m)/([A-Za-z0-9._]+)""").find(text)?.let {
                val user = it.groupValues[1]
                if (user !in INSTAGRAM_NON_PROFILES) return SavedChat(Messenger.Instagram, "https://ig.me/m/$user")
            }
            Regex("""https?://(?:\w+\.)?discord(?:app)?\.com/channels/(@me|\d+)/(\d+)""").find(text)?.let {
                return SavedChat(Messenger.Discord, "https://discord.com/channels/${it.groupValues[1]}/${it.groupValues[2]}")
            }
            Regex("""https?://t\.me/([A-Za-z0-9_]{4,})""").find(text)?.let {
                return SavedChat(Messenger.Telegram, "tg://resolve?domain=${it.groupValues[1]}")
            }
            return null
        }

        private val INSTAGRAM_NON_PROFILES = setOf("p", "reel", "reels", "stories", "explore", "direct", "accounts")
    }
}

class AndroidChatStore(context: Context) : ChatStore {
    private val prefs = context.applicationContext.getSharedPreferences("chats", Context.MODE_PRIVATE)

    override fun all(): Map<String, SavedChat> = prefs.all.mapNotNull { (name, value) ->
        val (messenger, link) = (value as? String)?.split('|', limit = 2)?.takeIf { it.size == 2 } ?: return@mapNotNull null
        val app = Messenger.entries.firstOrNull { it.name == messenger } ?: return@mapNotNull null
        name to SavedChat(app, link)
    }.toMap()

    override fun remove(name: String) {
        prefs.edit { remove(name) }
    }

    override fun save(name: String, chat: SavedChat) {
        prefs.edit { putString(ChatStore.normalizeName(name), "${chat.messenger.name}|${chat.link}") }
    }
}
