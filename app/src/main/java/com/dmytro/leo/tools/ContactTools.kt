package com.dmytro.leo.tools

import com.dmytro.leo.data.ChatStore
import com.dmytro.leo.data.Contact
import com.dmytro.leo.data.ContactAliases
import com.dmytro.leo.data.ContactMatcher
import com.dmytro.leo.data.ContactsSource
import com.dmytro.leo.data.Messenger
import com.dmytro.leo.data.OwnNumber
import com.dmytro.leo.data.PhoneNumbers
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

private const val NO_CONTACTS_PERMISSION = "Немає дозволу на контакти. Попроси користувача натиснути «Надати» на банері дозволів у Лео."

/** Who a message or call goes to, resolved from tool arguments. */
private data class Recipient(val name: String, val number: String, val contact: Contact?)

/** "contact_id" | "phone_number" | "self" → a recipient, or an error message for Gemini. */
private class RecipientResolver(
    private val contacts: ContactsSource,
    private val aliases: ContactAliases,
    private val ownNumber: OwnNumber,
) {
    fun resolve(args: JsonObject): Result<Recipient> {
        val contactId = args.string("contact_id")
        val phone = args.string("phone_number")
        return when {
            contactId == "self" -> selfRecipient()
            contactId != null -> {
                val id = contactId.toLongOrNull() ?: return Result.failure(Exception("contact_id має бути числом з find_contact"))
                val contact = loadContacts().getOrElse { return Result.failure(it) }.firstOrNull { it.id == id }
                    ?: return Result.failure(Exception("Контакт $id не знайдено, спершу виклич find_contact"))
                val number = contact.mainPhone ?: return Result.failure(Exception("У контакту ${contact.name} немає номера"))
                Result.success(Recipient(contact.name, number, contact))
            }
            phone != null -> Result.success(Recipient(phone, phone, null))
            else -> Result.failure(Exception("Вкажи contact_id (з find_contact), phone_number або self"))
        }
    }

    private fun selfRecipient(): Result<Recipient> {
        val saved = aliases.find("я")?.removePrefix("phone:")
        val number = saved ?: ownNumber.get()
            ?: return Result.failure(Exception("Не знаю номера цього телефона. Попроси користувача назвати його номер і збережи через remember_contact з alias «я»."))
        return Result.success(Recipient("собі", number, null))
    }

    fun loadContacts(): Result<List<Contact>> = try {
        Result.success(contacts.all())
    } catch (e: SecurityException) {
        Result.failure(Exception(NO_CONTACTS_PERMISSION))
    }
}

