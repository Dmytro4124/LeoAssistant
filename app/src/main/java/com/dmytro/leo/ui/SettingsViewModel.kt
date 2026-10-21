package com.dmytro.leo.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider.AndroidViewModelFactory.Companion.APPLICATION_KEY
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import android.content.Context
import com.dmytro.leo.LeoApp
import com.dmytro.leo.data.AboutMeStore
import com.dmytro.leo.data.MemoryFact
import com.dmytro.leo.data.MemoryStore
import com.dmytro.leo.reminders.AndroidReminderAlerts
import com.dmytro.leo.reminders.Reminder
import com.dmytro.leo.reminders.ReminderTime
import com.dmytro.leo.reminders.Reminders
import com.dmytro.leo.reminders.Repeat
import java.time.Instant
import java.time.ZonedDateTime
import com.dmytro.leo.data.ChatStore
import com.dmytro.leo.data.ContactAliases
import com.dmytro.leo.data.ContactsSource
import com.dmytro.leo.data.PlacesStore
import com.dmytro.leo.data.IgnoredChatsStore
import com.dmytro.leo.data.PlaylistStore
import com.dmytro.leo.voice.SpeechOutput
import com.dmytro.leo.voice.TtsVoice
import com.dmytro.leo.voice.VoiceMode
import com.dmytro.leo.voice.VoiceSettings
import com.dmytro.leo.wake.WakeCalibration
import com.dmytro.leo.wake.WakeCalibrator
import com.dmytro.leo.wake.WakeModel
import com.dmytro.leo.wake.WakeSensitivity
import com.dmytro.leo.wake.WakeWordService
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** The "Привіт, Leo" switch: off, on, or downloading the offline model first. */
sealed interface WakeUi {
    data object Off : WakeUi
    data object On : WakeUi
    data class Downloading(val progress: Float) : WakeUi
    data class Failed(val message: String) : WakeUi
}

/** Tuning "Привіт, Leo" to the user's voice: say it a few times, then Leo picks what works. */
sealed interface CalibrationUi {
    data object Preparing : CalibrationUi
    data class Recording(val sample: Int, val total: Int, val progress: Float, val level: Float) : CalibrationUi
    data class Analyzing(val progress: Float) : CalibrationUi
    data class Done(val result: WakeCalibration.Result) : CalibrationUi
    data class Failed(val message: String) : CalibrationUi
}

/** A reminder, to-do or scheduled task as the settings list shows it. */
data class SavedReminder(val id: Int, val text: String, val detail: String) {
    companion object {
        fun of(r: Reminder, now: ZonedDateTime = ZonedDateTime.now()): SavedReminder {
            val repeat = if (r.repeat != Repeat.None) ", ${r.repeat.label}" else ""
            val detail = when (r.kind) {
                Reminder.Kind.Task -> "Leo виконає: " + ReminderTime.describe(Instant.ofEpochMilli(r.at!!), now) + repeat
                Reminder.Kind.Time -> ReminderTime.describe(Instant.ofEpochMilli(r.at!!), now) + repeat
                Reminder.Kind.Place -> "Коли будеш біля «${r.place!!.name}»" + if (r.repeat != Repeat.None) ", щоразу" else ""
                Reminder.Kind.Todo -> "У списку справ"
            }
            return SavedReminder(r.id, r.task ?: r.text, detail)
        }
    }
}

/** Something the user taught Leo, shown in Settings with a delete button. */
data class SavedItem(val name: String, val detail: String)

data class SettingsUiState(
    val voiceMode: VoiceMode = VoiceMode.Live,
    val liveVoice: String = VoiceSettings.DEFAULT_LIVE_VOICE,
    val ttsVoices: List<TtsVoice> = emptyList(),
    val currentTtsVoice: TtsVoice? = null,
    val places: List<SavedItem> = emptyList(),
    val playlists: List<SavedItem> = emptyList(),
    val chats: List<SavedItem> = emptyList(),
    val ignoredChats: List<SavedItem> = emptyList(),
    val contactAliases: List<SavedItem> = emptyList(),
    val aboutMe: String = "",
    val memoryEnabled: Boolean = true,
    val memory: List<MemoryFact> = emptyList(),
    val reminders: List<SavedReminder> = emptyList(),
    val speakReminders: Boolean = true,
    val wake: WakeUi = WakeUi.Off,
    val wakeSensitivity: WakeSensitivity = WakeSensitivity.Medium,
    val wakeCalibrated: Boolean = false,
    val calibration: CalibrationUi? = null,
)

