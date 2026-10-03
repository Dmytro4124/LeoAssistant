package com.dmytro.leo.data

/**
 * Finds contacts by a spoken name. Gemini passes the name in the nominative case
 * ("Олегу" → "Олег"); small leftovers of declension are tolerated here too.
 */
object ContactMatcher {

    fun find(query: String, contacts: List<Contact>, limit: Int = 5): List<Contact> {
        val queryWords = words(query)
        if (queryWords.isEmpty()) return emptyList()
        return contacts
            .map { it to score(queryWords, words(it.name)) }
            .filter { it.second >= THRESHOLD }
            .sortedByDescending { it.second }
            .take(limit)
            .map { it.first }
    }

    /** Every query word must match some word of the name ("Олег Петренко" ⊇ "Олег"). */
    private fun score(queryWords: List<String>, nameWords: List<String>): Double {
        if (nameWords.isEmpty()) return 0.0
        val perWord = queryWords.map { q -> nameWords.maxOf { n -> wordScore(q, n) } }
        val score = perWord.min()
        // A full-name match beats a first-name-only match
        return if (queryWords.size == nameWords.size) score + 0.01 else score
    }

    private fun wordScore(q: String, n: String): Double = when {
        q == n -> 1.0
        // "олегу" vs "олег", "мамі" vs "мама": same stem, different ending
        q.length >= 3 && n.length >= 3 && commonPrefix(q, n) >= maxOf(q.length, n.length) - 2 &&
            commonPrefix(q, n) >= 3 -> 0.85
        else -> 0.0
    }

    private fun commonPrefix(a: String, b: String): Int {
        var i = 0
        while (i < a.length && i < b.length && a[i] == b[i]) i++
        return i
    }

    private fun words(text: String) =
        text.lowercase().replace('ё', 'е').split(Regex("[^\\p{L}\\p{N}]+")).filter { it.isNotEmpty() }

    private const val THRESHOLD = 0.8
}
