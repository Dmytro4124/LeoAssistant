package com.dmytro.leo.wake

/**
 * The wake phrase "Привіт, Лео" / "Хей, Лео" for the Vosk Ukrainian model.
 *
 * The small model's vocabulary has no "лео" and no "хей", and restricting it to a grammar
 * made it reject the phrase as unknown. So it transcribes freely and we look for a greeting
 * followed by a word that sounds like "Лео" — it comes out as "лео", "леон", "лев", "ліо"…
 * Pure Kotlin, unit-tested.
 */
object WakePhrase {

    private val GREETINGS = setOf("привіт", "привет", "гей", "хей", "хай", "ей", "агов", "алло")

    /** "Лео" as the model may spell it: up to 4 letters starting like "ле", "лі", "льо" (not "лелеко"). */
    private fun soundsLikeLeo(word: String) =
        word.length in 2..4 && (word.startsWith("ле") || word.startsWith("лі") || word.startsWith("льо") || word.startsWith("лєо"))

    /** True when the text has a greeting followed (right away, or one word later) by "Лео". */
    fun matches(recognized: String): Boolean {
        val words = words(recognized)
        return words.indices.any { i ->
            words[i] in GREETINGS && (words.getOrNull(i + 1)?.let(::soundsLikeLeo) == true ||
                words.getOrNull(i + 2)?.let(::soundsLikeLeo) == true)
        }
    }

    /** Whether the text has a greeting at all (only such texts are worth logging for tuning). */
    fun hasGreeting(recognized: String) = words(recognized).any { it in GREETINGS }

    /** The "text"/"partial" field of a Vosk result JSON, e.g. {"text" : "привіт леон"}. */
    fun textOf(voskJson: String): String =
        Regex("\"(?:text|partial)\"\\s*:\\s*\"([^\"]*)\"").find(voskJson)?.groupValues?.get(1).orEmpty()

    private fun words(text: String) =
        text.lowercase().replace('’', '\'').split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
}