class SettingsViewModel(
    private val voiceSettings: VoiceSettings,
    private val speaker: SpeechOutput,
    private val places: PlacesStore,
    private val playlists: PlaylistStore,
    private val chats: ChatStore,
    private val ignoredChats: IgnoredChatsStore,
    private val aliases: ContactAliases,
    private val contacts: ContactsSource,
    private val aboutMe: AboutMeStore,
    private val memory: MemoryStore,
    private val reminders: Reminders,
    private val wakeModel: WakeModel,
    private val appContext: Context,
) : ViewModel() {

    private val calibrator = WakeCalibrator(wakeModel)
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
                wakeCalibrated = WakeWordService.isCalibrated(appContext),
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
                    memoryEnabled = memory.enabled,
                    memory = memory.all().sortedBy { it.id },
                    reminders = reminders.store.all().filter { !it.fired }.sortedWith(compareBy(nullsLast()) { it.at }).map { SavedReminder.of(it) },
                    speakReminders = AndroidReminderAlerts.isSpeakEnabled(appContext),
                    places = places.all().map { SavedItem(it.key, it.value) },
                    playlists = playlists.all().keys.map { SavedItem(it, "YouTube Music") },
                    chats = chats.all().map { SavedItem(it.key, it.value.messenger.name) },
                    ignoredChats = ignoredChats.all().map { SavedItem(it.key, it.value) },
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

    private var calibrationJob: Job? = null

    /** The listener is paused (it holds the mic), the samples are recorded and analyzed, then it resumes. */
    fun startCalibration() {
        if (calibrationJob?.isActive == true) return
        calibrationJob = viewModelScope.launch {
            _state.update { it.copy(calibration = CalibrationUi.Preparing) }
            WakeWordService.stop(appContext)
            try {
                delay(800) // let the listener release the microphone
                val samples = mutableListOf<ByteArray>()
                repeat(WakeCalibration.SAMPLES) { i ->
                    _state.update { it.copy(calibration = CalibrationUi.Recording(i + 1, WakeCalibration.SAMPLES, 0f, 0f)) }
                    samples += calibrator.record(SAMPLE_MS) { progress, level ->
                        _state.update { it.copy(calibration = CalibrationUi.Recording(i + 1, WakeCalibration.SAMPLES, progress, level)) }
                    }
                    delay(400)
                }
                _state.update { it.copy(calibration = CalibrationUi.Analyzing(0f)) }
                val result = calibrator.analyze(samples) { p -> _state.update { it.copy(calibration = CalibrationUi.Analyzing(p)) } }
                if (result == null) {
                    _state.update {
                        it.copy(
                            calibration = CalibrationUi.Failed(
                                "Не впізнав фразу жодного разу. Говори чіткіше, ближче до телефона, і без пауз: «Привіт, Leo»."
                            )
                        )
                    }
                } else {
                    WakeWordService.saveCalibration(appContext, result)
                    _state.update {
                        it.copy(calibration = CalibrationUi.Done(result), wakeSensitivity = WakeSensitivity.Personal, wakeCalibrated = true)
                    }
                }
            } catch (e: RuntimeException) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                _state.update { it.copy(calibration = CalibrationUi.Failed("Не вдалося записати мікрофон: ${e.message}")) }
            } finally {
                WakeWordService.startIfEnabled(appContext)
            }
        }
    }

    fun closeCalibration() {
        calibrationJob?.cancel()
        _state.update { it.copy(calibration = null) }
    }

    fun clearCalibration() {
        WakeWordService.clearCalibration(appContext)
        _state.update { it.copy(wakeCalibrated = false, wakeSensitivity = WakeWordService.sensitivity(appContext)) }
    }

    override fun onCleared() {
        calibrationJob?.cancel()
        super.onCleared()
    }

    fun removeReminder(id: Int) = reminders.remove(listOf(id)).also { refresh() }

    fun setSpeakReminders(enabled: Boolean) {
        AndroidReminderAlerts.setSpeakEnabled(appContext, enabled)
        refresh()
    }

    /** Location access "all the time" was just given: set up the place reminders saved before. */
    fun permissionsChanged() {
        reminders.rescheduleAll()
        refresh()
    }

    fun setMemoryEnabled(enabled: Boolean) {
        memory.enabled = enabled
        refresh()
    }

    fun updateFact(id: Int, text: String) = memory.update(id, text).also { refresh() }
    fun removeFact(id: Int) = memory.remove(listOf(id)).also { refresh() }
    fun forgetAll() = memory.clear().also { refresh() }

    fun saveAboutMe(text: String) {
        aboutMe.set(text)
        _state.update { it.copy(aboutMe = aboutMe.get()) }
    }

    fun removePlace(name: String) = places.remove(name).also { refresh() }
    fun removePlaylist(name: String) = playlists.remove(name).also { refresh() }
    fun removeChat(name: String) = chats.remove(name).also { refresh() }
    fun removeIgnoredChat(name: String) = ignoredChats.remove(name).also { refresh() }
    fun removeAlias(name: String) = aliases.remove(name).also { refresh() }

    companion object {
        private const val SAMPLE_MS = 3_000L
        private const val VOICE_SAMPLE = "Привіт! Я Leo. Так звучить мій голос."

        val Factory = viewModelFactory {
            initializer {
                val c = (this[APPLICATION_KEY] as LeoApp).container
                SettingsViewModel(
                    c.voiceSettings, c.speaker, c.places, c.playlists, c.chats, c.ignoredChats, c.contactAliases, c.contacts, c.aboutMe, c.memory, c.reminders,
                    c.wakeModel, this[APPLICATION_KEY] as Context,
                )
            }
        }
    }
}
