package com.dmytro.leo.tools

import com.dmytro.leo.data.IgnoredChatsStore
import com.dmytro.leo.messages.IncomingMessage
import com.dmytro.leo.messages.Inbox
import com.dmytro.leo.messages.Messengers
import com.dmytro.leo.messages.UnreadChat
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MessagesToolTest {

    private val telegram = "org.telegram.messenger"
    private val whatsapp = "com.whatsapp"
    private val all = Messengers.packages

    private val natalia = UnreadChat(telegram, "Наталя Петренко", false, listOf(
        IncomingMessage("Наталя Петренко", "Привіт!", 1), IncomingMessage("Наталя Петренко", "Ти сьогодні будеш?", 2),
    ))
    private val work = UnreadChat(whatsapp, "Робота", true, listOf(IncomingMessage("Олег", "Зустріч о 15:00", 3)))

    private class FakeInbox(private val read: MessageInbox.Read) : MessageInbox {
        override suspend fun read() = read
    }

    @Test
    fun `people's chats count, channels and likes don't`() {
        assertTrue(Messengers.isFromPeople(telegram, "0channel_private_b9fad_-2789981249058919074"))
        assertTrue(Messengers.isFromPeople(telegram, "0channel_groups_b9fad_4139361157648494837"))
        assertFalse(Messengers.isFromPeople(telegram, "0channel_channels_b9fad_8126442623125665893"))
        // Telegram also shows private chats in its "Internal notifications" channel
        assertTrue(Messengers.isFromPeople(telegram, "Other3"))
        assertFalse(Messengers.isChannelPost(telegram, "ndid_123456", group = false))
        assertFalse(Messengers.isChannelPost(telegram, "ndid_-1001234", group = true))
        assertTrue(Messengers.isChannelPost(telegram, "ndid_-1001234", group = false))
        assertTrue(Messengers.isFromPeople(whatsapp, "individual_chat_defaults_1"))
        assertFalse(Messengers.isFromPeople(whatsapp, "chat_history_backup@1"))
        assertTrue(Messengers.isFromPeople("com.instagram.android", "ig_direct"))
        assertFalse(Messengers.isFromPeople("com.instagram.android", "ig_likes"))
        assertTrue(Messengers.isFromPeople("com.discord", "directMessages"))
        assertFalse(Messengers.isFromPeople("com.discord", "friendRequests"))
        assertTrue(Messengers.isFromPeople("com.Slack", "T06J83SF8-1-messages_mentions"))
        assertFalse(Messengers.isFromPeople("com.Slack", "T06J83SF8-4-missed_calls"))
        assertTrue(Messengers.isFromPeople("com.viber.voip", "001_messages_1"))
        assertFalse(Messengers.isFromPeople("com.instagram.barcelona", "ig_direct"))
    }

    @Test
    fun `the summary says who wrote where and which apps are quiet`() {
        val text = Inbox.summary(listOf(natalia, work), installed = all)
        assertTrue(text.contains("Telegram:\n- Наталя Петренко (2)"))
        assertTrue(text.contains("WhatsApp:\n- група «Робота» (1): Олег: «Зустріч о 15:00»"))
        assertTrue(text.contains("Без нових: Viber, Instagram, Discord, Slack."))
        assertEquals("Нових повідомлень від людей немає (перевірено: Telegram, Slack).", Inbox.summary(emptyList(), setOf(telegram, "com.Slack")))
    }

    @Test
    fun `split notifications of one chat are joined without repeats`() {
        val again = natalia.copy(messages = listOf(natalia.messages[1], IncomingMessage("Наталя Петренко", "Набери мене", 4)))
        val merged = Inbox.merge(listOf(natalia, again)).single()
        assertEquals(listOf("Привіт!", "Ти сьогодні будеш?", "Набери мене"), merged.messages.map { it.text })
    }

    @Test
    fun `names match the way they are said`() {
        assertTrue(Inbox.matches("Наталя Петренко", "Наталя"))
        assertTrue(Inbox.matches("Наталя Петренко", "Наталі"))
        assertTrue(Inbox.matches("Олег", "Олегу"))
        assertFalse(Inbox.matches("Олег", "Ольга"))
        assertTrue(Inbox.from(listOf(natalia, work), "Олег")!!.contains("Зустріч о 15:00"))
        assertNull(Inbox.from(listOf(natalia, work), "Мама"))
    }

    @Test
    fun `the tool answers who wrote, then what one person wrote`() = runTest {
        val tool = CheckMessagesTool(FakeInbox(MessageInbox.Read.Unread(listOf(natalia, work), all)), FakeIgnored())
        val who = tool.execute(buildJsonObject { }) as ToolResult.Success
        assertTrue(who.message.contains("Наталя Петренко"))
        val what = tool.execute(buildJsonObject { put("from", "Наталя") }) as ToolResult.Success
        assertTrue(what.message.contains("«Ти сьогодні будеш?»"))
        assertFalse(what.message.contains("Зустріч"))
    }

    private class FakeIgnored : IgnoredChatsStore {
        val saved = mutableMapOf<String, String>()
        override fun all() = saved.toMap()
        override fun add(title: String, app: String) { saved[IgnoredChatsStore.normalize(title)] = app }
        override fun remove(title: String) { saved.remove(title) }
    }

    @Test
    fun `a chat the user called a bot is skipped from then on`() = runTest {
        val bot = UnreadChat(telegram, "Game | Quest Bot", false, listOf(IncomingMessage("Game | Quest Bot", "Твій хід!", 5)))
        val inbox = FakeInbox(MessageInbox.Read.Unread(listOf(natalia, bot), all))
        val ignored = FakeIgnored()

        val saved = IgnoreChatTool(inbox, ignored).execute(buildJsonObject { put("chat", "game quest bot") }) as ToolResult.Success
        assertTrue("the exact title is kept", saved.message.contains("«Game | Quest Bot»"))
        assertEquals(mapOf("game | quest bot" to "Telegram"), ignored.saved)

        val text = (CheckMessagesTool(inbox, ignored).execute(buildJsonObject { }) as ToolResult.Success).message
        assertTrue(text.contains("Наталя Петренко"))
        assertFalse(text.contains("Quest"))
    }

    @Test
    fun `without notification access the user is told how to allow it`() = runTest {
        val result = CheckMessagesTool(FakeInbox(MessageInbox.Read.NoAccess), FakeIgnored()).execute(buildJsonObject { })
        assertTrue((result as ToolResult.Failure).message.contains("Доступ до сповіщень"))
    }
}
