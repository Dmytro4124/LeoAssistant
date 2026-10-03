package com.dmytro.leo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import com.dmytro.leo.LeoApp
import com.dmytro.leo.data.ChatStore
import com.dmytro.leo.data.ContactAliases
import com.dmytro.leo.data.ContactsSource
import com.dmytro.leo.data.PlacesStore
import com.dmytro.leo.data.PlaylistStore
import com.dmytro.leo.voice.SpeechOutput
import com.dmytro.leo.voice.TtsVoice
import com.dmytro.leo.voice.VoiceMode
import com.dmytro.leo.voice.VoiceSettings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Something the user taught Лео, shown in Settings with a delete button. */
data class SavedItem(val name: String, val detail: String)

data class SettingsUiState(
    val voiceMode: VoiceMode = VoiceMode.Live,
    val liveVoice: String = VoiceSettings.DEFAULT_LIVE_VOICE,
    val ttsVoices: List<TtsVoice> = emptyList(),
    val currentTtsVoice: TtsVoice? = null,
    val places: List<SavedItem> = emptyList(),
    val playlists: List<SavedItem> = emptyList(),
    val chats: List<SavedItem> = emptyList(),
    val contactAliases: List<SavedItem> = emptyList(),
)

class SettingsViewModel(
    private val voiceSettings: VoiceSettings,
    private val speaker: SpeechOutput,
    private val places: PlacesStore,
    private val playlists: PlaylistStore,
    private val chats: ChatStore,
    private val aliases: ContactAliases,
    private val contacts: ContactsSource,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { voiceSettings.mode.collect { m -> _state.update { it.copy(voiceMode = m) } } }
        viewModelScope.launch { voiceSettings.liveVoice.collect { v -> _state.update { it.copy(liveVoice = v) } } }
        viewModelScope.launch { speaker.voices.collect { v -> _state.update { it.copy(ttsVoices = v) } } }
        viewModelScope.launch { speaker.currentVoice.collect { v -> _state.update { it.copy(currentTtsVoice = v) } } }
        refresh()
    }

    /** Re-reads the saved items (they change from the chat, by voice). */
    fun refresh() {
        viewModelScope.launch {
            val contactNames = withContext(Dispatchers.IO) {
                try {
                    contacts.all().associate { it.id to it.name }
                } catch (e: SecurityException) {
                    emptyMap()
                }
            }
            _state.update { state ->
                state.copy(
                    places = places.all().map { SavedItem(it.key, it.value) },
                    playlists = playlists.all().keys.map { SavedItem(it, "YouTube Music") },
                    chats = chats.all().map { SavedItem(it.key, it.value.messenger.name) },
                    contactAliases = aliases.all().map { (alias, target) ->
                        val detail = when {
                            target.startsWith("contact:") -> target.removePrefix("contact:").toLongOrNull()
                                ?.let { contactNames[it] } ?: "контакт"
                            else -> target.removePrefix("phone:")
                        }
                        SavedItem(alias, detail)
                    }.sortedBy { it.name },
                )
            }
        }
    }

    fun useLive(voiceName: String) = voiceSettings.useLive(voiceName)

    /** Classic mode with this TTS voice; says a sample phrase so it can be heard. */
    fun useClassic(voice: TtsVoice) {
        voiceSettings.useClassic()
        speaker.selectVoice(voice)
        viewModelScope.launch { speaker.speak(VOICE_SAMPLE) }
    }

    fun removePlace(name: String) = places.remove(name).also { refresh() }
    fun removePlaylist(name: String) = playlists.remove(name).also { refresh() }
    fun removeChat(name: String) = chats.remove(name).also { refresh() }
    fun removeAlias(name: String) = aliases.remove(name).also { refresh() }

    companion object {
        private const val VOICE_SAMPLE = "Привіт! Я Лео. Так звучить мій голос."

        val Factory = viewModelFactory {
            initializer {
                val c = (this[APPLICATION_KEY] as LeoApp).container
                SettingsViewModel(c.voiceSettings, c.speaker, c.places, c.playlists, c.chats, c.contactAliases, c.contacts)
            }
        }
    }
}
