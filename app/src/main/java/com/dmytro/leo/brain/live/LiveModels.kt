package com.dmytro.leo.brain.live

import com.dmytro.leo.brain.Content
import com.dmytro.leo.brain.FunctionCall
import com.dmytro.leo.brain.FunctionResponse
import com.dmytro.leo.brain.ToolDeclarations
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

// JSON messages of the Gemini Live API (WebSocket BidiGenerateContent).
// Each message sets exactly one top-level field.

// ---- client → server ----

@Serializable
data class LiveClientMessage(
    val setup: LiveSetup? = null,
    val clientContent: LiveClientContent? = null,
    val realtimeInput: LiveRealtimeInput? = null,
    val toolResponse: LiveToolResponse? = null,
)

@Serializable
data class LiveSetup(
    val model: String,
    val generationConfig: LiveGenerationConfig,
    val systemInstruction: Content? = null,
    /** Empty object = "on": the server sends text transcripts of both sides. */
    val inputAudioTranscription: EmptyConfig = EmptyConfig(),
    val outputAudioTranscription: EmptyConfig = EmptyConfig(),
    val contextWindowCompression: ContextWindowCompression? = null,
    val tools: List<ToolDeclarations>? = null,
)

@Serializable
class EmptyConfig

@Serializable
data class LiveGenerationConfig(
    val responseModalities: List<String> = listOf("AUDIO"),
    val speechConfig: SpeechConfig,
)

@Serializable
data class SpeechConfig(val voiceConfig: VoiceConfig)

@Serializable
data class VoiceConfig(val prebuiltVoiceConfig: PrebuiltVoiceConfig)

@Serializable
data class PrebuiltVoiceConfig(val voiceName: String)

/** When the context reaches [triggerTokens], older turns are dropped down to targetTokens. */
@Serializable
data class ContextWindowCompression(
    val slidingWindow: SlidingWindow,
    val triggerTokens: Int,
)

@Serializable
data class SlidingWindow(val targetTokens: Int)

/** Earlier dialog turns, sent once at the start of a session. */
@Serializable
data class LiveClientContent(
    val turns: List<Content>,
    val turnComplete: Boolean = false,
)

@Serializable
data class LiveRealtimeInput(
    val audio: Blob? = null,
)

@Serializable
data class Blob(val mimeType: String, val data: String)

// ---- server → client ----

@Serializable
data class LiveServerMessage(
    val setupComplete: JsonObject? = null,
    val serverContent: LiveServerContent? = null,
    val toolCall: LiveToolCall? = null,
    val goAway: GoAway? = null,
)

@Serializable
data class LiveServerContent(
    val modelTurn: LiveModelTurn? = null,
    val inputTranscription: Transcription? = null,
    val outputTranscription: Transcription? = null,
    /** The user started talking over the model: stop playback right away. */
    val interrupted: Boolean? = null,
    val turnComplete: Boolean? = null,
)

@Serializable
data class LiveModelTurn(val parts: List<LivePart> = emptyList())

@Serializable
data class LivePart(
    val inlineData: Blob? = null,
    val text: String? = null,
)

@Serializable
data class Transcription(val text: String? = null)

/** The server will close the connection soon. */
@Serializable
data class GoAway(val timeLeft: String? = null)

/** The model wants the app to run functions; answer with [LiveToolResponse] using the same ids. */
@Serializable
data class LiveToolCall(val functionCalls: List<FunctionCall> = emptyList())

@Serializable
data class LiveToolResponse(val functionResponses: List<FunctionResponse>)
