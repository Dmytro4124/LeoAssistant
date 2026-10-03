package com.dmytro.leo.brain

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.io.InterruptedIOException

/** Anything that can answer a Gemini request. Lets tests use a fake instead of the network. */
interface GeminiApi {
    suspend fun generateContent(request: GenerateContentRequest): GeminiResult
}

/** Calls the Gemini REST API directly with OkHttp. */
class GeminiClient(
    private val apiKey: String,
    private val model: String,
    private val httpClient: OkHttpClient,
    private val baseUrl: String = "https://generativelanguage.googleapis.com/v1beta/",
) : GeminiApi {

    override suspend fun generateContent(request: GenerateContentRequest): GeminiResult {
        if (apiKey.isBlank()) return GeminiResult.Failure(GeminiError.InvalidApiKey)

        val httpRequest = Request.Builder()
            .url("${baseUrl}models/$model:generateContent")
            .header("x-goog-api-key", apiKey) // in a header, so the key never shows up in URL logs
            .post(GeminiJson.encodeToString(request).toRequestBody(JSON))
            .build()

        // execute() blocks the thread, so it runs on the IO dispatcher, never on the UI thread
        return withContext(Dispatchers.IO) {
            try {
                httpClient.newCall(httpRequest).execute().use { response ->
                    GeminiResponseParser.parse(response.code, response.body.string())
                }
            } catch (e: InterruptedIOException) {
                GeminiResult.Failure(GeminiError.Timeout)
            } catch (e: IOException) {
                GeminiResult.Failure(GeminiError.NoNetwork)
            }
        }
    }

    private companion object {
        val JSON = "application/json; charset=utf-8".toMediaType()
    }
}
