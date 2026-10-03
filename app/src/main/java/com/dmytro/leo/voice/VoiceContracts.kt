package com.dmytro.leo.voice

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

// Interfaces of the voice layer. Android implementations live next to them;
// tests replace them with fakes.

/** Microphone → text. */
interface SpeechInput {
    /**
     * Collecting starts one listening session; cancelling the collection stops it.
     * The flow ends after [SpeechEvent.Final] or [SpeechEvent.Error].
     */
    fun listen(): Flow<SpeechEvent>
}

sealed interface SpeechEvent {
    data class Partial(val text: String) : SpeechEvent
    /** Microphone loudness, roughly -2..10 dB; used for the button animation. */
    data class Level(val rmsDb: Float) : SpeechEvent
    data class Final(val text: String) : SpeechEvent
    data class Error(val error: SpeechError) : SpeechEvent
}

sealed interface SpeechError {
    data object NoSpeech : SpeechError
    data object Network : SpeechError
    data object NoPermission : SpeechError
    data object Busy : SpeechError
    data object LanguageUnavailable : SpeechError
    data object NotAvailable : SpeechError
    data class Other(val code: Int) : SpeechError
}

fun SpeechError.toUserMessage(): String = when (this) {
    SpeechError.NoSpeech -> "Я нічого не почув."
    SpeechError.Network -> "Немає зв'язку з сервісом розпізнавання мови."
    SpeechError.NoPermission -> "Немає дозволу на мікрофон."
    SpeechError.Busy -> "Мікрофон зайнятий. Спробуй ще раз."
    SpeechError.LanguageUnavailable -> "Українське розпізнавання мови недоступне. Перевір мовні налаштування Google."
    SpeechError.NotAvailable -> "На телефоні немає сервісу розпізнавання мови."
    is SpeechError.Other -> "Помилка розпізнавання мови ($code)."
}

/** Text → voice. */
interface SpeechOutput {
    val status: StateFlow<SpeakerStatus>

    /** Ukrainian voices of all installed TTS engines. */
    val voices: StateFlow<List<TtsVoice>>
    val currentVoice: StateFlow<TtsVoice?>

    /** Switches the voice (and the engine, if needed); the choice is remembered. */
    fun selectVoice(voice: TtsVoice)

    /** Suspends until the phrase is spoken. Cancelling the caller stops the speech. */
    suspend fun speak(text: String)
}

/** A voice of a TTS engine, e.g. engine = RHVoice package, name = "Anatol". */
data class TtsVoice(val engine: String, val name: String, val label: String)

enum class SpeakerStatus { Initializing, Ready, MissingUkrainianVoice, Failed }

/** Raw microphone audio for the Live mode. */
interface MicStream {
    /** 16 kHz, 16-bit, mono PCM chunks of ~100 ms. Recording stops when collection is cancelled. */
    fun stream(): Flow<ByteArray>
}

/** Plays raw PCM from the Live model as it arrives. */
interface PcmPlayer {
    /** True while queued audio is still being played. */
    val isSpeaking: Boolean
    fun start()
    /** 24 kHz, 16-bit, mono PCM. */
    fun play(pcm: ByteArray)
    /** Drops everything queued (the user talked over the model). */
    fun interrupt()
    fun stop()
}

/** Pauses other audio (music, video) while the assistant listens and talks. */
interface AudioFocus {
    fun acquire()
    fun release()
}
