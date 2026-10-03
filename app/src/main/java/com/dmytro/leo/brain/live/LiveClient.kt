package com.dmytro.leo.brain.live

import android.util.Log
import com.dmytro.leo.brain.Content
import com.dmytro.leo.brain.GeminiError
import com.dmytro.leo.brain.GeminiJson
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.serialization.SerializationException
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import java.io.IOException
import java.net.UnknownHostException
import java.util.Base64

/** What happens in a Live session, as seen by the app. */
sealed interface LiveEvent {
    /** Setup accepted; audio can be streamed now. */
    data object Ready : LiveEvent
    class Audio(val pcm: ByteArray) : LiveEvent
    data class InputTranscript(val text: String) : LiveEvent
    data class OutputTranscript(val text: String) : LiveEvent
    data object Interrupted : LiveEvent
    data object TurnComplete : LiveEvent
    data object GoAway : LiveEvent
    /** Final event. [error] is null for a normal close. */
    data class Closed(val error: GeminiError?) : LiveEvent
}

/** One open Live session. */
interface LiveConnection {
    val events: Flow<LiveEvent>
    fun sendHistory(turns: List<Content>)
    /** 16 kHz, 16-bit, mono PCM. */
    fun sendAudio(pcm: ByteArray)
    fun close()
}

/** Opens Live sessions. Implemented by [LiveClient]; tests use a fake. */
fun interface LiveApi {
    fun connect(setup: LiveSetup): LiveConnection
}

class LiveClient(
    private val apiKey: String,
    private val httpClient: OkHttpClient,
    private val url: String = "wss://generativelanguage.googleapis.com/ws/" +
        "google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
) : LiveApi {

    override fun connect(setup: LiveSetup): LiveConnection {
        val events = Channel<LiveEvent>(Channel.UNLIMITED)
        if (apiKey.isBlank()) {
            events.trySend(LiveEvent.Closed(GeminiError.InvalidApiKey))
            events.close()
            return ClosedConnection(events)
        }

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                webSocket.send(GeminiJson.encodeToString(LiveClientMessage(setup = setup)))
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                // Debug trace of everything except the (large) audio chunks
                if (!text.contains("\"inlineData\"")) Log.d(TAG, text.take(500))
                LiveEventParser.parse(text).forEach { events.trySend(it) }
            }

            // The server sends JSON in binary frames too
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = onMessage(webSocket, bytes.utf8())

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                events.trySend(LiveEvent.Closed(LiveEventParser.closeError(code, reason)))
                events.close()
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val error = when {
                    response?.code == 401 || response?.code == 403 -> GeminiError.InvalidApiKey
                    response?.code == 429 -> GeminiError.RateLimited(null)
                    t is UnknownHostException -> GeminiError.NoNetwork
                    t is IOException && response == null -> GeminiError.NoNetwork
                    else -> GeminiError.Unknown(t.message ?: "з'єднання розірвано")
                }
                events.trySend(LiveEvent.Closed(error))
                events.close()
            }
        }

        val request = Request.Builder()
            .url(url)
            .header("x-goog-api-key", apiKey)
            .build()
        val socket = httpClient.newWebSocket(request, listener)

        return object : LiveConnection {
            override val events: Flow<LiveEvent> = events.receiveAsFlow()

            override fun sendHistory(turns: List<Content>) {
                if (turns.isNotEmpty()) {
                    socket.send(GeminiJson.encodeToString(LiveClientMessage(clientContent = LiveClientContent(turns))))
                }
            }

            override fun sendAudio(pcm: ByteArray) {
                val data = Base64.getEncoder().encodeToString(pcm)
                socket.send(GeminiJson.encodeToString(LiveClientMessage(realtimeInput = LiveRealtimeInput(Blob(MIC_MIME, data)))))
            }

            override fun close() {
                socket.close(1000, null)
                events.trySend(LiveEvent.Closed(null))
                events.close()
            }
        }
    }

    private class ClosedConnection(channel: Channel<LiveEvent>) : LiveConnection {
        override val events: Flow<LiveEvent> = channel.receiveAsFlow()
        override fun sendHistory(turns: List<Content>) = Unit
        override fun sendAudio(pcm: ByteArray) = Unit
        override fun close() = Unit
    }

    companion object {
        const val MIC_SAMPLE_RATE = 16_000
        const val SPEAKER_SAMPLE_RATE = 24_000
        private const val MIC_MIME = "audio/pcm;rate=16000"
        private const val TAG = "LeoLive"
    }
}

/** Pure parsing of server messages; no networking, so it is unit-tested. */
object LiveEventParser {


    fun parse(json: String): List<LiveEvent> {
        val message = try {
            GeminiJson.decodeFromString<LiveServerMessage>(json)
        } catch (e: SerializationException) {
            return emptyList()
        } catch (e: IllegalArgumentException) {
            return emptyList()
        }

        val events = mutableListOf<LiveEvent>()
        if (message.setupComplete != null) events += LiveEvent.Ready
        message.serverContent?.let { content ->
            content.inputTranscription?.text?.takeIf { it.isNotEmpty() }?.let { events += LiveEvent.InputTranscript(it) }
            content.modelTurn?.parts.orEmpty().forEach { part ->
                part.inlineData?.takeIf { it.mimeType.startsWith("audio/") }?.let {
                    events += LiveEvent.Audio(Base64.getDecoder().decode(it.data))
                }
            }
            content.outputTranscription?.text?.takeIf { it.isNotEmpty() }?.let { events += LiveEvent.OutputTranscript(it) }
            if (content.interrupted == true) events += LiveEvent.Interrupted
            if (content.turnComplete == true) events += LiveEvent.TurnComplete
        }
        if (message.goAway != null) events += LiveEvent.GoAway
        return events
    }

    /** Error behind a server-initiated close, or null for a normal one. */
    fun closeError(code: Int, reason: String): GeminiError? = when {
        code == 1000 -> null
        reason.contains("quota", ignoreCase = true) || reason.contains("exceeded", ignoreCase = true) ->
            GeminiError.RateLimited(null)
        reason.contains("API key", ignoreCase = true) -> GeminiError.InvalidApiKey
        code == 1011 || code == 1013 -> GeminiError.Server(code)
        else -> GeminiError.Unknown(reason.ifBlank { "з'єднання закрито ($code)" })
    }
}
