package com.dmytro.leo.brain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeminiResponseParserTest {

    @Test
    fun `success returns model text and keeps thoughtSignature`() {
        val body = """
            {"candidates":[{"content":{"role":"model","parts":[
              {"text":"Привіт! Я Лео.","thoughtSignature":"EmAKXg=="}
            ]},"finishReason":"STOP"}],"usageMetadata":{"totalTokenCount":42}}
        """.trimIndent()

        val result = GeminiResponseParser.parse(200, body) as GeminiResult.Success

        assertEquals("Привіт! Я Лео.", result.text)
        assertEquals("EmAKXg==", result.content.parts.single().thoughtSignature)
    }

    @Test
    fun `thought parts are hidden from text`() {
        val body = """
            {"candidates":[{"content":{"role":"model","parts":[
              {"text":"міркую...","thought":true},{"text":"Відповідь"}
            ]}}]}
        """.trimIndent()

        val result = GeminiResponseParser.parse(200, body) as GeminiResult.Success

        assertEquals("Відповідь", result.text)
    }

    @Test
    fun `missing role is filled as model`() {
        val body = """{"candidates":[{"content":{"parts":[{"text":"Так"}]}}]}"""

        val result = GeminiResponseParser.parse(200, body) as GeminiResult.Success

        assertEquals("model", result.content.role)
    }

    @Test
    fun `429 reads retry delay and rounds it up`() {
        val body = """
            {"error":{"code":429,"message":"You exceeded your current quota","status":"RESOURCE_EXHAUSTED",
              "details":[
                {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[]},
                {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"44.55s"}
              ]}}
        """.trimIndent()

        assertEquals(failure(GeminiError.RateLimited(45)), GeminiResponseParser.parse(429, body))
    }

    @Test
    fun `429 without details still is rate limited`() {
        assertEquals(failure(GeminiError.RateLimited(null)), GeminiResponseParser.parse(429, "not json"))
    }

    @Test
    fun `invalid api key`() {
        val body = """
            {"error":{"code":400,"message":"API key not valid. Please pass a valid API key.","status":"INVALID_ARGUMENT",
              "details":[{"@type":"type.googleapis.com/google.rpc.ErrorInfo","reason":"API_KEY_INVALID"}]}}
        """.trimIndent()

        assertEquals(failure(GeminiError.InvalidApiKey), GeminiResponseParser.parse(400, body))
    }

    @Test
    fun `other 400 keeps the api message`() {
        val body = """{"error":{"code":400,"message":"Thinking level MINIMAL is not supported","status":"INVALID_ARGUMENT"}}"""

        assertEquals(
            failure(GeminiError.Unknown("Thinking level MINIMAL is not supported")),
            GeminiResponseParser.parse(400, body),
        )
    }

    @Test
    fun `5xx is a server error`() {
        assertEquals(failure(GeminiError.Server(503)), GeminiResponseParser.parse(503, "<html>Service Unavailable</html>"))
    }

    @Test
    fun `blocked prompt`() {
        val body = """{"promptFeedback":{"blockReason":"SAFETY"}}"""

        assertEquals(failure(GeminiError.Blocked("SAFETY")), GeminiResponseParser.parse(200, body))
    }

    @Test
    fun `candidate stopped for safety without content`() {
        val body = """{"candidates":[{"finishReason":"SAFETY"}]}"""

        assertEquals(failure(GeminiError.Blocked("SAFETY")), GeminiResponseParser.parse(200, body))
    }

    @Test
    fun `no candidates is an empty response`() {
        assertEquals(failure(GeminiError.EmptyResponse), GeminiResponseParser.parse(200, "{}"))
    }

    @Test
    fun `malformed success body`() {
        val result = GeminiResponseParser.parse(200, "<html>")
        assertTrue(result is GeminiResult.Failure && result.error is GeminiError.Unknown)
    }

    private fun failure(error: GeminiError) = GeminiResult.Failure(error)
}
