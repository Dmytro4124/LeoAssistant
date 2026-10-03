package com.dmytro.leo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dmytro.leo.LeoApp
import com.dmytro.leo.brain.AgentLoop
import com.dmytro.leo.brain.AgentReply
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatMessage(val id: Long, val author: Author, val text: String) {
    enum class Author { User, Agent, Error }
}

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isThinking: Boolean = false,
)

/** Holds the chat screen state and survives screen rotation. */
class ChatViewModel(private val agent: AgentLoop) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    private var nextId = 0L

    fun send(text: String) {
        val phrase = text.trim()
        if (phrase.isEmpty() || _state.value.isThinking) return

        append(ChatMessage.Author.User, phrase)
        _state.update { it.copy(isThinking = true) }

        viewModelScope.launch {
            when (val reply = agent.handle(phrase)) {
                is AgentReply.Text -> append(ChatMessage.Author.Agent, reply.text)
                is AgentReply.Error -> append(ChatMessage.Author.Error, reply.message)
            }
            _state.update { it.copy(isThinking = false) }
        }
    }

    fun clear() {
        if (_state.value.isThinking) return
        viewModelScope.launch {
            agent.reset()
            _state.value = ChatUiState()
        }
    }

    private fun append(author: ChatMessage.Author, text: String) {
        _state.update { it.copy(messages = it.messages + ChatMessage(nextId++, author, text)) }
    }

    companion object {
        val Factory = viewModelFactory {
            initializer {
                val app = this[APPLICATION_KEY] as LeoApp
                ChatViewModel(app.container.agentLoop)
            }
        }
    }
}
