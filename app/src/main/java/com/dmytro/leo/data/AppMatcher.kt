package com.dmytro.leo.data

/**
 * Fuzzy search of an app by its spoken name: "ютуб музик" → YouTube Music,
 * "телеграм" → Telegram, "налаштування" → Налаштування.
 */
object AppMatcher {

    sealed interface Match {
        data class Found(val app: AppEntry) : Match
        /** Not sure: these are the closest candidates, best first. */
        data class Unsure(val candidates: List<AppEntry>) : Match
    }

    fun find(query: String, apps: List<AppEntry>): Match {
        val ranked = apps
            .map { it to score(query, it.label) }
            .sortedByDescending { it.second }
        val (best, bestScore) = ranked.firstOrNull() ?: return Match.Unsure(emptyList())
        val secondScore = ranked.getOrNull(1)?.second ?: 0.0

        // Confident only when the best match is good and clearly ahead of the next one
        return if (bestScore >= CONFIDENT && bestScore - secondScore >= MARGIN) {
            Match.Found(best)
        } else {
            Match.Unsure(ranked.take(3).filter { it.second >= CANDIDATE }.map { it.first })
        }
    }

    /** 0..1, how well [query] matches an app [label]. */
    fun score(query: String, label: String): Double = maxOf(wholeScore(query, label), wordScore(query, label))

    private fun wholeScore(query: String, label: String): Double {
        val q = normalize(query)
        val l = normalize(label)
        if (q.isEmpty() || l.isEmpty()) return 0.0
        return when {
            q == l -> 1.0
            // "ютуб" fits both YouTube and YouTube Music; the closer length wins
            l.startsWith(q) || q.startsWith(l) -> 0.8 + 0.2 * minOf(q.length, l.length) / maxOf(q.length, l.length)
            q.length >= 3 && l.contains(q) -> 0.85
            else -> 1.0 - levenshtein(q, l).toDouble() / maxOf(q.length, l.length)
        }
    }

    /**
     * Word by word, so abbreviations count: label "YT Music" for "YouTube Music"
     * ("yt" is the start-anchored letters of "youtube").
     */
    private fun wordScore(query: String, label: String): Double {
        val qWords = words(query)
        val lWords = words(label)
        if (qWords.isEmpty() || lWords.size < 2 || qWords.size != lWords.size) return 0.0
        return lWords.zip(qWords).sumOf { (l, q) ->
            when {
                l == q -> 1.0
                l.length < q.length && l[0] == q[0] && isSubsequence(l, q) -> 0.8
                else -> 0.0
            }
        } / lWords.size
    }

    private fun words(text: String): List<String> =
        buildString { text.lowercase().forEach { append(TRANSLIT[it] ?: it.toString()) } }
            .split(Regex("[^a-z0-9]+"))
            .filter { it.isNotEmpty() }

    private fun isSubsequence(short: String, long: String): Boolean {
        var i = 0
        for (ch in long) if (i < short.length && short[i] == ch) i++
        return i == short.length
    }

    /** Lowercase Latin letters and digits only, with Ukrainian/Russian transliterated and similar sounds merged. */
    internal fun normalize(text: String): String {
        val latin = buildString {
            text.lowercase().forEach { ch -> append(TRANSLIT[ch] ?: ch.toString()) }
        }
        return latin
            .filter { it.isLetterOrDigit() }
            // Spelling vs. sound: "youtube" and "ютуб" (yutub) should meet in the middle
            .replace("you", "u").replace("yu", "u").replace("ou", "u")
            .replace("ph", "f").replace("kh", "h").replace("ck", "k")
            .replace('c', 'k').replace('w', 'v').replace('y', 'i').replace('q', 'k')
    }

    private fun levenshtein(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val current = IntArray(b.length + 1)
            current[0] = i
            for (j in 1..b.length) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1, previous[j - 1] + cost)
            }
            previous = current
        }
        return previous[b.length]
    }

    private const val CONFIDENT = 0.75
    private const val MARGIN = 0.05
    private const val CANDIDATE = 0.4

    private val TRANSLIT: Map<Char, String> = mapOf(
        'а' to "a", 'б' to "b", 'в' to "v", 'г' to "h", 'ґ' to "g", 'д' to "d", 'е' to "e",
        'є' to "ye", 'ж' to "zh", 'з' to "z", 'и' to "y", 'і' to "i", 'ї' to "yi", 'й' to "y",
        'к' to "k", 'л' to "l", 'м' to "m", 'н' to "n", 'о' to "o", 'п' to "p", 'р' to "r",
        'с' to "s", 'т' to "t", 'у' to "u", 'ф' to "f", 'х' to "kh", 'ц' to "ts", 'ч' to "ch",
        'ш' to "sh", 'щ' to "shch", 'ь' to "", 'ю' to "yu", 'я' to "ya", '\'' to "", 'ʼ' to "",
        'ы' to "y", 'э' to "e", 'ё' to "yo", 'ъ' to "",
    )
}
