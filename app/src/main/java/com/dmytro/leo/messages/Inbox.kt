package com.dmytro.leo.messages

/** One message from a messenger notification. */
data class IncomingMessage(val sender: String, val text: String, val time: Long)

/** A chat with unread messages, as its notification shows it. [group] = a group chat, [title] = its name. */
data class UnreadChat(
    val packageName: String,
    val title: String,
    val group: Boolean,
    val messages: List<IncomingMessage>,
)

/** The messengers Leo checks, and which of their notification channels are messages from people. Pure Kotlin, unit-tested. */
object Messengers {

    private class App(val name: String, val include: List<String>?, val exclude: List<String> = emptyList())

    private val APPS = linkedMapOf(
        // Telegram also puts chats into "Other"/per-chat channels, so only the known non-people ones are dropped
        "org.telegram.messenger" to App("Telegram", include = null, exclude = listOf("channels", "call", "stories", "reaction")),
        "com.whatsapp" to App("WhatsApp", include = listOf("individual_chat", "group_chat", "silent_notifications")),
        "com.viber.voip" to App("Viber", include = listOf("messages", "mentions"), exclude = listOf("dating", "reminder")),
        "com.instagram.android" to App("Instagram", include = listOf("ig_direct"), exclude = listOf("video_chat")),
        "com.discord" to App("Discord", include = listOf("directMessages", "messages"), exclude = listOf("missed")),
        "com.Slack" to App("Slack", include = listOf("messages_mentions")),
    )

    val packages: Set<String> get() = APPS.keys

    fun name(packageName: String) = APPS[packageName]?.name ?: packageName

    /** True for chats with people: not channels, calls, likes, reminders and the like. */
    fun isFromPeople(packageName: String, channelId: String?): Boolean {
        val app = APPS[packageName] ?: return false
        val channel = channelId.orEmpty().lowercase()
        if (app.exclude.any { channel.contains(it.lowercase()) }) return false
        return app.include == null || app.include.any { channel.contains(it.lowercase()) }
    }

    /**
     * A Telegram channel post: its chat id (in the shortcut "ndid_<id>") is negative like a group's,
     * but it isn't a group conversation. People have positive ids.
     */
    fun isChannelPost(packageName: String, shortcutId: String?, group: Boolean): Boolean =
        packageName == "org.telegram.messenger" && !group && shortcutId?.startsWith("ndid_-") == true
}

/** What Leo tells about unread messages. Pure Kotlin, unit-tested. */
object Inbox {

    private const val MAX_MESSAGES = 10
    private const val MAX_TEXT = 500

    /** Notifications can split one chat; joins them, drops repeats, keeps the latest messages. */
    fun merge(chats: List<UnreadChat>): List<UnreadChat> =
        chats.groupBy { it.packageName to it.title }.map { (_, same) ->
            val messages = same.flatMap { it.messages }.distinct().sortedBy { it.time }.takeLast(MAX_MESSAGES)
            same.first().copy(group = same.any { it.group }, messages = messages)
        }.filter { it.messages.isNotEmpty() }.sortedByDescending { chat -> chat.messages.maxOf { it.time } }

    /** Who wrote where, with the texts, so a follow-up "що пише Наталя?" needs no new check. */
    fun summary(chats: List<UnreadChat>, installed: Collection<String>): String {
        val merged = merge(chats)
        val quiet = Messengers.packages.filter { it in installed && merged.none { chat -> chat.packageName == it } }.map(Messengers::name)
        if (merged.isEmpty()) {
            return "Нових повідомлень від людей немає" + (if (quiet.isNotEmpty()) " (перевірено: ${quiet.joinToString()})" else "") + "."
        }
        return buildString {
            appendLine("Нові повідомлення від людей:")
            merged.groupBy { it.packageName }.forEach { (pkg, appChats) ->
                appendLine("${Messengers.name(pkg)}:")
                appChats.forEach { appendLine("- " + describe(it)) }
            }
            if (quiet.isNotEmpty()) appendLine("Без нових: ${quiet.joinToString()}.")
            append(
                "Скажи коротко лише хто написав і де (групи — назвою групи). Тексти не читай, доки користувач не спитає " +
                    "(«що пише Наталя?») — тоді перекажи або прочитай саме ці повідомлення. Коди й паролі з повідомлень не озвучуй."
            )
        }
    }

    /** The messages of one person or chat: [who] as the user said it ("Наталя", "робоча група"). */
    fun from(chats: List<UnreadChat>, who: String): String? {
        val merged = merge(chats)
        val found = merged.filter { chat -> matches(chat.title, who) || chat.messages.any { matches(it.sender, who) } }
        if (found.isEmpty()) return null
        return found.joinToString("\n") { "${Messengers.name(it.packageName)}: " + describe(it) }
    }

    private fun describe(chat: UnreadChat): String {
        val texts = chat.messages.joinToString(" / ") { msg ->
            val text = "«${msg.text.trim().take(MAX_TEXT)}»"
            if (chat.group) "${msg.sender}: $text" else text
        }
        return if (chat.group) "група «${chat.title}» (${chat.messages.size}): $texts"
        else "${chat.title} (${chat.messages.size}): $texts"
    }

    /** "Наталя" matches "Наталя Петренко" and "Наталі" (endings change in Ukrainian). */
    fun matches(name: String, who: String): Boolean {
        val words = name.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }
        val asked = who.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length >= 2 }
        if (asked.isEmpty()) return false
        return asked.all { a -> words.any { w -> w == a || (a.length >= 4 && w.length >= 4 && w.take(a.length - 1) == a.dropLast(1)) || w.startsWith(a) } }
    }
}
