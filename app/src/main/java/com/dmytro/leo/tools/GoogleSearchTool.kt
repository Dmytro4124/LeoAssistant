package com.dmytro.leo.tools

import com.dmytro.leo.accessibility.ScreenResult
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject

/**
 * Searches like the Google widget on the home screen: the Google app opens with the results,
 * and their text (with the AI overview) is read back from the screen for the model to answer.
 * A free stand-in for Search grounding, which the free API tier doesn't have.
 */
class GoogleSearchTool(
    private val phone: PhoneActions,
    private val screen: ScreenAgent,
    private val pollMs: Long = 700,
    private val timeoutMs: Long = 10_000,
) : Tool {

    override val name = "google_search"
    override val description =
        "Пошук у Google, як віджет на головному екрані: відкриває результати в додатку Google і повертає їхній текст " +
            "(разом з «Оглядом від ШІ»). Для курсу валют, новин, довідок, фактів, людей, подій, цін, розкладів, рецептів, " +
            "результатів матчів — усього, крім погоди. Також коли користувач просить «загугли» чи «пошукай в Google». " +
            "Результати лишаються на екрані: можна прокрутити (ui_scroll) чи відкрити посилання (ui_click)."
    override val parameters = objectSchema(
        Param("query", "Пошуковий запит, коротко, як його ввела б людина: «населення Києва», «розклад потягів Київ Львів»"),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val query = args.string("query") ?: return ToolResult.Failure("Не вказано, що шукати")
        if (!phone.webSearch(query, GOOGLE_APP) && !phone.openUri(GoogleResults.url(query))) {
            return ToolResult.Failure("Не вдалося відкрити пошук Google")
        }

        // The page loads and the AI overview keeps growing for a few seconds: wait until it settles
        var last: String? = null
        var waited = 0L
        while (waited < timeoutMs) {
            delay(pollMs)
            waited += pollMs
            when (val result = screen.read(detailed = true)) {
                ScreenResult.NotEnabled -> return ToolResult.Success(
                    "Результати пошуку «$query» відкрито на екрані, але прочитати їх не можу: керування екраном вимкнене. " +
                        "Скажи користувачу глянути на екран або увімкнути «Leo: керування екраном» у налаштуваннях Leo."
                )
                is ScreenResult.Ok -> {
                    val results = GoogleResults.extract(result.screen.text, query) ?: continue
                    if (results == last) return found(query, results)
                    last = results
                }
                else -> continue
            }
        }
        return last?.let { found(query, it) }
            ?: ToolResult.Failure("Результати пошуку «$query» відкрито, але вони не встигли завантажитися. Можна прочитати екран пізніше.")
    }

    private fun found(query: String, results: String) = ToolResult.Success(
        "Результати Google за запитом «$query» (з екрана):\n$results\n\n" +
            "Відповідай коротко своїми словами лише за цими результатами. Якщо відповіді тут немає — так і скажи.",
    )

    companion object {
        const val GOOGLE_APP = "com.google.android.googlequicksearchbox"
    }
}

/** Pure Kotlin, unit-tested. */
object GoogleResults {

    fun url(query: String) = "https://www.google.com/search?q=" + java.net.URLEncoder.encode(query, "UTF-8")

    /**
     * The results part of the Google app's screen, without the account line, search box and tabs
     * on top. Null while the results for [query] haven't appeared yet (the search box shows the query —
     * so results of an earlier search still on screen are not mistaken for these).
     */
    fun extract(screenText: String, query: String, maxChars: Int = 4000): String? {
        if (!screenText.contains(query, ignoreCase = true)) return null
        val lines = screenText.lines()
        val start = lines.indexOfFirst { line -> MARKERS.any { it in line } }
        if (start < 0) return null
        val text = lines.drop(start)
            .filterNot { line -> NOISE.any { it in line } || UNLABELLED.matches(line.trim()) }
            .joinToString("\n")
        return if (text.length <= maxChars) text else text.take(maxChars) + "\n…"
    }

    private val MARKERS = listOf("Результати пошуку", "Огляд від ШІ", "Search Results", "AI Overview")
    /** Icons and thumbnails without a label: "[22] кнопка". */
    private val UNLABELLED = Regex("""\[\d+] [\p{L} ]+""")
    private val NOISE = listOf("Про цей результат", "Відгук", "About this result", "Feedback")
}
