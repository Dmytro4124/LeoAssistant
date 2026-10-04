package com.dmytro.leo.voice

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import java.util.concurrent.LinkedBlockingQueue
import kotlin.concurrent.thread

/**
 * Streams the Live model's voice to the speaker. Audio arrives in pieces faster than
 * real time, so it is queued and written by a background thread.
 *
 * Played like a phone call (communication mode, loudspeaker): only then does the
 * phone's echo canceller remove this voice from the microphone, so the model does
 * not hear itself and the user can interrupt it.
 */
class AndroidPcmPlayer(context: Context) : PcmPlayer {

    private val audioManager = context.applicationContext.getSystemService(AudioManager::class.java)

    private val queue = LinkedBlockingQueue<ByteArray>()
    private val lock = Any()
    private var track: AudioTrack? = null
    private var writer: Thread? = null

    /** Bumped by interrupt(); the writer drops the rest of a chunk from an older generation. */
    @Volatile
    private var generation = 0

    /** Wall-clock time when the queued audio will have finished playing. */
    @Volatile
    private var speakingUntil = 0L

    override val isSpeaking: Boolean
        get() = SystemClock.elapsedRealtime() < speakingUntil

    override fun start() {
        enterCallMode()
        val minBuffer = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val newTrack = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(minBuffer * 2)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        newTrack.play()
        track = newTrack

        writer = thread(name = "leo-pcm-player") {
            try {
                while (true) {
                    val chunk = queue.take()
                    if (chunk === STOP) break
                    val chunkGeneration = generation
                    // Written in small slices so interrupt() never waits long for the lock
                    var offset = 0
                    while (offset < chunk.size && chunkGeneration == generation) {
                        val size = minOf(SLICE_BYTES, chunk.size - offset)
                        synchronized(lock) { newTrack.write(chunk, offset, size) }
                        offset += size
                    }
                }
            } catch (e: InterruptedException) {
                // stop() was called
            }
        }
    }

    override fun play(pcm: ByteArray) {
        val durationMs = pcm.size * 1000L / BYTES_PER_SECOND
        speakingUntil = maxOf(speakingUntil, SystemClock.elapsedRealtime()) + durationMs
        queue.put(pcm)
    }

    override fun interrupt() {
        generation++
        queue.clear()
        speakingUntil = 0L
        synchronized(lock) {
            track?.run {
                pause()
                flush()
                play()
            }
        }
    }

    override fun stop() {
        queue.clear()
        queue.put(STOP)
        writer?.join(500)
        writer = null
        synchronized(lock) {
            track?.run {
                stop()
                release()
            }
            track = null
        }
        speakingUntil = 0L
        leaveCallMode()
    }

    private fun enterCallMode() {
        audioManager.mode = AudioManager.MODE_IN_COMMUNICATION
        // In communication mode audio goes to the earpiece by default; use the loudspeaker
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.availableCommunicationDevices
                .firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
                ?.let { audioManager.setCommunicationDevice(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = true
        }
    }

    private fun leaveCallMode() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            audioManager.clearCommunicationDevice()
        } else {
            @Suppress("DEPRECATION")
            audioManager.isSpeakerphoneOn = false
        }
        // A phone call placed by Leo may already own the audio (and its loudspeaker): leave it alone
        if (audioManager.mode == AudioManager.MODE_IN_COMMUNICATION) audioManager.mode = AudioManager.MODE_NORMAL
    }

    private companion object {
        const val SAMPLE_RATE = 24_000
        const val BYTES_PER_SECOND = SAMPLE_RATE * 2
        const val SLICE_BYTES = BYTES_PER_SECOND / 20 // 50 ms
        val STOP = ByteArray(0)
    }
}
