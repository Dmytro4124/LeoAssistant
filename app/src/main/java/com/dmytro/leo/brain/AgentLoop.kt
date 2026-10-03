package com.dmytro.leo.brain

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Clock
import java.time.ZonedDateTime

/** What the agent produced for one user phrase. */
sealed interface AgentReply {
    data class Text(val text: String) : AgentReply
    data class Error(val error: GeminiError) : AgentReply {
        val message: String get() = error.toUserMessage()
    }
}

/**
 * The heart of the assistant: takes a user phrase, asks Gemini with the system prompt
 * and dialog history, and returns the reply. Stage 3 adds the function-calling loop here.
 */
class AgentLoop(
    private val gemini: GeminiApi,
    private val clock: Clock = Clock.systemDefaultZone(),
    maxHistoryMessages: Int = AgentConfig.MAX_HISTORY_MESSAGES,
) {
    private val conversation = Conversation(maxHistoryMessages)

    // One phrase at a time, so the history never gets interleaved
    private val mutex = Mutex()

    suspend fun handle(userText: String): AgentReply = mutex.withLock {
        val userTurn = Content.user(userText)
        val request = GenerateContentRequest(
            systemInstruction = Content.system(SystemPrompt.build(ZonedDateTime.now(clock))),
            contents = conversation.messages + userTurn,
        )

        when (val result = gemini.generateContent(request)) {
            is GeminiResult.Success -> {
                val text = result.text
                if (text.isEmpty()) {
                    AgentReply.Error(GeminiError.EmptyResponse)
                } else {
                    // Save the model turn exactly as received (keeps thoughtSignature)
                    conversation.add(userTurn, result.content)
                    AgentReply.Text(text)
                }
            }
            // A failed exchange is not saved, so the next attempt starts from a clean history
            is GeminiResult.Failure -> AgentReply.Error(result.error)
        }
    }

    suspend fun reset() = mutex.withLock { conversation.clear() }

    /** Dialog so far as plain text turns, to give a Live voice session the same context. */
    suspend fun history(): List<Content> = mutex.withLock {
        conversation.messages.mapNotNull { turn ->
            val text = turn.parts.filter { it.thought != true }.mapNotNull { it.text }.joinToString("").trim()
            if (text.isEmpty()) null else Content(role = turn.role, parts = listOf(Part(text = text)))
        }
    }

    /** Adds an exchange that happened elsewhere (a Live voice session) to the shared history. */
    suspend fun remember(userText: String, agentText: String) = mutex.withLock {
        if (userText.isNotBlank() && agentText.isNotBlank()) {
            conversation.add(Content.user(userText), Content(role = "model", parts = listOf(Part(text = agentText))))
        }
    }
}
