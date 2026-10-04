package com.dmytro.leo.wake

import com.dmytro.leo.data.AboutMeStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WakePhraseTest {

    @Test
    fun `greeting plus something like Leo wakes him`() {
        assertTrue(WakePhrase.matches("привіт лео"))
        assertTrue(WakePhrase.matches("привіт леон"))
        assertTrue(WakePhrase.matches("хей ліо"))
        assertTrue(WakePhrase.matches("гей лев"))
        assertTrue(WakePhrase.matches("ну привіт мій лео як справи"))
    }

    @Test
    fun `other speech does not`() {
        assertFalse(WakePhrase.matches("привіт"))
        assertFalse(WakePhrase.matches("лео"))
        assertFalse(WakePhrase.matches("привіт як справи лео"))
        assertFalse(WakePhrase.matches("привіт лелеко"))
        assertFalse(WakePhrase.matches(""))
    }

    @Test
    fun `vosk results`() {
        assertEquals("привіт леон", WakePhrase.textOf("""{ "text" : "привіт леон" }"""))
        assertEquals("гей", WakePhrase.textOf("""{ "partial" : "гей" }"""))
        assertTrue(WakePhrase.hasGreeting("ну привіт"))
        assertFalse(WakePhrase.hasGreeting("як справи"))
    }

    @Test
    fun `gain makes quiet speech louder and clips loud sound instead of wrapping`() {
        fun pcm(vararg samples: Int) = ByteArray(samples.size * 2).also { b ->
            samples.forEachIndexed { i, s -> b[2 * i] = (s and 0xFF).toByte(); b[2 * i + 1] = (s shr 8).toByte() }
        }
        fun samples(b: ByteArray) = (0 until b.size / 2).map { ((b[2 * it].toInt() and 0xFF) or (b[2 * it + 1].toInt() shl 8)).toShort().toInt() }

        val audio = pcm(1000, -1000, 20000, -20000)
        AudioGain.apply(audio, audio.size, WakeSensitivity.Medium.gain)
        assertEquals(listOf(2500, -2500, 32767, -32768), samples(audio))

        val untouched = pcm(123)
        AudioGain.apply(untouched, untouched.size, WakeSensitivity.Low.gain)
        assertEquals(listOf(123), samples(untouched))
    }

    @Test
    fun `about me goes into the prompt only when filled in`() {
        val store = object : AboutMeStore {
            var text = ""
            override fun get() = text
            override fun set(text: String) { this.text = text }
        }
        assertEquals("", store.promptNote())
        store.set("Мене звати Дмитро")
        assertTrue(store.promptNote().endsWith("Мене звати Дмитро"))
    }
}
