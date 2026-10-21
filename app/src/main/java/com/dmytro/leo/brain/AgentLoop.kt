package com.dmytro.leo.brain

import com.dmytro.leo.tools.ToolRegistry
import com.dmytro.leo.tools.ToolResult
import com.dmytro.leo.tools.UserTurns
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.JsonObject
import java.time.Clock
import java.time.ZonedDateTime

/** A tool the agent ran while answering, shown in the chat for debugging. */
data class ToolAction(val name: String, val args: JsonObject, val result: ToolResult)

/** What the agent produced for one user phrase. */
sealed interface AgentReply {
    data class Text(val text: String, val actions: List<ToolAction> = emptyList()) : AgentReply
    data class Error(val error: GeminiError, val actions: List<ToolAction> = emptyList()) : AgentReply {
        val message: String get() = error.toUserMessage()
    }
}

/**
 * The heart of the assistant: takes a user phrase, asks Gemini with the system prompt,
 * dialog history and tools, runs the tools Gemini calls, and returns the final reply.
 *
 * "Подзвони Олегу" → Gemini calls find_contact → we return the matches → Gemini asks
 * which one, or calls call() → ... until it answers with text (at most [MAX_STEPS] calls).
 */
class AgentLoop(
    private val gemini: GeminiApi,
    private val tools: ToolRegistry = ToolRegistry(emptyList()),
    private val clock: Clock = Clock.systemDefaultZone(),
    /** Extra facts for the system prompt, read on every request (e.g. saved places). */
    private val promptNotes: () -> String = { "" },
    /** Shared with the confirmation gate: every user phrase counts, typed or spoken. */
    private val userTurns: UserTurns = UserTurns(),
    /** Every finished exchange (typed or spoken), e.g. for building up the long-term memory. */
    private val onExchange: (user: String, agent: String) -> Unit = { _, _ -> },
    maxHistoryMessages: Int = AgentConfig.MAX_HISTORY_MESSAGES,
) {
    private val conversation = Conversation(maxHistoryMessages)

    // One phrase at a time, so the history never gets interleaved
    private val mutex = Mutex()

    val toolDeclarations get() = tools.declarations

    /** The same instructions for the text chat and for Live voice sessions. */
    fun systemPrompt(): String = SystemPrompt.build(ZonedDateTime.now(clock), promptNotes())

    /** A Live voice session heard the user start a new phrase. */
    fun noteUserTurn() = userTurns.next()

    suspend fun handle(userText: String): AgentReply = mutex.withLock {
        userTurns.next()
        // This exchange: the user's turn, then model calls and our function responses
        val exchange = mutableListOf(Content.user(userText))
        val actions = mutableListOf<ToolAction>()

        repeat(MAX_STEPS) {
            val request = GenerateContentRequest(
                systemInstruction = Content.system(systemPrompt()),
                contents = conversation.messages + exchange,
                tools = tools.declarations.ifEmpty { null },
            )
            val modelTurn = when (val result = gemini.generateContent(request)) {
                is GeminiResult.Success -> result.content
                // A failed exchange is not saved, so the next attempt starts from a clean history
                is GeminiResult.Failure -> return@withLock AgentReply.Error(result.error, actions)
            }
            // Model turns are kept exactly as received (thoughtSignature must go back unchanged)
            exchange += modelTurn

            val calls = modelTurn.parts.mapNotNull { it.functionCall }
            if (calls.isEmpty()) {
                val text = GeminiResult.Success(modelTurn).text
                    .ifEmpty { if (actions.isNotEmpty()) "Готово." else "" }
                if (text.isEmpty()) return@withLock AgentReply.Error(GeminiError.EmptyResponse, actions)
                conversation.add(*exchange.toTypedArray())
                onExchange(userText, text)
                return@withLock AgentReply.Text(text, actions)
            }

            val responses = calls.map { call ->
                val result = tools.execute(call.name, call.args)
                actions += ToolAction(call.name, call.args, result)
                Part(functionResponse = FunctionResponse(call.name, result.toJson(), call.id))
            }
            exchange += Content(role = "user", parts = responses)
        }
        AgentReply.Error(GeminiError.Unknown("забагато кроків поспіль"), actions)
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
            onExchange(userText, agentText)
        }
    }

    /** Runs a tool for a Live session, which has its own function-calling loop. */
    suspend fun runTool(name: String, args: JsonObject): ToolResult = tools.execute(name, args)

    private companion object {
        const val MAX_STEPS = 5
    }
}
