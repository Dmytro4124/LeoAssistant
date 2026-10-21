package com.dmytro.leo.wake

/**
 * The wake phrase "Привіт, Лео" / "Хей, Лео" for the Vosk Ukrainian model.
 *
 * The small model's vocabulary has no "лео" and no "хей", and restricting it to a grammar
 * made it reject the phrase as unknown. So it transcribes freely and we look for a greeting
 * followed by a word that sounds like "Лео" — it comes out as "лео", "леон", "лев", "ліо"…
 * Calibration adds how the model hears this particular user ([learned] phrases).
 * Pure Kotlin, unit-tested.
 */
object WakePhrase {

    private val GREETINGS = setOf("привіт", "привет", "гей", "хей", "хай", "ей", "агов", "алло")

    /** "Лео" as the model may spell it: up to 4 letters starting like "ле", "лі", "льо" (not "лелеко"). */
    private fun soundsLikeLeo(word: String) =
        word.length in 2..4 && (word.startsWith("ле") || word.startsWith("лі") || word.startsWith("льо") || word.startsWith("лєо"))

    /**
     * Said quickly, "Привіт, Лео" becomes one dictionary word: "привітне", "привітно" — the "л" is
     * lost and only the end of "Лео" is left. Not "привіти", "привітом": those are other words.
     */
    private val GLUED_LEO = setOf("не", "но", "на", "ле", "ло", "лі")

    private fun isGlued(word: String) = word.startsWith("привіт") && word.removePrefix("привіт") in GLUED_LEO

    /** One word starting with a greeting ("привітне"): calibration may learn such words too. */
    fun startsWithGreeting(word: String) = GREETINGS.any { word.startsWith(it) && word.length > it.length }

    /**
     * True when the text has a greeting followed (right away, or one word later) by "Лео",
     * or contains one of the [learned] phrases (normalized, see [normalize]) word for word.
     */
    fun matches(recognized: String, learned: Set<String> = emptySet()): Boolean {
        val words = words(recognized)
        val general = words.any(::isGlued) || words.indices.any { i ->
            words[i] in GREETINGS && (words.getOrNull(i + 1)?.let(::soundsLikeLeo) == true ||
                words.getOrNull(i + 2)?.let(::soundsLikeLeo) == true)
        }
        if (general || learned.isEmpty()) return general
        val text = " ${words.joinToString(" ")} "
        return learned.any { text.contains(" $it ") }
    }

    /** "…привіт": the user paused after the greeting, so "Лео" may come in the next utterance. */
    fun endsWithGreeting(recognized: String) = words(recognized).lastOrNull() in GREETINGS

    /** Whether the text has a greeting at all (only such texts are worth logging for tuning). */
    fun hasGreeting(recognized: String) = words(recognized).any { it in GREETINGS }

    /** Lowercase words separated by single spaces: the form learned phrases are kept in. */
    fun normalize(text: String) = words(text).joinToString(" ")

    /** The "text"/"partial" field of a Vosk result JSON, e.g. {"text" : "привіт леон"}. */
    fun textOf(voskJson: String): String =
        Regex("\"(?:text|partial)\"\\s*:\\s*\"([^\"]*)\"").find(voskJson)?.groupValues?.get(1).orEmpty()

    /**
     * Follows the recognizer's results as they come. The pause in "Привіт, … Лео" often makes the
     * recognizer close the utterance after the greeting; then "Лео" still counts if it comes within
     * [gapMs] — so the phrase works on the first try.
     */
    class Matcher(private val learned: Set<String>, private val gapMs: Long = 2_500) {
        private var greetingAt: Long? = null

        fun hear(text: String, isFinal: Boolean, nowMs: Long): Boolean {
            val since = greetingAt?.let { nowMs - it }
            val joined = if (since != null && since <= gapMs) "привіт $text" else text
            if (matches(joined, learned)) return true
            if (isFinal && text.isNotBlank()) greetingAt = if (endsWithGreeting(text)) nowMs else null
            return false
        }
    }

    private fun words(text: String) =
        text.lowercase().replace('’', '\'').split(Regex("[^\\p{L}']+")).filter { it.isNotEmpty() }
}
