package com.dmytro.leo.brain

/**
 * Dialog history kept in memory. Only the last [maxMessages] are kept, to save
 * free-tier tokens. Gemini requires the history to start with a user's text turn,
 * so trimming drops messages from the front until that holds.
 */
class Conversation(private val maxMessages: Int) {

    private val history = mutableListOf<Content>()

    val messages: List<Content>
        get() = history.toList()

    fun add(vararg contents: Content) {
        history += contents
        trim()
    }

    fun clear() = history.clear()

    private fun trim() {
        while (history.size > maxMessages) history.removeAt(0)
        while (history.isNotEmpty() && !history.first().isUserText()) history.removeAt(0)
    }

    private fun Content.isUserText() = role == "user" && parts.any { it.text != null }
}
