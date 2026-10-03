package com.dmytro.leo.brain

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.jsonPrimitive
import kotlin.math.ceil

/** Turns an HTTP status + body from Gemini into a [GeminiResult]. Pure, no networking. */
object GeminiResponseParser {

    fun parse(httpCode: Int, body: String): GeminiResult =
        if (httpCode in 200..299) parseSuccess(body) else parseError(httpCode, body)

    private fun parseSuccess(body: String): GeminiResult {
        val response = try {
            GeminiJson.decodeFromString<GenerateContentResponse>(body)
        } catch (e: SerializationException) {
            return failure(GeminiError.Unknown("не вдалося прочитати відповідь"))
        } catch (e: IllegalArgumentException) {
            return failure(GeminiError.Unknown("не вдалося прочитати відповідь"))
        }

        response.promptFeedback?.blockReason?.let { return failure(GeminiError.Blocked(it)) }

        val candidate = response.candidates.firstOrNull()
            ?: return failure(GeminiError.EmptyResponse)
        val content = candidate.content

        if (content == null || content.parts.isEmpty()) {
            return when (candidate.finishReason) {
                "SAFETY", "PROHIBITED_CONTENT", "BLOCKLIST", "SPII" ->
                    failure(GeminiError.Blocked(candidate.finishReason))
                else -> failure(GeminiError.EmptyResponse)
            }
        }
        // Gemini sometimes omits the role in responses; history needs it.
        return GeminiResult.Success(content.copy(role = content.role ?: "model"))
    }

    private fun parseError(httpCode: Int, body: String): GeminiResult {
        val error = try {
            GeminiJson.decodeFromString<ApiErrorResponse>(body).error
        } catch (e: SerializationException) {
            null
        } catch (e: IllegalArgumentException) {
            null
        }

        return failure(
            when {
                httpCode == 429 -> GeminiError.RateLimited(error?.retryDelaySeconds())
                httpCode == 401 || httpCode == 403 -> GeminiError.InvalidApiKey
                httpCode == 400 && error != null && error.isInvalidApiKey() -> GeminiError.InvalidApiKey
                httpCode >= 500 -> GeminiError.Server(httpCode)
                else -> GeminiError.Unknown(error?.message?.takeIf { it.isNotBlank() } ?: "HTTP $httpCode")
            }
        )
    }

    private fun ApiError.isInvalidApiKey(): Boolean =
        message.contains("API key", ignoreCase = true) ||
            details.any { it["reason"]?.jsonPrimitive?.content == "API_KEY_INVALID" }

    /** RetryInfo detail looks like {"@type": "...RetryInfo", "retryDelay": "44.55s"}. */
    private fun ApiError.retryDelaySeconds(): Int? =
        details.firstNotNullOfOrNull { it["retryDelay"]?.jsonPrimitive?.content }
            ?.removeSuffix("s")
            ?.toDoubleOrNull()
            ?.let { ceil(it).toInt() }

    private fun failure(error: GeminiError) = GeminiResult.Failure(error)
}
