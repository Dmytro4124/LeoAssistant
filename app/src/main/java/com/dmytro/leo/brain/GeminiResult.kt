package com.dmytro.leo.brain

/** Outcome of one Gemini request: either the model's turn or a typed error. */
sealed interface GeminiResult {
    data class Success(val content: Content) : GeminiResult {
        /** Visible answer text, without the model's internal "thoughts". */
        val text: String
            get() = content.parts
                .filter { it.thought != true }
                .mapNotNull { it.text }
                .joinToString("")
                .trim()
    }

    data class Failure(val error: GeminiError) : GeminiResult
}

sealed interface GeminiError {
    data object NoNetwork : GeminiError
    data object Timeout : GeminiError
    data class RateLimited(val retryAfterSeconds: Int?) : GeminiError
    data object InvalidApiKey : GeminiError
    data class Server(val code: Int) : GeminiError
    data class Blocked(val reason: String) : GeminiError
    data object EmptyResponse : GeminiError
    data class Unknown(val message: String) : GeminiError
}

/** Short Ukrainian message, suitable to be spoken aloud. */
fun GeminiError.toUserMessage(): String = when (this) {
    GeminiError.NoNetwork -> "Немає з'єднання з інтернетом."
    GeminiError.Timeout -> "Сервер довго не відповідає. Спробуй ще раз."
    is GeminiError.RateLimited ->
        if (retryAfterSeconds != null) "Забагато запитів. Спробуй через $retryAfterSeconds ${secondsUk(retryAfterSeconds)}."
        else "Забагато запитів. Спробуй трохи пізніше."
    GeminiError.InvalidApiKey -> "Ключ Gemini недійсний або не налаштований. Перевір local.properties."
    is GeminiError.Server -> "Сервіс Gemini тимчасово недоступний, помилка $code."
    is GeminiError.Blocked -> "Gemini відмовився відповідати на цей запит."
    GeminiError.EmptyResponse -> "Gemini повернув порожню відповідь."
    is GeminiError.Unknown -> "Помилка: $message"
}

/** Ukrainian plural: 1 секунду, 2 секунди, 5 секунд, 11 секунд, 21 секунду. */
internal fun secondsUk(n: Int): String {
    val lastTwo = n % 100
    val last = n % 10
    return when {
        lastTwo in 11..14 -> "секунд"
        last == 1 -> "секунду"
        last in 2..4 -> "секунди"
        else -> "секунд"
    }
}
