package com.dmytro.leo.wake

import kotlin.math.roundToInt

/** How far away "Привіт, Leo" is heard: higher hears from farther, but wakes up more by mistake. */
enum class WakeSensitivity(val label: String, val gain: Float) {
    Low("Низька", 1f),
    Medium("Середня", 2.5f),
    High("Висока", 4f),
}

/** Software microphone gain before recognition. Pure Kotlin, unit-tested. */
object AudioGain {

    /** Multiplies 16-bit little-endian PCM in place by [gain], clipping instead of wrapping around. */
    fun apply(pcm: ByteArray, length: Int, gain: Float) {
        if (gain == 1f) return
        for (i in 0 until length / 2) {
            val sample = ((pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)).toShort().toInt()
            val louder = (sample * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm[2 * i] = (louder and 0xFF).toByte()
            pcm[2 * i + 1] = (louder shr 8).toByte()
        }
    }
}
