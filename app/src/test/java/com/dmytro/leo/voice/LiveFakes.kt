package com.dmytro.leo.voice

import com.dmytro.leo.brain.Content
import com.dmytro.leo.brain.live.LiveApi
import com.dmytro.leo.brain.live.LiveConnection
import com.dmytro.leo.brain.live.LiveEvent
import com.dmytro.leo.brain.live.LiveSetup
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.receiveAsFlow

/** A Live server the test drives by pushing events. */
class FakeLiveConnection : LiveConnection {
    private val channel = Channel<LiveEvent>(Channel.UNLIMITED)
    override val events: Flow<LiveEvent> = channel.receiveAsFlow()

    val history = mutableListOf<Content>()
    var audioChunksSent = 0
    var closed = false

    fun push(vararg events: LiveEvent) = events.forEach { channel.trySend(it) }

    /** The server ends the session (optionally with an error). */
    fun serverClose(event: LiveEvent.Closed = LiveEvent.Closed(null)) {
        channel.trySend(event)
        channel.close()
    }

    override fun sendHistory(turns: List<Content>) {
        history += turns
    }

    override fun sendAudio(pcm: ByteArray) {
        audioChunksSent++
    }

    override fun close() {
        closed = true
        channel.trySend(LiveEvent.Closed(null))
        channel.close()
    }
}

class FakeLiveApi(val connection: FakeLiveConnection = FakeLiveConnection()) : LiveApi {
    val setups = mutableListOf<LiveSetup>()
    override fun connect(setup: LiveSetup): LiveConnection {
        setups += setup
        return connection
    }
}

/** Emits one silent chunk, then keeps "recording" until cancelled. */
class FakeMic : MicStream {
    var recording = false
    override fun stream(): Flow<ByteArray> = flow {
        recording = true
        try {
            emit(ByteArray(3200))
            awaitCancellation()
        } finally {
            recording = false
        }
    }
}

class FakePlayer : PcmPlayer {
    override var isSpeaking = false
    val played = mutableListOf<ByteArray>()
    var interrupts = 0
    var started = false

    override fun start() { started = true }
    override fun play(pcm: ByteArray) { played += pcm }
    override fun interrupt() { interrupts++ }
    override fun stop() { started = false }
}

class FakeVoiceSettings(mode: VoiceMode) : VoiceSettings {
    override val mode = MutableStateFlow(mode)
    override val liveVoice = MutableStateFlow(VoiceSettings.DEFAULT_LIVE_VOICE)
    override fun useLive(voiceName: String) { mode.value = VoiceMode.Live; liveVoice.value = voiceName }
    override fun useClassic() { mode.value = VoiceMode.Classic }
}
