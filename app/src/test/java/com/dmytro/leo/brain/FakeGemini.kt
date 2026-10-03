package com.dmytro.leo.brain

/** Returns queued results and remembers every request. */
class FakeGemini(vararg results: GeminiResult) : GeminiApi {
    private val queue = ArrayDeque(results.toList())
    val requests = mutableListOf<GenerateContentRequest>()

    override suspend fun generateContent(request: GenerateContentRequest): GeminiResult {
        requests += request
        return queue.removeFirst()
    }

    companion object {
        fun answer(text: String) =
            GeminiResult.Success(Content(role = "model", parts = listOf(Part(text = text, thoughtSignature = "sig"))))
    }
}
