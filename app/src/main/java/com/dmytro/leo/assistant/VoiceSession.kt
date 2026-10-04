package com.dmytro.leo.assistant

import com.dmytro.leo.brain.AgentLoop
import com.dmytro.leo.brain.AgentReply
import com.dmytro.leo.brain.GeminiError
import com.dmytro.leo.brain.toUserMessage
import com.dmytro.leo.voice.AudioFocus
import com.dmytro.leo.voice.AudioHandoff
import com.dmytro.leo.voice.LiveConversation
import com.dmytro.leo.voice.SpeechError
import com.dmytro.leo.voice.SpeechEvent
import com.dmytro.leo.voice.SpeechInput
import com.dmytro.leo.voice.SpeechOutput
import com.dmytro.leo.voice.VoiceMode
import com.dmytro.leo.voice.VoiceSettings
import com.dmytro.leo.voice.toUserMessage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** Idle → Listening → Thinking → Speaking → (Listening again if the agent asked a question) → Idle */
sealed interface VoiceState {
    data object Idle : VoiceState
    data class Listening(val partial: String = "", val level: Float = 0f) : VoiceState
    data class Thinking(val heard: String) : VoiceState
    data class Speaking(val text: String) : VoiceState
}

/** Keeps the app allowed to use the microphone while another app is on screen. */
interface ForegroundKeeper {
    fun start()
    fun stop()

    object None : ForegroundKeeper {
        override fun start() = Unit
        override fun stop() = Unit
    }
}

/**
 * One voice conversation at a time, owned by the app (not by a screen): it can be started
 * from the chat, the widget, the assistant overlay or the Quick Settings tile, and it keeps
 * going while Maps or a messenger is open.
 */
