package com.dmytro.leo.brain

import org.junit.Assert.assertEquals
import org.junit.Test

class ConversationTest {

    private fun model(text: String) = Content(role = "model", parts = listOf(Part(text = text)))

    @Test
    fun `keeps only the last messages`() {
        val conversation = Conversation(maxMessages = 4)
        repeat(5) { i -> conversation.add(Content.user("q$i"), model("a$i")) }

        val texts = conversation.messages.map { it.parts.single().text }
        assertEquals(listOf("q3", "a3", "q4", "a4"), texts)
    }

    @Test
    fun `history always starts with a user turn`() {
        // an odd limit would leave a model turn first; it must be dropped too
        val conversation = Conversation(maxMessages = 3)
        conversation.add(Content.user("q1"), model("a1"))
        conversation.add(Content.user("q2"), model("a2"))

        val messages = conversation.messages
        assertEquals("user", messages.first().role)
        assertEquals(listOf("q2", "a2"), messages.map { it.parts.single().text })
    }

    @Test
    fun `clear empties history`() {
        val conversation = Conversation(maxMessages = 10)
        conversation.add(Content.user("q"), model("a"))
        conversation.clear()
        assertEquals(emptyList<Content>(), conversation.messages)
    }
}
