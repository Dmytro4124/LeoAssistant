package com.dmytro.leo.data

import android.content.Context
import com.dmytro.leo.brain.GeminiJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import java.io.File

/** One line of the chat as stored on disk. [author]: User, Agent, Error or Tool. */
@Serializable
data class StoredMessage(val author: String, val text: String)

/** Keeps the chat (with its 🔧 tool lines) across app restarts, for debugging and context. */
interface ChatHistoryStore {
    suspend fun load(): List<StoredMessage>
    suspend fun save(messages: List<StoredMessage>)
}

class FileChatHistoryStore(context: Context, private val maxMessages: Int = 300) : ChatHistoryStore {

    private val file = File(context.applicationContext.filesDir, "chat_history.json")

    override suspend fun load(): List<StoredMessage> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        try {
            GeminiJson.decodeFromString<List<StoredMessage>>(file.readText())
        } catch (e: SerializationException) {
            emptyList()
        } catch (e: IllegalArgumentException) {
            emptyList()
        }
    }

    override suspend fun save(messages: List<StoredMessage>) = withContext(Dispatchers.IO) {
        // Write to a temp file first, so a crash mid-write never leaves a broken history
        val tmp = File(file.parentFile, "${file.name}.tmp")
        tmp.writeText(GeminiJson.encodeToString(messages.takeLast(maxMessages)))
        tmp.renameTo(file)
        Unit
    }
}
