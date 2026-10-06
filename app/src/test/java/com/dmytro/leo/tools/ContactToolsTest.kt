package com.dmytro.leo.tools

import com.dmytro.leo.data.ChatStore
import com.dmytro.leo.data.Contact
import com.dmytro.leo.data.ContactAliases
import com.dmytro.leo.data.ContactMatcher
import com.dmytro.leo.data.Messenger
import com.dmytro.leo.data.PhoneNumber
import com.dmytro.leo.data.PhoneNumbers
import com.dmytro.leo.data.SavedChat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContactToolsTest {

    private val olehP = Contact(1, "Олег Петренко", listOf(PhoneNumber("067 111-22-33", Contact.MOBILE)), setOf(Messenger.Telegram))
    private val olehK = Contact(2, "Олег Коваленко", listOf(PhoneNumber("+380501234567", Contact.MOBILE)))
    private val mom = Contact(3, "Мама", listOf(PhoneNumber("0991112233", Contact.MOBILE)), setOf(Messenger.WhatsApp))
    private val phoneBook = listOf(olehP, olehK, mom)

    private class FakeAliases : ContactAliases {
        val map = mutableMapOf<String, String>()
        override fun all() = map
        override fun save(alias: String, target: String) { map[ContactAliases.normalize(alias)] = target }
        override fun remove(alias: String) { map.remove(alias) }
    }

    private class FakeComm : CommActions {
        val calls = mutableListOf<String>()
        val sms = mutableListOf<Pair<String, String>>()
        var speaker: Boolean? = null
        override fun call(number: String, speaker: Boolean): Boolean { this.speaker = speaker; return calls.add(number) }
        override fun sendSms(number: String, text: String) = sms.add(number to text)
    }

    private val turns = UserTurns()
    private val gate = ConfirmationGate(turns)
    private val aliases = FakeAliases()
    private val comm = FakeComm()
    private val phone = FakePhone()
    private var autoSend = AutoSendResult.Sent
    private val sentBy = mutableListOf<Pair<String, String>>()

    private fun call() = CallTool({ phoneBook }, aliases, { "+380670000000" }, gate, comm)
    private class FakeChats : ChatStore {
        val map = mutableMapOf<String, SavedChat>()
        override fun all() = map
        override fun save(name: String, chat: SavedChat) { map[ChatStore.normalizeName(name)] = chat }
        override fun remove(name: String) { map.remove(name) }
    }

    private val chats = FakeChats()

    private fun message() = SendMessageTool({ phoneBook }, aliases, { "+380670000000" }, chats, gate, phone) { pkg, text ->
        sentBy += pkg to text
        autoSend
    }

    private fun args(vararg pairs: Pair<String, String>): JsonObject = buildJsonObject { pairs.forEach { put(it.first, it.second) } }

    @Test
    fun `contacts are found by name, with declension leftovers`() {
        assertEquals(listOf(olehP, olehK), ContactMatcher.find("Олег", phoneBook))
        assertEquals(listOf(olehP), ContactMatcher.find("Олег Петренко", phoneBook))
        assertEquals(listOf(olehP, olehK), ContactMatcher.find("Олегу", phoneBook))
        assertEquals(listOf(mom), ContactMatcher.find("мамі", phoneBook))
        assertTrue(ContactMatcher.find("Ірина", phoneBook).isEmpty())
    }

    @Test
    fun `find_contact lists matches with masked numbers only`() = runTest {
        val result = FindContactTool({ phoneBook }, aliases).execute(args("name" to "Олег")) as ToolResult.Success

        assertTrue(result.message.contains("contact_id=1: Олег Петренко (мобільний …2233; є в Telegram)"))
        assertTrue(result.message.contains("contact_id=2"))
        assertFalse("full numbers stay on the phone", result.message.contains("111-22-33"))
    }

    @Test
    fun `remembered alias wins`() = runTest {
        RememberContactTool(aliases).execute(args("alias" to "Бос", "contact_id" to "2"))
        val result = FindContactTool({ phoneBook }, aliases).execute(args("name" to "бос")) as ToolResult.Success
        assertTrue(result.message.startsWith("contact_id=2:"))
    }

    @Test
    fun `call happens only after the user confirms`() = runTest {
        val first = call().execute(args("contact_id" to "1")) as ToolResult.NeedsConfirmation
        assertTrue(comm.calls.isEmpty())

        // The model tries to confirm by itself, without the user saying anything
        assertTrue(call().execute(args("contact_id" to "1", "confirmation" to first.token)) is ToolResult.NeedsConfirmation)
        assertTrue(comm.calls.isEmpty())

        // The previous attempt issued a new token; the user says "так"; the model calls again with it
        val second = call().execute(args("contact_id" to "1")) as ToolResult.NeedsConfirmation
        turns.next()
        val done = call().execute(args("contact_id" to "1", "confirmation" to second.token))

        assertTrue(done is ToolResult.Success)
        assertEquals(listOf("067 111-22-33"), comm.calls)
        assertEquals("loudspeaker only when asked for", false, comm.speaker)
    }

    @Test
    fun `call on the loudspeaker when asked`() = runTest {
        val ask = call().execute(args("contact_id" to "1", "speaker" to "true")) as ToolResult.NeedsConfirmation
        turns.next()
        val done = call().execute(args("contact_id" to "1", "speaker" to "true", "confirmation" to ask.token)) as ToolResult.Success

        assertEquals(true, comm.speaker)
        assertTrue(done.message.contains("гучному"))
    }

    @Test
    fun `a call waits until the voice session releases the audio`() {
        val handoff = com.dmytro.leo.voice.AudioHandoff()
        val placed = mutableListOf<String>()
        handoff.runWhenFree { placed += "before" }
        handoff.acquire()
        handoff.runWhenFree { placed += "during" }
        assertEquals(listOf("before"), placed)
        handoff.release()
        assertEquals(listOf("before", "during"), placed)
        handoff.release()
        assertEquals("runs once", listOf("before", "during"), placed)
    }

    @Test
    fun `a confirmation is for that exact text and one use only`() = runTest {
        val ask = message().execute(args("app" to "telegram", "contact_id" to "1", "text" to "Я запізнююсь")) as ToolResult.NeedsConfirmation
        turns.next()

        val changed = message().execute(args("app" to "telegram", "contact_id" to "1", "text" to "Я не прийду", "confirmation" to ask.token))
        assertTrue("other text needs its own confirmation", changed is ToolResult.NeedsConfirmation)

        val ask2 = message().execute(args("app" to "telegram", "contact_id" to "1", "text" to "Я запізнююсь")) as ToolResult.NeedsConfirmation
        turns.next()
        val ok = message().execute(args("app" to "telegram", "contact_id" to "1", "text" to "Я запізнююсь", "confirmation" to ask2.token))
        assertTrue(ok is ToolResult.Success)
        assertEquals(listOf("org.telegram.messenger" to "Я запізнююсь"), sentBy)

        val reused = message().execute(args("app" to "telegram", "contact_id" to "1", "text" to "Я запізнююсь", "confirmation" to ask2.token))
        assertTrue(reused is ToolResult.NeedsConfirmation)
    }

    @Test
    fun `message to self opens Telegram with the own number`() = runTest {
        val ask = message().execute(args("app" to "telegram", "contact_id" to "self", "text" to "тест")) as ToolResult.NeedsConfirmation
        turns.next()
        message().execute(args("app" to "telegram", "contact_id" to "self", "text" to "тест", "confirmation" to ask.token))

        assertEquals("tg://resolve?phone=380670000000&text=%D1%82%D0%B5%D1%81%D1%82", phone.openedUris.single().first)
    }

    @Test
    fun `without auto-send the chat is opened and the user presses Send`() = runTest {
        autoSend = AutoSendResult.NotEnabled
        val ask = message().execute(args("app" to "whatsapp", "contact_id" to "3", "text" to "Привіт")) as ToolResult.NeedsConfirmation
        turns.next()
        val result = message().execute(args("app" to "whatsapp", "contact_id" to "3", "text" to "Привіт", "confirmation" to ask.token))

        assertTrue((result as ToolResult.Failure).message.contains("текст скопійовано"))
        assertEquals("https://api.whatsapp.com/send?phone=380991112233&text=%D0%9F%D1%80%D0%B8%D0%B2%D1%96%D1%82", phone.openedUris.single().first)
    }

    @Test
    fun `shared links become chats`() {
        assertEquals(
            SavedChat(Messenger.Instagram, "https://ig.me/m/lera.k"),
            ChatStore.chatFromLink("https://www.instagram.com/lera.k?igsh=abc123"),
        )
        assertEquals(
            SavedChat(Messenger.Discord, "https://discord.com/channels/@me/1122334455"),
            ChatStore.chatFromLink("https://discord.com/channels/@me/1122334455/9988776655"),
        )
        assertEquals(
            SavedChat(Messenger.Discord, "https://discord.com/channels/123/456"),
            ChatStore.chatFromLink("Подивись https://canary.discord.com/channels/123/456"),
        )
        assertEquals(
            SavedChat(Messenger.Slack, "https://acme-team.slack.com/archives/D04ABCDEF12"),
            ChatStore.chatFromLink("https://acme-team.slack.com/archives/D04ABCDEF12/p1727000000123456"),
        )
        assertEquals(SavedChat(Messenger.Telegram, "tg://resolve?domain=my_group"), ChatStore.chatFromLink("https://t.me/my_group"))
        assertEquals(null, ChatStore.chatFromLink("https://www.instagram.com/p/C0ffee/"))
        assertEquals(null, ChatStore.chatFromLink("https://music.youtube.com/playlist?list=PL1"))
    }

    @Test
    fun `instagram message goes to a saved chat after confirmation`() = runTest {
        SaveChatTool(chats).execute(args("name" to "Лера", "link" to "https://instagram.com/lera.k"))

        val ask = message().execute(args("app" to "instagram", "chat" to "лера", "text" to "Привіт")) as ToolResult.NeedsConfirmation
        assertTrue(ask.question.contains("Instagram"))
        turns.next()
        val done = message().execute(args("app" to "instagram", "chat" to "лера", "text" to "Привіт", "confirmation" to ask.token))

        assertTrue(done is ToolResult.Success)
        assertFalse("the conversation goes on after a sent message", (done as ToolResult.Success).endsConversation)
        assertEquals("https://ig.me/m/lera.k" to "com.instagram.android", phone.openedUris.single())
        assertEquals(listOf("com.instagram.android" to "Привіт"), sentBy)
    }

    @Test
    fun `instagram and discord need a saved chat`() = runTest {
        val noChat = message().execute(args("app" to "discord", "contact_id" to "1", "text" to "Привіт"))
        assertTrue((noChat as ToolResult.Failure).message.contains("збережений чат"))

        SaveChatTool(chats).execute(args("name" to "Лера", "link" to "https://instagram.com/lera.k"))
        val wrongApp = message().execute(args("app" to "discord", "chat" to "Лера", "text" to "Привіт"))
        assertTrue(wrongApp is ToolResult.Failure)
        assertTrue(phone.openedUris.isEmpty())
    }

    @Test
    fun `phone numbers`() {
        assertEquals("380671112233", PhoneNumbers.normalize("067 111-22-33"))
        assertEquals("380501234567", PhoneNumbers.normalize("+38 (050) 123-45-67"))
        assertEquals("…4567", PhoneNumbers.masked("+380501234567"))
    }
}