class FindContactTool(
    private val contacts: ContactsSource,
    private val aliases: ContactAliases,
) : Tool {

    override val name = "find_contact"
    override val description =
        "Знайти людину в контактах телефона перед дзвінком чи повідомленням. Повертає збіги з їхніми id. " +
            "Якщо збігів кілька — перепитай, кого саме. Знайдених людей можна запам'ятати через remember_contact («мама» → контакт)."
    override val parameters = objectSchema(
        Param("name", "Ім'я в називному відмінку, як записують у контакти: «Олег», «Олег Петренко», «мама»"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val query = args.string("name") ?: return ToolResult.Failure("Не вказано ім'я")
        val all = try {
            contacts.all()
        } catch (e: SecurityException) {
            return ToolResult.Failure(NO_CONTACTS_PERMISSION)
        }

        // A remembered alias ("мама") wins over name search
        val aliased = aliases.find(query)?.removePrefix("contact:")?.toLongOrNull()?.let { id -> all.firstOrNull { it.id == id } }
        val matches = aliased?.let(::listOf) ?: ContactMatcher.find(query, all)
        if (matches.isEmpty()) return ToolResult.Failure("У контактах немає нікого на ім'я «$query»")

        // Only what's needed to choose: names, masked numbers, messengers — not the whole phone book
        return ToolResult.Success(matches.joinToString("\n") { it.describe() })
    }

    private fun Contact.describe(): String {
        val phonesText = phones.joinToString { "${it.label} ${PhoneNumbers.masked(it.number)}" }
        val apps = if (messengers.isEmpty()) "" else "; є в ${messengers.joinToString { it.name }}"
        return "contact_id=$id: $name ($phonesText$apps)"
    }
}

class RememberContactTool(private val aliases: ContactAliases) : Tool {

    override val name = "remember_contact"
    override val description =
        "Запам'ятати, як користувач називає людину: «мама» → контакт з find_contact. " +
            "Також для номера цього телефона: alias «я» і phone_number."
    override val parameters = objectSchema(
        Param("alias", "Як користувач її називає: мама, тато, бос, я"),
        Param("contact_id", "id з find_contact", required = false),
        Param("phone_number", "Номер, якщо це не контакт (напр. для «я»)", required = false),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val alias = args.string("alias") ?: return ToolResult.Failure("Не вказано, як запам'ятати")
        val target = args.string("contact_id")?.toLongOrNull()?.let { ContactAliases.contact(it) }
            ?: args.string("phone_number")?.let { ContactAliases.phone(it) }
            ?: return ToolResult.Failure("Вкажи contact_id або phone_number")
        aliases.save(alias, target)
        return ToolResult.Success("Запам'ятав: «$alias»")
    }
}

private val RECIPIENT_PARAMS = arrayOf(
    Param("contact_id", "id з find_contact, або «self» — собі (Збережене в Telegram)", required = false),
    Param("phone_number", "Номер, якщо людини немає в контактах", required = false),
)
private val CONFIRMATION_PARAM = Param("confirmation", "Токен підтвердження з попередньої відповіді цього інструмента", required = false)

class CallTool(
    contacts: ContactsSource,
    aliases: ContactAliases,
    ownNumber: OwnNumber,
    private val gate: ConfirmationGate,
    private val comm: CommActions,
) : Tool {

    private val recipients = RecipientResolver(contacts, aliases, ownNumber)

    override val name = "call"
    override val description = "Подзвонити. Спершу знайди людину через find_contact. Дзвінок відбудеться лише після підтвердження користувача."
    override val parameters = objectSchema(*RECIPIENT_PARAMS, CONFIRMATION_PARAM)

    override suspend fun execute(args: JsonObject): ToolResult {
        val to = recipients.resolve(args).getOrElse { return ToolResult.Failure(it.message.orEmpty()) }
        val key = "call|${PhoneNumbers.normalize(to.number)}"
        if (!gate.confirm(args.string("confirmation"), key)) {
            return ToolResult.NeedsConfirmation("Подзвонити: ${to.name} (${PhoneNumbers.masked(to.number)})?", gate.ask(key))
        }
        return if (comm.call(to.number)) ToolResult.Success("Дзвоню: ${to.name}", endsConversation = true)
        else ToolResult.Failure("Немає дозволу на дзвінки. Попроси користувача натиснути «Надати» на банері дозволів у Лео.")
    }
}

class SendSmsTool(
    contacts: ContactsSource,
    aliases: ContactAliases,
    ownNumber: OwnNumber,
    private val gate: ConfirmationGate,
    private val comm: CommActions,
) : Tool {

    private val recipients = RecipientResolver(contacts, aliases, ownNumber)

    override val name = "send_sms"
    override val description = "Надіслати SMS. Спершу знайди людину через find_contact. Надсилається лише після підтвердження користувача."
    override val parameters = objectSchema(*RECIPIENT_PARAMS, Param("text", "Текст повідомлення"), CONFIRMATION_PARAM)

    override suspend fun execute(args: JsonObject): ToolResult {
        val text = args.string("text") ?: return ToolResult.Failure("Не вказано текст")
        val to = recipients.resolve(args).getOrElse { return ToolResult.Failure(it.message.orEmpty()) }
        val key = "sms|${PhoneNumbers.normalize(to.number)}|$text"
        if (!gate.confirm(args.string("confirmation"), key)) {
            return ToolResult.NeedsConfirmation("Надіслати SMS (${to.name}): «$text»?", gate.ask(key))
        }
        return if (comm.sendSms(to.number, text)) ToolResult.Success("SMS надіслано: ${to.name}")
        else ToolResult.Failure("Немає дозволу на SMS. Попроси користувача натиснути «Надати» на банері дозволів у Лео.")
    }
}

class SendMessageTool(
    contacts: ContactsSource,
    aliases: ContactAliases,
    ownNumber: OwnNumber,
    private val chats: ChatStore,
    private val gate: ConfirmationGate,
    private val phone: PhoneActions,
    private val autoSender: AutoSender,
) : Tool {

    private val recipients = RecipientResolver(contacts, aliases, ownNumber)

    override val name = "send_message"
    override val description =
        "Надіслати повідомлення в Telegram, WhatsApp, Viber, Instagram або Discord. " +
            "Для Telegram/WhatsApp/Viber спершу знайди людину через find_contact (або contact_id=self — собі, у Збережене). " +
            "Instagram і Discord — лише в збережені чати (параметр chat); збережені чати бувають і для Telegram-груп. " +
            "Надсилається лише після підтвердження користувача."
    override val parameters = objectSchema(
        Param("app", "Месенджер", enum = listOf("telegram", "whatsapp", "viber", "instagram", "discord")),
        Param("chat", "Назва збереженого чату (обов'язково для Instagram і Discord)", required = false),
        *RECIPIENT_PARAMS,
        Param("text", "Текст повідомлення"),
        CONFIRMATION_PARAM,
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val messenger = when (args.string("app")) {
            "telegram" -> Messenger.Telegram
            "whatsapp" -> Messenger.WhatsApp
            "viber" -> Messenger.Viber
            "instagram" -> Messenger.Instagram
            "discord" -> Messenger.Discord
            else -> return ToolResult.Failure("Вкажи app: telegram, whatsapp, viber, instagram або discord")
        }
        val text = args.string("text") ?: return ToolResult.Failure("Не вказано текст")

        // Where to: a saved chat by name, or a person by phone number
        val (who, link) = args.string("chat")?.let { chatName ->
            val chat = chats.find(chatName)
                ?: return ToolResult.Failure("Чату «$chatName» немає серед збережених. Його можна зберегти: «Поділитися» посиланням → Лео.")
            if (chat.messenger != messenger) return ToolResult.Failure("Чат «$chatName» збережено для ${chat.messenger.name}, а не ${messenger.name}")
            chatName to chat.link
        } ?: run {
            if (messenger == Messenger.Instagram || messenger == Messenger.Discord) {
                return ToolResult.Failure(
                    "Для ${messenger.name} потрібен збережений чат (параметр chat). Збережені: " +
                        chats.all().filterValues { it.messenger == messenger }.keys.ifEmpty { listOf("немає") }.joinToString() +
                        ". Зберегти: у ${messenger.name} «Поділитися» профілем чи повідомленням → Лео."
                )
            }
            val to = recipients.resolve(args).getOrElse { return ToolResult.Failure(it.message.orEmpty()) }
            to.name to MessengerLinks.chat(messenger, to.number, text)
        }

        val key = "message|${messenger.name}|${link.substringBefore("&text=").substringBefore("&draft=")}|$text"
        if (!gate.confirm(args.string("confirmation"), key)) {
            return ToolResult.NeedsConfirmation("Надіслати в ${messenger.name} ($who): «$text»?", gate.ask(key))
        }

        if (!phone.openUri(link, messenger.packageName)) {
            return ToolResult.Failure("Не вдалося відкрити ${messenger.name}. Він встановлений?")
        }
        return when (autoSender.send(messenger.packageName, text)) {
            AutoSendResult.Sent -> ToolResult.Success("Надіслано в ${messenger.name}: $who", endsConversation = true)
            // Not sent: the messenger may have ignored the text, so don't claim it is in the field
            AutoSendResult.NotEnabled -> ToolResult.Failure(
                "НЕ надіслано: автонадсилання вимкнене в спеціальних можливостях. Чат відкрито, " +
                    "текст скопійовано — нехай користувач вставить його і натисне «Надіслати»",
            )
            AutoSendResult.Failed -> ToolResult.Failure(
                "НЕ надіслано: не вдалося натиснути «Надіслати». Чат відкрито, " +
                    "текст скопійовано — нехай користувач вставить його і натисне «Надіслати»",
            )
        }
    }
}

class SaveChatTool(private val chats: ChatStore) : Tool {

    override val name = "save_chat"
    override val description =
        "Запам'ятати чат за посиланням, щоб потім писати туди голосом: профіль Instagram, канал чи особисті " +
            "повідомлення Discord, група чи канал Telegram. Користувач ділиться посиланням з Лео. Якщо назви немає — спитай її."
    override val parameters = objectSchema(
        Param("name", "Як користувач називатиме чат: «Лера», «друзі в дискорді»"),
        Param("link", "Посилання: instagram.com/…, discord.com/channels/…, t.me/…"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val chatName = args.string("name") ?: return ToolResult.Failure("Не вказано назву чату")
        val link = args.string("link") ?: return ToolResult.Failure("Не вказано посилання")
        val chat = ChatStore.chatFromLink(link)
            ?: return ToolResult.Failure("Це не посилання на чат Instagram, Discord чи Telegram")
        chats.save(chatName, chat)
        return ToolResult.Success("Запам'ятав чат «$chatName» (${chat.messenger.name})")
    }
}

/** Links that open a chat with the text already typed in. Pure, unit-tested. */
object MessengerLinks {
    fun chat(messenger: Messenger, number: String, text: String): String {
        val digits = PhoneNumbers.normalize(number)
        val encoded = URLEncoder.encode(text, "UTF-8").replace("+", "%20")
        return when (messenger) {
            Messenger.Telegram -> "tg://resolve?phone=$digits&text=$encoded"
            Messenger.WhatsApp -> "https://api.whatsapp.com/send?phone=$digits&text=$encoded"
            Messenger.Viber -> "viber://chat?number=%2B$digits&draft=$encoded"
            Messenger.Instagram, Messenger.Discord -> error("${messenger.name} has no phone-number chats")
        }
    }
}
