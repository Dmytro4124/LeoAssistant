package com.dmytro.leo

import com.dmytro.leo.brain.AgentConfig
import com.dmytro.leo.brain.AgentLoop
import com.dmytro.leo.brain.GeminiApi
import com.dmytro.leo.brain.GeminiClient
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

/**
 * Manual dependency injection: every shared object is created here, once per app process.
 * Screens get what they need from [LeoApp.container].
 */
class AppContainer {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .build()

    val gemini: GeminiApi = GeminiClient(
        apiKey = BuildConfig.GEMINI_API_KEY,
        model = AgentConfig.MODEL,
        httpClient = httpClient,
    )

    val agentLoop = AgentLoop(gemini)
}