class VoiceSession(
    private val agent: AgentLoop,
    private val chatLog: ChatLog,
    private val speechInput: SpeechInput,
    private val speaker: SpeechOutput,
    private val audioFocus: AudioFocus,
    private val live: LiveConversation,
    private val voiceSettings: VoiceSettings,
    private val scope: CoroutineScope,
    private val foreground: ForegroundKeeper = ForegroundKeeper.None,
    private val audioHandoff: AudioHandoff = AudioHandoff(),
) {
    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    /** Short one-off note, e.g. "I didn't hear anything". */
    private val _hint = MutableStateFlow<String?>(null)
    val hint: StateFlow<String?> = _hint.asStateFlow()

    private var job: Job? = null

    val isActive: Boolean get() = job?.isActive == true

    /** Must be called while one of our screens is visible (Android lets only those start the mic service). */
    /** [openingPhrase]: what the user already said to start it (the wake phrase), sent to the model first. */
    fun start(openingPhrase: String? = null) {
        if (isActive) return
        _hint.value = null
        foreground.start()
        audioHandoff.acquire()
        job = scope.launch {
            try {
                when (voiceSettings.mode.value) {
                    VoiceMode.Live -> runLive(openingPhrase)
                    VoiceMode.Classic -> runClassic()
                }
            } finally {
                // Speaker and mic are released by now: a confirmed call can start
                audioHandoff.release()
                _state.value = VoiceState.Idle
                foreground.stop()
            }
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    fun toggle() = if (isActive) stop() else start()

    fun showHint(text: String?) {
        _hint.value = text
    }

    /** A spoken conversation with the Live model; transcripts of both sides appear in the chat. */
    private suspend fun runLive(openingPhrase: String?) {
        var fallBackToClassic = false
        audioFocus.acquire()
        try {
            _state.value = VoiceState.Listening()
            var userMessageId: Long? = null
            var agentMessageId: Long? = null

            var phrase = openingPhrase
            // The server limits how long one connection lives; when it drops a working session,
            // reconnect with the conversation so far — the user only ends it themselves
            do {
                val session = LiveConversation.Session(
                    voiceName = voiceSettings.liveVoice.value,
                    systemPrompt = agent.systemPrompt(),
                    history = agent.history(),
                    tools = agent.toolDeclarations,
                    runTool = agent::runTool,
                    onUserTurn = agent::noteUserTurn,
                    openingPhrase = phrase,
                )
                phrase = null
                val end = live.run(session) { update ->
                    when (update) {
                        is LiveConversation.Update.Listening -> _state.value = VoiceState.Listening(level = update.level)
                        LiveConversation.Update.Speaking ->
                            if (_state.value !is VoiceState.Speaking) _state.value = VoiceState.Speaking("")
                        is LiveConversation.Update.UserTranscript ->
                            userMessageId = chatLog.upsert(userMessageId, ChatMessage.Author.User, update.text)
                        is LiveConversation.Update.AgentTranscript ->
                            agentMessageId = chatLog.upsert(agentMessageId, ChatMessage.Author.Agent, update.text)
                        is LiveConversation.Update.ToolUsed -> chatLog.append(ChatMessage.Author.Tool, ChatLog.describe(update.action))
                        is LiveConversation.Update.TurnFinished -> {
                            // Text mode (and the next connection) will know what was said by voice
                            scope.launch { agent.remember(update.user, update.agent) }
                            userMessageId = null
                            agentMessageId = null
                        }
                        is LiveConversation.Update.Failed -> {
                            val canFallBack = update.beforeReady &&
                                update.error != GeminiError.NoNetwork && update.error != GeminiError.InvalidApiKey
                            if (canFallBack) {
                                fallBackToClassic = true
                                _hint.value = "Live недоступний: ${update.error.toUserMessage()} Перемикаюсь на звичайний режим."
                            } else {
                                chatLog.append(ChatMessage.Author.Error, update.error.toUserMessage())
                            }
                        }
                    }
                }
            } while (end == LiveConversation.End.Dropped)
        } finally {
            audioFocus.release()
        }
        if (fallBackToClassic) runClassic()
    }

    /** SpeechRecognizer → text model → TextToSpeech; relistens when the answer is a question. */
    private suspend fun runClassic() {
        audioFocus.acquire()
        try {
            var listenAgain = true
            var isFollowUp = false
            while (listenAgain) {
                val heard = listenOnce(quietIfNothingHeard = isFollowUp) ?: break
                chatLog.append(ChatMessage.Author.User, heard)

                _state.value = VoiceState.Thinking(heard)
                val reply = agent.handle(heard)
                val answer = when (reply) {
                    is AgentReply.Text -> reply.text.also {
                        chatLog.addActions(reply.actions)
                        chatLog.append(ChatMessage.Author.Agent, it)
                    }
                    is AgentReply.Error -> reply.message.also {
                        chatLog.addActions(reply.actions)
                        chatLog.append(ChatMessage.Author.Error, it)
                    }
                }

                _state.value = VoiceState.Speaking(answer)
                speaker.speak(answer)

                // A clarifying question ("Якому саме Олегу?") → open the mic again automatically
                listenAgain = reply is AgentReply.Text && answer.trimEnd().endsWith("?")
                isFollowUp = true
            }
        } finally {
            audioFocus.release()
        }
    }

    /** One listening session. Returns the recognized phrase, or null if nothing usable was heard. */
    private suspend fun listenOnce(quietIfNothingHeard: Boolean): String? {
        _state.value = VoiceState.Listening()
        var heard: String? = null
        speechInput.listen().collect { event ->
            when (event) {
                is SpeechEvent.Partial -> updateListening { it.copy(partial = event.text) }
                is SpeechEvent.Level -> updateListening { it.copy(level = event.rmsDb) }
                is SpeechEvent.Final -> heard = event.text.trim().takeIf { it.isNotEmpty() }
                is SpeechEvent.Error -> {
                    // After a follow-up question silence just means "done", no need to complain
                    if (!(quietIfNothingHeard && event.error == SpeechError.NoSpeech)) {
                        _hint.value = event.error.toUserMessage()
                    }
                }
            }
        }
        return heard
    }

    private fun updateListening(change: (VoiceState.Listening) -> VoiceState.Listening) =
        _state.update { if (it is VoiceState.Listening) change(it) else it }

    companion object {
        /** Sent first when a conversation is started by voice, so the model starts in Ukrainian. */
        const val WAKE_GREETING = "Привіт, Leo!"
    }
}
