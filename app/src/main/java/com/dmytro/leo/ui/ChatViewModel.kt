package com.dmytro.leo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dmytro.leo.LeoApp
import com.dmytro.leo.brain.AgentLoop
import com.dmytro.leo.brain.AgentReply
import com.dmytro.leo.voice.AudioFocus
import com.dmytro.leo.voice.SpeakerStatus
import com.dmytro.leo.voice.SpeechError
import com.dmytro.leo.voice.SpeechEvent
import com.dmytro.leo.voice.SpeechInput
import com.dmytro.leo.voice.SpeechOutput
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
) {
    val isBusy: Boolean get() = isThinking || voice != VoiceState.Idle
}

/** Holds the main screen state (text chat + voice) and survives screen rotation. */
class ChatViewModel(
    private val agent: AgentLoop,
    private val speechInput: SpeechInput,
    private val speaker: SpeechOutput,
    private val audioFocus: AudioFocus,
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var nextId = 0L
    private var voiceJob: Job? = null

    init {
        viewModelScope.launch {
            speaker.status.collect { status -> _state.update { it.copy(speakerStatus = status) } }
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
                voiceJob = viewModelScope.launch { runVoiceExchange() }
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
            _state.update { ChatUiState(speakerStatus = it.speakerStatus) }
        }
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

    private fun append(author: ChatMessage.Author, text: String) {
        _state.update { it.copy(messages = it.messages + ChatMessage(nextId++, author, text)) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val container = (this[APPLICATION_KEY] as LeoApp).container
                ChatViewModel(
                    agent = container.agentLoop,
                    speechInput = container.speechInput,
                    speaker = container.speaker,
                    audioFocus = container.audioFocus,
                )
            }
        }
    }
}
