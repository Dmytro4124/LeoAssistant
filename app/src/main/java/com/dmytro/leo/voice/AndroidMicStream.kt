package com.dmytro.leo.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive

/**
 * Microphone for the Live mode. VOICE_COMMUNICATION + echo canceller keep the model's
 * own voice from the speaker out of the recording, so the user can interrupt it.
 */
class AndroidMicStream : MicStream {

    // RECORD_AUDIO is requested by the UI before a voice session starts
    @SuppressLint("MissingPermission")
    override fun stream(): Flow<ByteArray> = flow {
        val minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recorder = AudioRecord(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            maxOf(minBuffer, CHUNK_BYTES * 4),
        )
        val echoCanceler = AcousticEchoCanceler.create(recorder.audioSessionId)?.apply { enabled = true }
        val noiseSuppressor = NoiseSuppressor.create(recorder.audioSessionId)?.apply { enabled = true }
        try {
            recorder.startRecording()
            val buffer = ByteArray(CHUNK_BYTES)
            while (currentCoroutineContext().isActive) {
                val read = recorder.read(buffer, 0, buffer.size)
                if (read > 0) emit(buffer.copyOf(read))
            }
        } finally {
            recorder.stop()
            recorder.release()
            echoCanceler?.release()
            noiseSuppressor?.release()
        }
    }.flowOn(Dispatchers.IO) // read() blocks

    private companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK_BYTES = SAMPLE_RATE * 2 / 10 // 100 ms of 16-bit mono
    }
}
