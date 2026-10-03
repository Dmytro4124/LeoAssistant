package com.dmytro.leo.brain

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

// JSON shapes of the Gemini REST API (generateContent).
// Only the fields we use are declared; the rest are ignored when parsing.

val GeminiJson = Json {
    ignoreUnknownKeys = true
    explicitNulls = false // do not send "field": null
}

@Serializable
data class GenerateContentRequest(
    val contents: List<Content>,
    val systemInstruction: Content? = null,
    val generationConfig: GenerationConfig? = null,
)

/** One dialog turn. role = "user" or "model". */
@Serializable
data class Content(
    val role: String? = null,
    val parts: List<Part> = emptyList(),
) {
    companion object {
        fun user(text: String) = Content(role = "user", parts = listOf(Part(text = text)))
        fun system(text: String) = Content(parts = listOf(Part(text = text)))
    }
}

/**
 * A piece of a turn. Gemini 3 models attach [thoughtSignature]; it must be sent back
 * unchanged in the history, so model turns are stored exactly as received.
 */
@Serializable
data class Part(
    val text: String? = null,
    val thought: Boolean? = null,
    val thoughtSignature: String? = null,
)

@Serializable
data class GenerationConfig(
    val temperature: Double? = null,
    val maxOutputTokens: Int? = null,
)

@Serializable
data class GenerateContentResponse(
    val candidates: List<Candidate> = emptyList(),
    val promptFeedback: PromptFeedback? = null,
)

@Serializable
data class Candidate(
    val content: Content? = null,
    val finishReason: String? = null,
)

@Serializable
data class PromptFeedback(
    val blockReason: String? = null,
)

@Serializable
data class ApiErrorResponse(
    val error: ApiError,
)

@Serializable
data class ApiError(
    val code: Int = 0,
    val message: String = "",
    val status: String = "",
    val details: List<JsonObject> = emptyList(),
)
