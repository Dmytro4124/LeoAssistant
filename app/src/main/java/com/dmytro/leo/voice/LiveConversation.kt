package com.dmytro.leo.voice

import com.dmytro.leo.brain.AgentConfig
import com.dmytro.leo.brain.Content
import com.dmytro.leo.brain.FunctionResponse
import com.dmytro.leo.brain.GeminiError
import com.dmytro.leo.brain.ToolAction
import com.dmytro.leo.brain.ToolDeclarations
import com.dmytro.leo.brain.live.ContextWindowCompression
import com.dmytro.leo.brain.live.LiveApi
import com.dmytro.leo.brain.live.LiveEvent
import com.dmytro.leo.brain.live.LiveGenerationConfig
import com.dmytro.leo.brain.live.LiveSetup
import com.dmytro.leo.brain.live.PrebuiltVoiceConfig
import com.dmytro.leo.brain.live.SlidingWindow
import com.dmytro.leo.brain.live.SpeechConfig
import com.dmytro.leo.brain.live.VoiceConfig
import com.dmytro.leo.tools.ToolResult
import kotlinx.serialization.json.JsonObject
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.log10
import kotlin.math.sqrt

/**
 * One spoken conversation with the Live model: the microphone streams to Gemini, its
 * voice streams to the speaker, and both sides arrive as text transcripts for the chat.
 * Silence does not end it: only cancelling [run]'s caller (the stop button), or an action
 * that ends the conversation by itself (music, navigation, a call, "бувай" → end_conversation).
 */
