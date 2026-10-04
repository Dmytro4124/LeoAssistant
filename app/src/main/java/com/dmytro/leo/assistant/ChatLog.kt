package com.dmytro.leo.assistant

import com.dmytro.leo.brain.AgentLoop
import com.dmytro.leo.brain.ToolAction
import com.dmytro.leo.data.ChatHistoryStore
import com.dmytro.leo.data.StoredMessage
import com.dmytro.leo.tools.ToolResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonPrimitive
import java.util.concurrent.atomic.AtomicLong

data class ChatMessage(val id: Long, val author: Author, val text: String) {
    /** Tool = a service line about an action the agent ran ("🔧 navigate_to …"). */
    enum class Author { User, Agent, Error, Tool }
}

/**
 * The conversation as the user sees it, shared by every screen (chat, assistant overlay)
 * and by voice sessions. Kept on disk, so it survives restarts.
 */
@OptIn(FlowPreview::class)
class ChatLog(
    private val agent: AgentLoop,
    /** Null in tests: nothing is kept across restarts. */
    private val store: ChatHistoryStore?,
    scope: CoroutineScope,
) {
    private val _messages = MutableStateFlow<List<ChatMessage>>(emptyList())
    val messages: StateFlow<List<ChatMessage>> = _messages.asStateFlow()

    private val nextId = AtomicLong()

    init {
        store?.let { history ->
            scope.launch {
                restore(history)
                // Then keep the file in sync, at most twice a second
                _messages.debounce(500).collect { list ->
                    history.save(list.map { StoredMessage(it.author.name, it.text) })
                }
            }
        }
    }

    fun append(author: ChatMessage.Author, text: String): Long {
        val id = nextId.getAndIncrement()
        _messages.update { it + ChatMessage(id, author, text) }
        return id
    }

    /** Appends a message, or replaces the text of the one with [id] (a growing voice transcript). */
    fun upsert(id: Long?, author: ChatMessage.Author, text: String): Long {
        if (id == null) return append(author, text)
        _messages.update { list -> list.map { if (it.id == id) it.copy(text = text) else it } }
        return id
    }

    fun addActions(actions: List<ToolAction>) = actions.forEach { append(ChatMessage.Author.Tool, describe(it)) }

    suspend fun clear() {
        agent.reset()
        _messages.value = emptyList()
    }

    /**
     * Shows the chat from the previous run and gives the agent its last exchanges back,
     * so follow-ups keep working after a restart.
     */
    private suspend fun restore(history: ChatHistoryStore) {
        val restored = history.load().mapNotNull { stored ->
            val author = ChatMessage.Author.entries.firstOrNull { it.name == stored.author } ?: return@mapNotNull null
            ChatMessage(nextId.getAndIncrement(), author, stored.text)
        }
        if (restored.isEmpty()) return
        _messages.update { restored + it }

        // A user phrase followed by the agent's answer (tool lines in between are skipped)
        restored.filter { it.author == ChatMessage.Author.User || it.author == ChatMessage.Author.Agent }
            .zipWithNext()
            .filter { (q, a) -> q.author == ChatMessage.Author.User && a.author == ChatMessage.Author.Agent }
            .takeLast(RESTORED_EXCHANGES)
            .forEach { (q, a) -> agent.remember(q.text, a.text) }
    }

    companion object {
        private const val RESTORED_EXCHANGES = 10

        private const val MAX_OUTCOME = 120

        /** "🔧 navigate_to: Будинок культури, driving → Навігацію запущено" */
        fun describe(action: ToolAction): String {
            val args = action.args.values.joinToString { (it as? JsonPrimitive)?.content ?: it.toString() }
            val outcome = when (val result = action.result) {
                is ToolResult.Success -> result.message
                is ToolResult.Failure -> "✗ " + result.message
                is ToolResult.NeedsConfirmation -> "❔ " + result.question
            }
            // Screen texts and search results can be long: the chat shows only the start
            val short = outcome.lineSequence().first().let { if (it.length > MAX_OUTCOME) it.take(MAX_OUTCOME) + "…" else it }
            return "🔧 ${action.name}: $args → $short"
        }
    }
}
