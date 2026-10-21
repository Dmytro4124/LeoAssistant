package com.dmytro.leo.wake

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import org.vosk.Model
import org.vosk.Recognizer
import java.io.ByteArrayOutputStream
import kotlin.coroutines.coroutineContext

/** The microphone exactly as the wake listener uses it, so calibration hears what it will hear. */
class WakeMic : AutoCloseable {

    private val recorder: AudioRecord
    private val agc: AutomaticGainControl?

    init {
        val bufferSize = maxOf(AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT), CHUNK * 4)
        @SuppressLint("MissingPermission") // only used after RECORD_AUDIO was granted
        val r = AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufferSize)
        recorder = r
        // The phone's own automatic gain control, if it has one for this source
        agc = if (AutomaticGainControl.isAvailable()) AutomaticGainControl.create(r.audioSessionId)?.apply { enabled = true } else null
        recorder.startRecording()
    }

    val state get() = recorder.recordingState

    fun read(buffer: ByteArray) = recorder.read(buffer, 0, buffer.size)

    override fun close() {
        recorder.stop()
        recorder.release()
        agc?.release()
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val CHUNK = 4096
    }
}

/** Records the user saying the phrase and finds what works for their voice (see [WakeCalibration]). */
class WakeCalibrator(private val wakeModel: WakeModel) {

    /** Records [ms] of audio; [onLevel] gets the elapsed share and the loudness of each chunk. */
    suspend fun record(ms: Long, onLevel: (progress: Float, level: Float) -> Unit): ByteArray = withContext(Dispatchers.IO) {
        val total = (WakeMic.SAMPLE_RATE * 2 * ms / 1000).toInt()
        val out = ByteArrayOutputStream(total)
        WakeMic().use { mic ->
            val buffer = ByteArray(WakeMic.CHUNK)
            while (out.size() < total) {
                coroutineContext.ensureActive()
                val read = mic.read(buffer)
                if (read <= 0) continue
                out.write(buffer, 0, read)
                onLevel(out.size().toFloat() / total, AudioGain.level(buffer, read))
            }
        }
        out.toByteArray()
    }

    /** Runs every sample through the recognizer at every gain; [onProgress] gets 0..1. */
    suspend fun analyze(samples: List<ByteArray>, onProgress: (Float) -> Unit): WakeCalibration.Result? = withContext(Dispatchers.Default) {
        val model = Model(wakeModel.directory.absolutePath)
        try {
            val heard = mutableListOf<WakeCalibration.Heard>()
            val runs = samples.size * WakeCalibration.GAINS.size
            WakeCalibration.GAINS.forEach { gain ->
                samples.forEachIndexed { i, sample ->
                    coroutineContext.ensureActive()
                    heard += WakeCalibration.Heard(i, gain, transcribe(model, sample, gain), AudioGain.clippedShare(sample, sample.size, gain))
                    onProgress(heard.size.toFloat() / runs)
                }
            }
            WakeCalibration.choose(heard)
        } finally {
            model.close()
        }
    }

    /** Everything the recognizer heard in the sample, fed in chunks as the listener does. */
    private fun transcribe(model: Model, sample: ByteArray, gain: Float): String {
        val audio = sample.copyOf()
        AudioGain.apply(audio, audio.size, gain)
        val recognizer = Recognizer(model, WakeMic.SAMPLE_RATE.toFloat())
        try {
            val texts = mutableListOf<String>()
            var offset = 0
            while (offset < audio.size) {
                val chunk = audio.copyOfRange(offset, minOf(offset + WakeMic.CHUNK, audio.size))
                if (recognizer.acceptWaveForm(chunk, chunk.size)) texts += WakePhrase.textOf(recognizer.result)
                offset += WakeMic.CHUNK
            }
            texts += WakePhrase.textOf(recognizer.finalResult)
            return texts.filter { it.isNotBlank() }.joinToString(" ")
        } finally {
            recognizer.close()
        }
    }
}
