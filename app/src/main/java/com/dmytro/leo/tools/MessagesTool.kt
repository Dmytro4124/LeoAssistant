package com.dmytro.leo.tools

import android.content.Context
import android.content.pm.PackageManager
import com.dmytro.leo.data.IgnoredChatsStore
import com.dmytro.leo.messages.Inbox
import com.dmytro.leo.messages.MessageNotificationService
import com.dmytro.leo.messages.Messengers
import com.dmytro.leo.messages.UnreadChat
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/** Unread messenger messages, from their notifications. */
interface MessageInbox {
    sealed interface Read {
        data class Unread(val chats: List<UnreadChat>, val installed: Set<String>) : Read
        data object NoAccess : Read
    }

    suspend fun read(): Read
}

class NotificationInbox(private val context: Context) : MessageInbox {
    override suspend fun read(): MessageInbox.Read {
        if (!MessageNotificationService.isAllowed(context)) return MessageInbox.Read.NoAccess
        var service = MessageNotificationService.instance
        if (service == null) {
            MessageNotificationService.rebind(context)
            repeat(10) { if (service == null) { delay(200); service = MessageNotificationService.instance } }
        }
        val chats = service?.unreadChats() ?: return MessageInbox.Read.NoAccess
        val installed = Messengers.packages.filterTo(mutableSetOf()) { isInstalled(it) }
        return MessageInbox.Read.Unread(chats, installed)
    }

    private fun isInstalled(pkg: String) = try {
        context.packageManager.getPackageInfo(pkg, 0); true
    } catch (_: PackageManager.NameNotFoundException) {
        false
    }
}

private const val NO_ACCESS =
    "Немає доступу до сповіщень: попроси користувача увімкнути «Доступ до сповіщень» для Leo в налаштуваннях Leo (розділ «Повідомлення»)."

/** "Leo, перевір, чи мені ніхто не писав" and then "а що пише Наталя?". Skips the chats in [ignored]. */
class CheckMessagesTool(private val inbox: MessageInbox, private val ignored: IgnoredChatsStore) : Tool {

    override val name = "check_messages"
    override val description =
        "Перевірити нові (непрочитані) повідомлення від людей у Telegram, WhatsApp, Viber, Instagram, Discord і Slack — " +
            "«чи мені ніхто не писав», «є нові повідомлення?». Без from — хто й де написав; " +
            "from — повідомлення конкретної людини чи групи («що пише Наталя?»). Канали й розсилки не враховуються. " +
            "Щоразу викликай заново: повідомлення могли прийти чи бути прочитаними."
    override val parameters = objectSchema(
        Param("from", "Чиї повідомлення показати: ім'я людини чи назва групи в називному відмінку («Наталі» → «Наталя»)", required = false),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val read = inbox.read()
        if (read !is MessageInbox.Read.Unread) return ToolResult.Failure(NO_ACCESS)
        val chats = read.chats.filterNot { ignored.isIgnored(it.title) }
        val from = args.string("from") ?: return ToolResult.Success(Inbox.summary(chats, read.installed))
        val messages = Inbox.from(chats, from)
            ?: return ToolResult.Success("Нових повідомлень від «$from» немає (можливо, їх уже прочитали).")
        return ToolResult.Success(
            "Нові повідомлення «$from»:\n$messages\n\nПерекажи їх користувачу або прочитай, якщо короткі. Коди й паролі не озвучуй."
        )
    }
}

/** "Це бот, не зважай на нього": check_messages skips that chat from now on. */
class IgnoreChatTool(private val inbox: MessageInbox, private val ignored: IgnoredChatsStore) : Tool {

    override val name = "ignore_chat"
    override val description =
        "Більше не повідомляти про новий чат у check_messages: «це бот, не зважай», «пропускай цей чат». " +
            "Для ботів, розсилок і чатів, які користувач не хоче чути. Повернути можна в налаштуваннях Leo."
    override val parameters = objectSchema(
        Param("chat", "Назва чату, як її повернув check_messages, або як назвав користувач"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val asked = args.string("chat") ?: return ToolResult.Failure("Не вказано, який чат пропускати")
        // The exact title of the unread chat it means, if there is one
        val unread = (inbox.read() as? MessageInbox.Read.Unread)?.chats.orEmpty()
        val chat = unread.firstOrNull { IgnoredChatsStore.normalize(it.title) == IgnoredChatsStore.normalize(asked) }
            ?: unread.firstOrNull { Inbox.matches(it.title, asked) }
        val title = chat?.title ?: asked
        ignored.add(title, chat?.let { Messengers.name(it.packageName) } ?: "")
        return ToolResult.Success("Більше не зважатиму на «$title». Повернути можна в налаштуваннях Leo (розділ «Повідомлення»).")
    }
}
