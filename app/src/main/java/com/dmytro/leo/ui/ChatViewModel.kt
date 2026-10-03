package com.dmytro.leo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dmytro.leo.LeoApp
import com.dmytro.leo.brain.AgentLoop
import com.dmytro.leo.brain.AgentReply
import com.dmytro.leo.brain.GeminiError
import com.dmytro.leo.brain.toUserMessage
import com.dmytro.leo.voice.AudioFocus
import com.dmytro.leo.voice.LiveConversation
import com.dmytro.leo.voice.SpeakerStatus
import com.dmytro.leo.voice.SpeechError
import com.dmytro.leo.voice.SpeechEvent
import com.dmytro.leo.voice.SpeechInput
import com.dmytro.leo.voice.SpeechOutput
import com.dmytro.leo.voice.TtsVoice
import com.dmytro.leo.voice.VoiceMode
import com.dmytro.leo.voice.VoiceSettings
import com.dmytro.leo.voice.toUserMessage
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(val id: Long, val author: Author, val text: String) {
    enum class Author { User, Agent, Error }
}

/** Idle → Listening → Thinking → Speaking → (Listening again if the agent asked a question) → Idle */
sealed interface VoiceState {
    data object Idle : VoiceState
    data class Listening(val partial: String = "", val level: Float = 0f) : VoiceState
    data class Thinking(val heard: String) : VoiceState
    data class Speaking(val text: String) : VoiceState
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    /** A typed message is being answered. */
    val isThinking: Boolean = false,
    val voice: VoiceState = VoiceState.Idle,
    /** Short one-off note shown near the input, e.g. "I didn't hear anything". */
    val hint: String? = null,
    val speakerStatus: SpeakerStatus = SpeakerStatus.Initializing,
    val voices: List<TtsVoice> = emptyList(),
    val currentVoice: TtsVoice? = null,
    val voiceMode: VoiceMode = VoiceMode.Live,
    val liveVoice: String = VoiceSettings.DEFAULT_LIVE_VOICE,
) {
    val isBusy: Boolean get() = isThinking || voice != VoiceState.Idle
}

