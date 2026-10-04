package com.dmytro.leo.tools

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject

/** Something the agent can do on the phone. Every new ability is a new class. */
interface Tool {
    /** Function name for Gemini, e.g. "navigate_to". */
    val name: String
    /** When and how to use it, for Gemini (in Ukrainian, like the rest of the prompt). */
    val description: String
    /** JSON schema of the arguments; build it with [objectSchema]. */
    val parameters: JsonObject

    suspend fun execute(args: JsonObject): ToolResult
}

/** Outcome of a tool, sent back to Gemini, which then tells the user in its own words. */
sealed interface ToolResult {
    /**
     * [endsConversation]: the action takes over the sound or the user's attention (music,
     * a call), so a voice session ends once the agent has confirmed it. Just opening
     * an app does not end it: the conversation goes on over the other app ("відкрий телеграм" → "напиши…").
     */
    data class Success(val message: String, val endsConversation: Boolean = false) : ToolResult
    data class Failure(val message: String) : ToolResult

    /** Nothing done yet: the model must ask [question] and call again with [token] after "так". */
    data class NeedsConfirmation(val question: String, val token: String) : ToolResult

    fun toJson(): JsonObject = buildJsonObject {
        when (this@ToolResult) {
            is Success -> put("result", message)
            is Failure -> put("error", message)
            is NeedsConfirmation -> {
                put("needs_confirmation", question)
                put("confirmation", token)
                put(
                    "instruction",
                    "Нічого ще не виконано. Постав користувачу це питання і дочекайся відповіді. " +
                        "Якщо він підтвердить — виклич інструмент ще раз з тими самими аргументами і confirmation=\"$token\". " +
                        "Якщо відмовиться або змінить текст чи адресата — не викликай з цим токеном.",
                )
            }
        }
    }
}

// ---- small helpers for schemas and arguments ----

/** One parameter of a tool. [enum] limits the allowed values. */
data class Param(
    val name: String,
    val description: String,
    val required: Boolean = true,
    val enum: List<String>? = null,
)

/** Builds {"type": "OBJECT", "properties": {...}, "required": [...]} with string parameters. */
fun objectSchema(vararg params: Param): JsonObject = buildJsonObject {
    put("type", "OBJECT")
    putJsonObject("properties") {
        params.forEach { param ->
            putJsonObject(param.name) {
                put("type", "STRING")
                put("description", param.description)
                param.enum?.let { values -> put("enum", buildJsonArray { values.forEach { add(JsonPrimitive(it)) } }) }
            }
        }
    }
    val required = params.filter { it.required }
    if (required.isNotEmpty()) put("required", buildJsonArray { required.forEach { add(JsonPrimitive(it.name)) } })
}

/** A string argument, or null if missing or blank. */
fun JsonObject.string(name: String): String? =
    this[name]?.jsonPrimitive?.contentOrNull?.trim()?.takeIf { it.isNotEmpty() }
