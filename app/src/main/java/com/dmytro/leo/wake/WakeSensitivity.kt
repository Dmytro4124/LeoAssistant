package com.dmytro.leo.wake

import kotlin.math.roundToInt

/**
 * How far away "Привіт, Leo" is heard: higher hears from farther, but wakes up more by mistake.
 * [Personal] is what calibration picked for the user's voice (its gain is saved separately).
 */
enum class WakeSensitivity(val label: String, val gain: Float) {
    Low("Низька", 1f),
    Medium("Середня", 2.5f),
    High("Висока", 4f),
    Personal("Мій голос", 1f),
}

/** Software microphone gain before recognition. Pure Kotlin, unit-tested. */
object AudioGain {

    /** Multiplies 16-bit little-endian PCM in place by [gain], clipping instead of wrapping around. */
    fun apply(pcm: ByteArray, length: Int, gain: Float) {
        if (gain == 1f) return
        for (i in 0 until length / 2) {
            val louder = (sample(pcm, i) * gain).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt())
            pcm[2 * i] = (louder and 0xFF).toByte()
            pcm[2 * i + 1] = (louder shr 8).toByte()
        }
    }

    /** Share of samples that [gain] would push past full scale (distorting the voice). */
    fun clippedShare(pcm: ByteArray, length: Int, gain: Float): Double {
        val count = length / 2
        if (count == 0) return 0.0
        val clipped = (0 until count).count { kotlin.math.abs(sample(pcm, it) * gain) > Short.MAX_VALUE }
        return clipped.toDouble() / count
    }

    /** Loudness 0..1 (RMS of full scale), for the level meter. */
    fun level(pcm: ByteArray, length: Int): Float {
        val count = length / 2
        if (count == 0) return 0f
        var sum = 0.0
        for (i in 0 until count) sum += sample(pcm, i).toDouble() * sample(pcm, i)
        return (kotlin.math.sqrt(sum / count) / Short.MAX_VALUE).toFloat()
    }

    private fun sample(pcm: ByteArray, i: Int) = ((pcm[2 * i].toInt() and 0xFF) or (pcm[2 * i + 1].toInt() shl 8)).toShort().toInt()
}

/** Tuning the wake word to the user's voice and microphone. Pure Kotlin, unit-tested. */
object WakeCalibration {

    /** How many times the user says the phrase. */
    const val SAMPLES = 5

    /** Gains every recorded sample is tried with. */
    val GAINS = listOf(1f, 1.5f, 2f, 3f, 4f, 6f)

    /** More clipping than this distorts the voice enough to hurt recognition. */
    private const val MAX_CLIPPED = 0.002

    /** What the recognizer made of sample [sample] at [gain]. */
    data class Heard(val sample: Int, val gain: Float, val text: String, val clipped: Double)

    data class Result(val gain: Float, val hits: Int, val samples: Int, val learned: Set<String>, val heard: List<String>)

    /**
     * How the recognizer hears this user's "Привіт, Leo" where the general rule misses it: a
     * transcription (see [isLearnable]) that came out in at least two different samples (one-offs are noise).
     */
    fun learn(heard: List<Heard>): Set<String> =
        heard.filter { !WakePhrase.matches(it.text) }
            .map { it.sample to WakePhrase.normalize(it.text) }
            .filter { (_, phrase) -> isLearnable(phrase) }
            .distinct()
            .groupBy({ it.second }, { it.first })
            .filterValues { it.size >= 2 }
            .keys

    /** 2–3 words, or one glued word starting with a greeting ("привітне"); never a bare short word. */
    private fun isLearnable(phrase: String): Boolean {
        val words = phrase.split(' ')
        return when (words.size) {
            1 -> phrase.length >= 7 && WakePhrase.startsWithGreeting(phrase)
            2, 3 -> phrase.replace(" ", "").length >= 6
            else -> false
        }
    }

    /**
     * The gain that recognized the most samples; among equally good ones the highest that doesn't
     * distort the voice, since in real use the user is usually farther from the phone than now.
     * Null when no sample was recognized at all.
     */
    fun choose(heard: List<Heard>): Result? {
        if (heard.isEmpty()) return null
        val learned = learn(heard)
        val byGain = heard.groupBy { it.gain }
        val hits = byGain.mapValues { (_, h) -> h.count { WakePhrase.matches(it.text, learned) } }
        val best = hits.values.max()
        if (best == 0) return null
        val bestGains = hits.filterValues { it == best }.keys
        val gain = bestGains.filter { g -> byGain.getValue(g).all { it.clipped <= MAX_CLIPPED } }.maxOrNull() ?: bestGains.min()
        return Result(
            gain = gain,
            hits = best,
            samples = heard.map { it.sample }.distinct().size,
            learned = learned,
            heard = byGain.getValue(gain).sortedBy { it.sample }.map { it.text },
        )
    }
}
