package com.dmytro.leo.wake

import com.dmytro.leo.wake.WakeCalibration.Heard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class WakeCalibrationTest {

    @Test
    fun `the gain that recognized the most wins, the highest one that doesn't distort`() {
        val heard = listOf(
            Heard(0, 1f, "привіт", 0.0), Heard(1, 1f, "привіт лео", 0.0),
            Heard(0, 2f, "привіт лео", 0.0), Heard(1, 2f, "привіт лео", 0.0),
            Heard(0, 3f, "привіт лео", 0.0), Heard(1, 3f, "привіт лео", 0.0),
            Heard(0, 4f, "привіт лео", 0.05), Heard(1, 4f, "привіт лео", 0.0),
        )
        val result = WakeCalibration.choose(heard)!!
        assertEquals(3f, result.gain)
        assertEquals(2, result.hits)
        assertEquals(2, result.samples)
        assertEquals(listOf("привіт лео", "привіт лео"), result.heard)
    }

    @Test
    fun `how the recognizer hears this voice is learned when it repeats`() {
        val heard = listOf(
            Heard(0, 1f, "привіт левко", 0.0), Heard(1, 1f, "привіт левко", 0.0),
            Heard(2, 1f, "при вітер", 0.0), Heard(0, 2f, "привіт левко", 0.0),
        )
        assertEquals(setOf("привіт левко"), WakeCalibration.learn(heard))
        val result = WakeCalibration.choose(heard)!!
        assertEquals(2, result.hits)

        assertTrue(WakePhrase.matches("ну Привіт, Левко!", result.learned))
        assertFalse("not learned without calibration", WakePhrase.matches("привіт левко"))
        assertFalse("only the whole phrase", WakePhrase.matches("левко", result.learned))
    }

    @Test
    fun `the phrase said in one breath is one word`() {
        assertTrue(WakePhrase.matches("привітне"))
        assertTrue(WakePhrase.matches("ну привітно"))
        assertFalse("other words", WakePhrase.matches("передай привіти"))
        assertFalse(WakePhrase.matches("з привітом"))

        val heard = listOf(Heard(0, 1f, "привітлеон", 0.0), Heard(1, 1f, "привітлеон", 0.0), Heard(2, 1f, "добре", 0.0), Heard(3, 1f, "добре", 0.0))
        assertEquals("a glued greeting is learned, a bare word is not", setOf("привітлеон"), WakeCalibration.learn(heard))
    }

    @Test
    fun `a pause after the greeting doesn't lose the phrase`() {
        val matcher = WakePhrase.Matcher(emptySet())
        assertFalse(matcher.hear("привіт", isFinal = true, nowMs = 0))
        assertTrue("«Лео» in the next utterance", matcher.hear("лев", isFinal = false, nowMs = 1_500))

        val late = WakePhrase.Matcher(emptySet())
        late.hear("привіт", isFinal = true, nowMs = 0)
        assertFalse("too long after the greeting", late.hear("лео", isFinal = true, nowMs = 4_000))

        val other = WakePhrase.Matcher(emptySet())
        other.hear("привіт", isFinal = true, nowMs = 0)
        other.hear("як справи", isFinal = true, nowMs = 1_000)
        assertFalse("other speech came in between", other.hear("лео", isFinal = true, nowMs = 2_000))
    }

    @Test
    fun `nothing recognized is a failed calibration`() {
        assertNull(WakeCalibration.choose(listOf(Heard(0, 1f, "", 0.0), Heard(1, 1f, "добрий день", 0.0))))
        assertNull(WakeCalibration.choose(emptyList()))
    }

    @Test
    fun `clipping and loudness are measured on 16-bit audio`() {
        val audio = ByteArray(4).also { b ->
            listOf(10000, -20000).forEachIndexed { i, s -> b[2 * i] = (s and 0xFF).toByte(); b[2 * i + 1] = (s shr 8).toByte() }
        }
        assertEquals(0.0, AudioGain.clippedShare(audio, audio.size, 1f), 0.0)
        assertEquals(0.5, AudioGain.clippedShare(audio, audio.size, 2f), 0.0)
        assertEquals(0.48, AudioGain.level(audio, audio.size).toDouble(), 0.01)
    }
}