class LiveConversation(
    private val live: LiveApi,
    private val mic: MicStream,
    private val player: PcmPlayer,
    private val now: () -> Long = System::currentTimeMillis,
) {

    /** How [run] ended. [Dropped]: the server closed a working session (time limit) — reconnect to go on. */
    enum class End { Finished, Dropped }

    sealed interface Update {
        /** The model is listening; [level] is the microphone loudness in dB (about -2..10). */
        data class Listening(val level: Float) : Update
        data object Speaking : Update
        /** Text of the user's current turn so far. */
        data class UserTranscript(val text: String) : Update
        /** Text of the model's current turn so far. */
        data class AgentTranscript(val text: String) : Update
        data class TurnFinished(val user: String, val agent: String) : Update
        /** A tool was run at the model's request. */
        data class ToolUsed(val action: ToolAction) : Update
        /** [beforeReady] = the session never started, so another voice mode can be tried. */
        data class Failed(val error: GeminiError, val beforeReady: Boolean) : Update
    }

    /** What a session needs from the agent: the same prompt and tools as the text chat. */
    class Session(
        val voiceName: String,
        val systemPrompt: String,
        val history: List<Content>,
        val tools: List<ToolDeclarations>,
        val runTool: suspend (name: String, args: JsonObject) -> ToolResult,
        /** Called when the user starts a new phrase (counts as an answer to a confirmation). */
        val onUserTurn: () -> Unit = {},
        /** Said by the user before the session opened (the wake phrase): sent right away, so the
         *  model starts in Ukrainian and is ready, instead of waiting in silence. */
        val openingPhrase: String? = null,
    )

    suspend fun run(session: Session, onUpdate: (Update) -> Unit): End = coroutineScope {
        val connection = live.connect(setup(session))
        player.start()

        val userText = StringBuilder()
        val agentText = StringBuilder()
        var ready = false
        var lastActivity = now()
        var micJob: Job? = null
        var closedByUs = false
        var failed = false

        // After music or navigation starts, the session ends once the model has confirmed it
        var endAfterAction = false
        var confirmedAfterAction = false
        var spokeAfterAction = false

        fun finishTurn() {
            val user = userText.toString().trim()
            val agent = agentText.toString().trim()
            if (user.isNotEmpty() || agent.isNotEmpty()) onUpdate(Update.TurnFinished(user, agent))
            userText.clear()
            agentText.clear()
        }

        // Switches the UI back to "listening" when playback ends, and ends the session after an action
        val watchdog = launch {
            var wasSpeaking = false
            while (isActive) {
                delay(200)
                val speaking = player.isSpeaking
                if (speaking) lastActivity = now()
                if (wasSpeaking && !speaking) onUpdate(Update.Listening(0f))
                wasSpeaking = speaking
                val actionDone = endAfterAction && !speaking &&
                    ((spokeAfterAction && confirmedAfterAction) || now() - lastActivity > CONFIRM_WAIT_MS)
                if (actionDone) {
                    closedByUs = true
                    connection.close()
                    break
                }
            }
        }

        try {
            connection.events.collect { event ->
                when (event) {
                    LiveEvent.Ready -> {
                        ready = true
                        lastActivity = now()
                        connection.sendHistory(session.history)
                        session.openingPhrase?.let { phrase ->
                            userText.append(phrase)
                            onUpdate(Update.UserTranscript(phrase))
                            connection.sendText(phrase)
                        }
                        onUpdate(Update.Listening(0f))
                        micJob = launch {
                            mic.stream().collect { chunk ->
                                connection.sendAudio(chunk)
                                if (!player.isSpeaking) onUpdate(Update.Listening(loudnessDb(chunk)))
                            }
                        }
                    }
                    is LiveEvent.InputTranscript -> {
                        // A new user phrase after the model's answer starts a new turn
                        if (agentText.isNotEmpty()) finishTurn()
                        if (userText.isEmpty()) session.onUserTurn()
                        userText.append(event.text)
                        lastActivity = now()
                        onUpdate(Update.UserTranscript(userText.toString().trim()))
                    }
                    is LiveEvent.Audio -> {
                        player.play(event.pcm)
                        if (endAfterAction) spokeAfterAction = true
                        lastActivity = now()
                        onUpdate(Update.Speaking)
                    }
                    is LiveEvent.OutputTranscript -> {
                        agentText.append(event.text)
                        onUpdate(Update.AgentTranscript(agentText.toString().trim()))
                    }
                    LiveEvent.Interrupted -> {
                        player.interrupt()
                        onUpdate(Update.Listening(0f))
                    }
                    LiveEvent.TurnComplete -> {
                        finishTurn()
                        if (spokeAfterAction) confirmedAfterAction = true
                        lastActivity = now()
                    }
                    is LiveEvent.ToolCall -> {
                        lastActivity = now()
                        val responses = event.calls.map { call ->
                            val result = session.runTool(call.name, call.args)
                            onUpdate(Update.ToolUsed(ToolAction(call.name, call.args, result)))
                            if (result is ToolResult.Success && result.endsConversation) endAfterAction = true
                            FunctionResponse(call.name, result.toJson(), call.id)
                        }
                        connection.sendToolResponse(responses)
                    }
                    LiveEvent.GoAway -> connection.close()
                    is LiveEvent.Closed -> {
                        event.error?.let {
                            failed = true
                            onUpdate(Update.Failed(it, beforeReady = !ready))
                        }
                    }
                }
            }
            // Let the last answer finish playing before closing the speaker
            while (player.isSpeaking) delay(100)
            if (ready && !closedByUs && !failed) End.Dropped else End.Finished
        } finally {
            finishTurn()
            micJob?.cancel()
            watchdog.cancel()
            connection.close()
            player.stop()
        }
    }

    private fun setup(session: Session) = LiveSetup(
        model = "models/${AgentConfig.LIVE_MODEL}",
        generationConfig = LiveGenerationConfig(
            speechConfig = SpeechConfig(VoiceConfig(PrebuiltVoiceConfig(session.voiceName)), languageCode = "uk-UA"),
        ),
        systemInstruction = Content.system(session.systemPrompt),
        tools = session.tools.ifEmpty { null },
        // Keeps long conversations under the free-tier tokens-per-minute limit
        contextWindowCompression = ContextWindowCompression(
            slidingWindow = SlidingWindow(targetTokens = 16_000),
            triggerTokens = 24_000,
        ),
    )

    private companion object {
        /** How long to wait for the spoken confirmation of an action before closing anyway. */
        const val CONFIRM_WAIT_MS = 5_000L
    }

    /** RMS loudness of 16-bit PCM, scaled like SpeechRecognizer's rmsdB (about -2..10). */
    private fun loudnessDb(pcm: ByteArray): Float {
        var sum = 0.0
        val samples = pcm.size / 2
        for (i in 0 until samples) {
            val sample = (pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)
            sum += sample.toDouble() * sample
        }
        val rms = sqrt(sum / maxOf(samples, 1))
        return (20 * log10(maxOf(rms, 1.0)) / 6 - 3).toFloat()
    }
}