/** Holds the main screen state (text chat + voice) and survives screen rotation. */
class ChatViewModel(
    private val agent: AgentLoop,
    private val speechInput: SpeechInput,
    private val speaker: SpeechOutput,
    private val audioFocus: AudioFocus,
    private val live: LiveConversation,
    private val voiceSettings: VoiceSettings,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var nextId = 0L
    private var voiceJob: Job? = null

    init {
        viewModelScope.launch {
            speaker.status.collect { status -> _state.update { it.copy(speakerStatus = status) } }
        }
        viewModelScope.launch {
            speaker.voices.collect { voices -> _state.update { it.copy(voices = voices) } }
        }
        viewModelScope.launch {
            speaker.currentVoice.collect { voice -> _state.update { it.copy(currentVoice = voice) } }
        }
        viewModelScope.launch {
            voiceSettings.mode.collect { mode -> _state.update { it.copy(voiceMode = mode) } }
        }
        viewModelScope.launch {
            voiceSettings.liveVoice.collect { voice -> _state.update { it.copy(liveVoice = voice) } }
        }
    }

    /** Typed message: the answer is shown, not spoken. */
    fun send(text: String) {
        val phrase = text.trim()
        if (phrase.isEmpty() || _state.value.isBusy) return

        append(ChatMessage.Author.User, phrase)
        _state.update { it.copy(isThinking = true, hint = null) }

        viewModelScope.launch {
            when (val reply = agent.handle(phrase)) {
                is AgentReply.Text -> append(ChatMessage.Author.Agent, reply.text)
                is AgentReply.Error -> append(ChatMessage.Author.Error, reply.message)
            }
            _state.update { it.copy(isThinking = false) }
        }
    }

    /** Mic button: starts a voice exchange, or stops the current one. */
    fun onMicClick() {
        when {
            _state.value.voice != VoiceState.Idle -> stopVoice()
            !_state.value.isThinking -> {
                _state.update { it.copy(hint = null) }
                voiceJob = viewModelScope.launch {
                    when (voiceSettings.mode.value) {
                        VoiceMode.Live -> runLiveSession()
                        VoiceMode.Classic -> runVoiceExchange()
                    }
                }
            }
        }
    }

    fun onMicPermissionDenied() {
        _state.update { it.copy(hint = "Без дозволу на мікрофон я тебе не почую. Його можна дати в налаштуваннях додатка.") }
    }

    fun stopVoice() {
        voiceJob?.cancel()
        voiceJob = null
    }

    fun clear() {
        if (_state.value.isBusy) return
        viewModelScope.launch {
            agent.reset()
            _state.update { it.copy(messages = emptyList(), hint = null) }
        }
    }

    /** Live mode with the given voice for the next conversations. */
    fun selectLiveVoice(voiceName: String) {
        if (_state.value.isBusy) return
        voiceSettings.useLive(voiceName)
    }

    /** Classic mode with this TTS voice; plays a sample phrase with it. */
    fun previewVoice(voice: TtsVoice) {
        if (_state.value.isBusy) return
        voiceSettings.useClassic()
        speaker.selectVoice(voice)
        voiceJob = viewModelScope.launch {
            setVoice(VoiceState.Speaking(VOICE_SAMPLE))
            try {
                speaker.speak(VOICE_SAMPLE)
            } finally {
                setVoice(VoiceState.Idle)
            }
        }
    }

    /** A spoken conversation with the Live model; transcripts of both sides appear in the chat. */
    private suspend fun runLiveSession() {
        var fallBackToClassic = false
        audioFocus.acquire()
        try {
            setVoice(VoiceState.Listening())
            var userMessageId: Long? = null
            var agentMessageId: Long? = null

            live.run(voiceSettings.liveVoice.value, agent.history()) { update ->
                when (update) {
                    is LiveConversation.Update.Listening -> setVoice(VoiceState.Listening(level = update.level))
                    LiveConversation.Update.Speaking ->
                        if (_state.value.voice !is VoiceState.Speaking) setVoice(VoiceState.Speaking(""))
                    is LiveConversation.Update.UserTranscript ->
                        userMessageId = upsert(userMessageId, ChatMessage.Author.User, update.text)
                    is LiveConversation.Update.AgentTranscript ->
                        agentMessageId = upsert(agentMessageId, ChatMessage.Author.Agent, update.text)
                    is LiveConversation.Update.TurnFinished -> {
                        // Text mode will know what was said by voice
                        viewModelScope.launch { agent.remember(update.user, update.agent) }
                        userMessageId = null
                        agentMessageId = null
                    }
                    is LiveConversation.Update.Failed -> {
                        val canFallBack = update.beforeReady &&
                            update.error != GeminiError.NoNetwork && update.error != GeminiError.InvalidApiKey
                        if (canFallBack) {
                            fallBackToClassic = true
                            _state.update { it.copy(hint = "Live недоступний: ${update.error.toUserMessage()} Перемикаюсь на звичайний режим.") }
                        } else {
                            append(ChatMessage.Author.Error, update.error.toUserMessage())
                        }
                    }
                }
            }
        } finally {
            audioFocus.release()
            setVoice(VoiceState.Idle)
        }
        if (fallBackToClassic) runVoiceExchange()
    }

    /** Appends a message, or replaces the text of the one with [id]. Returns the message id. */
    private fun upsert(id: Long?, author: ChatMessage.Author, text: String): Long {
        if (id == null) return append(author, text)
        _state.update { state ->
            state.copy(messages = state.messages.map { if (it.id == id) it.copy(text = text) else it })
        }
        return id
    }

    private suspend fun runVoiceExchange() {
        audioFocus.acquire()
        try {
            var listenAgain = true
            var isFollowUp = false
            while (listenAgain) {
                val heard = listenOnce(quietIfNothingHeard = isFollowUp) ?: break
                append(ChatMessage.Author.User, heard)

                setVoice(VoiceState.Thinking(heard))
                val reply = agent.handle(heard)
                val answer = when (reply) {
                    is AgentReply.Text -> reply.text.also { append(ChatMessage.Author.Agent, it) }
                    is AgentReply.Error -> reply.message.also { append(ChatMessage.Author.Error, it) }
                }

                setVoice(VoiceState.Speaking(answer))
                speaker.speak(answer)

                // A clarifying question ("Якому саме Олегу?") → open the mic again automatically
                listenAgain = reply is AgentReply.Text && answer.trimEnd().endsWith("?")
                isFollowUp = true
            }
        } finally {
            audioFocus.release()
            setVoice(VoiceState.Idle)
        }
    }

    /** One listening session. Returns the recognized phrase, or null if nothing usable was heard. */
    private suspend fun listenOnce(quietIfNothingHeard: Boolean): String? {
        setVoice(VoiceState.Listening())
        var heard: String? = null
        speechInput.listen().collect { event ->
            when (event) {
                is SpeechEvent.Partial -> updateListening { it.copy(partial = event.text) }
                is SpeechEvent.Level -> updateListening { it.copy(level = event.rmsDb) }
                is SpeechEvent.Final -> heard = event.text.trim().takeIf { it.isNotEmpty() }
                is SpeechEvent.Error -> {
                    // After a follow-up question silence just means "done", no need to complain
                    if (!(quietIfNothingHeard && event.error == SpeechError.NoSpeech)) {
                        _state.update { it.copy(hint = event.error.toUserMessage()) }
                    }
                }
            }
        }
        return heard
    }

    private fun setVoice(voice: VoiceState) = _state.update { it.copy(voice = voice) }

    private fun updateListening(change: (VoiceState.Listening) -> VoiceState.Listening) =
        _state.update { state ->
            val voice = state.voice
            if (voice is VoiceState.Listening) state.copy(voice = change(voice)) else state
        }

    private fun append(author: ChatMessage.Author, text: String): Long {
        val id = nextId++
        _state.update { it.copy(messages = it.messages + ChatMessage(id, author, text)) }
        return id
    }

    companion object {
        private const val VOICE_SAMPLE = "Привіт! Я Лео. Так звучить мій голос — подобається?"

        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as LeoApp).container
                ChatViewModel(
                    agent = container.agentLoop,
                    speechInput = container.speechInput,
                    speaker = container.speaker,
                    audioFocus = container.audioFocus,
                    live = container.liveConversation,
                    voiceSettings = container.voiceSettings,
                )
            }
        }
    }
}
