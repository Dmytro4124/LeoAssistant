package com.dmytro.leo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.content.Context
import com.dmytro.leo.LeoApp
import com.dmytro.leo.data.AboutMeStore
import com.dmytro.leo.data.ChatStore
import com.dmytro.leo.data.ContactAliases
import com.dmytro.leo.data.ContactsSource
import com.dmytro.leo.data.PlacesStore
import com.dmytro.leo.data.PlaylistStore
import com.dmytro.leo.voice.SpeechOutput
import com.dmytro.leo.voice.TtsVoice
import com.dmytro.leo.voice.VoiceMode
import com.dmytro.leo.voice.VoiceSettings
import com.dmytro.leo.wake.WakeModel
import com.dmytro.leo.wake.WakeSensitivity
import com.dmytro.leo.wake.WakeWordService
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The "Привіт, Лео" switch: off, on, or downloading the offline model first. */
sealed interface WakeUi {
    data object Off : WakeUi
    data object On : WakeUi
    data class Downloading(val progress: Float) : WakeUi
    data class Failed(val message: String) : WakeUi
}

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
    val aboutMe: String = "",
    val wake: WakeUi = WakeUi.Off,
    val wakeSensitivity: WakeSensitivity = WakeSensitivity.Medium,
)

class SettingsViewModel(
    private val voiceSettings: VoiceSettings,
    private val speaker: SpeechOutput,
    private val places: PlacesStore,
    private val playlists: PlaylistStore,
    private val chats: ChatStore,
    private val aliases: ContactAliases,
    private val contacts: ContactsSource,
    private val aboutMe: AboutMeStore,
    private val wakeModel: WakeModel,
    private val appContext: Context,
) : ViewModel() {

    private val _state = MutableStateFlow(SettingsUiState())
    val state: StateFlow<SettingsUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch { voiceSettings.mode.collect { m -> _state.update { it.copy(voiceMode = m) } } }
        viewModelScope.launch { voiceSettings.liveVoice.collect { v -> _state.update { it.copy(liveVoice = v) } } }
        viewModelScope.launch { speaker.voices.collect { v -> _state.update { it.copy(ttsVoices = v) } } }
        viewModelScope.launch { speaker.currentVoice.collect { v -> _state.update { it.copy(currentTtsVoice = v) } } }
        _state.update {
            it.copy(
                wake = if (WakeWordService.isEnabled(appContext) && wakeModel.isReady) WakeUi.On else WakeUi.Off,
                wakeSensitivity = WakeWordService.sensitivity(appContext),
            )
        }
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
                    aboutMe = aboutMe.get(),
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

    /** Turning on downloads the offline model the first time (~78 MB), then starts listening. */
    fun setWake(enabled: Boolean) {
        if (!enabled) {
            WakeWordService.setEnabled(appContext, false)
            WakeWordService.stop(appContext)
            _state.update { it.copy(wake = WakeUi.Off) }
            return
        }
        if (_state.value.wake is WakeUi.Downloading) return
        viewModelScope.launch {
            if (!wakeModel.isReady) {
                _state.update { it.copy(wake = WakeUi.Downloading(0f)) }
                try {
                    wakeModel.download { p -> _state.update { it.copy(wake = WakeUi.Downloading(p)) } }
                } catch (e: IOException) {
                    _state.update { it.copy(wake = WakeUi.Failed("Не вдалося завантажити модель: перевір інтернет")) }
                    return@launch
                }
            }
            WakeWordService.setEnabled(appContext, true)
            WakeWordService.startIfEnabled(appContext)
            _state.update { it.copy(wake = WakeUi.On) }
        }
    }

    fun setWakeSensitivity(value: WakeSensitivity) {
        WakeWordService.setSensitivity(appContext, value)
        _state.update { it.copy(wakeSensitivity = value) }
    }

    fun saveAboutMe(text: String) {
        aboutMe.set(text)
        _state.update { it.copy(aboutMe = aboutMe.get()) }
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
                SettingsViewModel(
                    c.voiceSettings, c.speaker, c.places, c.playlists, c.chats, c.contactAliases, c.contacts, c.aboutMe,
                    c.wakeModel, this[APPLICATION_KEY] as Context,
                )
            }
        }
    }
}
