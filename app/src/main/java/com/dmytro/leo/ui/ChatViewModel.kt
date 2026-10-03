package com.dmytro.leo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dmytro.leo.LeoApp
import com.dmytro.leo.assistant.ChatLog
import com.dmytro.leo.assistant.ChatMessage
import com.dmytro.leo.assistant.VoiceSession
import com.dmytro.leo.assistant.VoiceState
import com.dmytro.leo.brain.AgentLoop
import com.dmytro.leo.brain.AgentReply
import com.dmytro.leo.voice.SpeakerStatus
import com.dmytro.leo.voice.SpeechOutput
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    /** A typed message is being answered. */
    val isThinking: Boolean = false,
    val voice: VoiceState = VoiceState.Idle,
    /** Short one-off note shown near the input, e.g. "I didn't hear anything". */
    val hint: String? = null,
    val speakerStatus: SpeakerStatus = SpeakerStatus.Initializing,
    /** Text to put into the input field once (e.g. a link shared from another app). */
    val prefill: String? = null,
) {
    val isBusy: Boolean get() = isThinking || voice != VoiceState.Idle
}

/** The chat screen: typed messages here; voice runs in the app-wide [VoiceSession]. */
class ChatViewModel(
    private val agent: AgentLoop,
    private val chatLog: ChatLog,
    private val voice: VoiceSession,
    speaker: SpeechOutput,
) : ViewModel() {

    private data class Local(val isThinking: Boolean = false, val prefill: String? = null)

    private val local = MutableStateFlow(Local())

    val state: StateFlow<ChatUiState> =
        combine(chatLog.messages, voice.state, voice.hint, speaker.status, local) { messages, voiceState, hint, speakerStatus, l ->
            ChatUiState(messages, l.isThinking, voiceState, hint, speakerStatus, l.prefill)
        }.stateIn(viewModelScope, SharingStarted.Eagerly, ChatUiState())

    /** Typed message: the answer is shown, not spoken. */
    fun send(text: String) {
        val phrase = text.trim()
        if (phrase.isEmpty() || state.value.isBusy) return

        chatLog.append(ChatMessage.Author.User, phrase)
        local.update { it.copy(isThinking = true) }
        voice.showHint(null)

        viewModelScope.launch {
            when (val reply = agent.handle(phrase)) {
                is AgentReply.Text -> {
                    chatLog.addActions(reply.actions)
                    chatLog.append(ChatMessage.Author.Agent, reply.text)
                }
                is AgentReply.Error -> {
                    chatLog.addActions(reply.actions)
                    chatLog.append(ChatMessage.Author.Error, reply.message)
                }
            }
            local.update { it.copy(isThinking = false) }
        }
    }

    /** Mic button: starts a voice conversation, or stops the current one. */
    fun onMicClick() {
        when {
            voice.isActive -> voice.stop()
            !state.value.isThinking -> voice.start()
        }
    }

    fun stopVoice() = voice.stop()

    fun onMicPermissionDenied() =
        voice.showHint("Без дозволу на мікрофон я тебе не почую. Його можна дати в налаштуваннях додатка.")

    fun prefillInput(text: String) = local.update { it.copy(prefill = text) }

    fun prefillConsumed() = local.update { it.copy(prefill = null) }

    fun clear() {
        if (state.value.isBusy) return
        voice.showHint(null)
        viewModelScope.launch { chatLog.clear() }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val c = (this[APPLICATION_KEY] as LeoApp).container
                ChatViewModel(c.agentLoop, c.chatLog, c.voiceSession, c.speaker)
            }
        }
    }
}
