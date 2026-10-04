package com.dmytro.leo.tools

import com.dmytro.leo.accessibility.Screen
import com.dmytro.leo.accessibility.ScreenResult
import com.dmytro.leo.accessibility.UiElement
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import java.net.URLEncoder

/**
 * Asks the Claude app on the phone and returns its answer for Leo to read out: a new chat
 * opens with the question already typed (claude.ai/new?q=…), Leo presses Send, waits until the
 * answer is finished (its Copy/Retry buttons appear) and reads it from the screen. A follow-up
 * goes into the chat that is already open.
 */
class ClaudeAppTool(
    private val phone: PhoneActions,
    private val screen: ScreenAgent,
    private val pollMs: Long = 1_000,
    private val timeoutMs: Long = 120_000,
) : Tool {

    override val name = "ask_claude"
    override val description =
        "Спитати Claude в додатку Claude на телефоні й отримати його відповідь, щоб прочитати її користувачу вголос. " +
            "Коли користувач просить «спитай Claude», «запитай у Клода», «що скаже Claude». " +
            "Питання передавай словами користувача. Для уточнення в тій самій розмові — follow_up=true."
    override val parameters = objectSchema(
        Param("question", "Що спитати, як сказав користувач"),
        Param("follow_up", "«true» — продовжити відкриту розмову з Claude, а не почати нову", required = false, enum = listOf("true", "false")),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val question = args.string("question") ?: return ToolResult.Failure("Не вказано, що спитати")
        val followUp = args.string("follow_up") == "true"

        val sent = if (followUp) askHere(question) || askInNewChat(question) else askInNewChat(question)
        if (!sent) {
            return ToolResult.Failure(
                "Не вдалося надіслати питання в Claude. Якщо керування екраном вимкнене — нехай користувач увімкне «Leo: керування екраном»."
            )
        }

        val done = awaitAnswer(question) ?: return ToolResult.Failure(
            "Claude не відповів за ${timeoutMs / 1000} секунд. Відповідь може з'явитися пізніше — вона лишається на екрані."
        )
        val answer = collectAnswer(done, question)
            ?: return ToolResult.Failure("Claude відповів, але прочитати відповідь з екрана не вдалося. Вона на екрані.")
        return ToolResult.Success(
            "Відповідь Claude:\n$answer\n\n" +
                "Прочитай її користувачу ПОВНІСТЮ і дослівно — правило коротких відповідей тут не діє. " +
                "Лише прибери markdown (списки — звичайними реченнями) і, якщо вона не українською, перекладай дослівно. " +
                "Не переказуй, не скорочуй і не додавай свого.",
        )
    }

    private suspend fun askInNewChat(question: String): Boolean {
        if (!phone.openUri(ClaudeScreen.newChatUrl(question), PACKAGE)) return false
        val ready = awaitScreen { s -> s.field()?.label?.let { ClaudeScreen.sameText(it, question) } == true && s.send() != null }
            ?: return false
        screen.click(ready.send()!!.id)
        return true
    }

    /** Back to the open chat (Leo may be on top of it) and type the question there. */
    private suspend fun askHere(question: String): Boolean {
        if (!phone.openApp(PACKAGE)) return false
        val chat = awaitScreen { it.app.contains("Claude", ignoreCase = true) && it.field() != null } ?: return false
        val field = chat.field() ?: return false
        screen.type(field.id, question)
        val ready = awaitScreen { it.send() != null } ?: return false
        screen.click(ready.send()!!.id)
        return true
    }

    /** Finished: nothing is streaming, the text stopped changing, and the Copy button under this question is there. */
    private suspend fun awaitAnswer(question: String): Screen? {
        var previous: String? = null
        // Give Claude a moment to start, so the buttons of an older answer aren't taken for this one
        delay(pollMs * 2)
        return awaitScreen(timeoutMs) { s ->
            val finished = ClaudeScreen.isFinished(s, question) && s.text == previous
            previous = s.text
            finished
        }
    }

    /** The answer can be longer than the screen: scroll up to the question, then read down to its Copy button. */
    private suspend fun collectAnswer(done: Screen, question: String): String? {
        ClaudeScreen.answer(done.text, question)?.takeIf { ClaudeScreen.hasQuestion(done.text, question) }?.let { return it }

        var current = done
        repeat(MAX_PAGES) {
            if (ClaudeScreen.hasQuestion(current.text, question)) return@repeat
            screen.scroll(null, forward = false)
            current = (screen.read(detailed = true) as? ScreenResult.Ok)?.screen ?: return null
        }
        val lines = LinkedHashSet<String>()
        repeat(MAX_PAGES) {
            val part = ClaudeScreen.answerLines(current.text, question)
            lines += part.lines
            if (part.complete) return@repeat
            screen.scroll(null, forward = true)
            current = (screen.read(detailed = true) as? ScreenResult.Ok)?.screen ?: return@repeat
        }
        return lines.joinToString("\n").takeIf { it.isNotBlank() }?.let(ClaudeScreen::cap)
    }


    private fun Screen.field(): UiElement? = elements.values.firstOrNull { it.role.startsWith("поле") }
    private fun Screen.send(): UiElement? = elements.values.firstOrNull { it.label == "Send" }

    private suspend fun awaitScreen(timeout: Long = 10_000, ready: (Screen) -> Boolean): Screen? {
        var waited = 0L
        while (waited <= timeout) {
            when (val result = screen.read(detailed = true)) {
                is ScreenResult.Ok -> if (ready(result.screen)) return result.screen
                ScreenResult.NotEnabled, is ScreenResult.Blocked -> return null
                else -> Unit
            }
            delay(pollMs)
            waited += pollMs
        }
        return null
    }

    companion object {
        const val PACKAGE = "com.anthropic.claude"
        private const val MAX_PAGES = 10
    }
}

/** Reading the Claude app's chat screen. Pure Kotlin, unit-tested. */
object ClaudeScreen {

    private const val COPY = "Copy message"
    private const val MAX_CHARS = 4000

    /** Labels of the app's own buttons and notes, never part of an answer. */
    private val CHROME = setOf(
        "Copy message", "Share", "Narrate", "Good response", "Bad response", "Retry message", "Edit message",
        "Claude is AI and can make mistakes.", "Open menu", "Linked computer", "New session", "More options",
        "Dismiss", "Add to chat", "Start speech input", "Voice Mode", "Send", "Incognito chat",
    )

    fun newChatUrl(question: String) = "https://claude.ai/new?q=" + URLEncoder.encode(question, "UTF-8").replace("+", "%20")

    /**
     * Nothing is streaming and this question's answer has its Copy button. A long answer pushes the
     * question off screen: then any Copy button counts (the last one is at the bottom).
     */
    fun isFinished(screen: Screen, question: String): Boolean {
        if (screen.elements.values.any { it.label.startsWith("Stop") } || COPY !in screen.text) return false
        if (!hasQuestion(screen.text, question)) return true
        val part = answerLines(screen.text, question)
        return part.complete && part.lines.isNotEmpty()
    }

    fun hasQuestion(screenText: String, question: String) = texts(screenText).any { sameText(it, question) }

    /** The answer when the whole of it is on screen: the texts after the last copy of the question, up to its Copy button. */
    fun answer(screenText: String, question: String): String? {
        val part = answerLines(screenText, question)
        return if (part.complete && part.lines.isNotEmpty()) cap(part.lines.joinToString("\n")) else null
    }

    data class Part(val lines: List<String>, val complete: Boolean)

    /** Answer lines on this screen: after the question (or from the top if it scrolled away), until the Copy button. */
    fun answerLines(screenText: String, question: String): Part {
        val all = screenText.lines().drop(1) // "Програма: Claude"
        val start = all.indexOfLast { line -> quoted(line)?.let { sameText(it, question) } == true }
        val after = if (start >= 0) all.drop(start + 1) else all
        val end = after.indexOfFirst { quoted(it) == COPY }
        val body = if (end >= 0) after.take(end) else after
        val lines = body.mapNotNull { quoted(it) }.filter { it !in CHROME && !it.startsWith("Reply to Claude") }
        return Part(lines, complete = end >= 0)
    }

    /** The same text, ignoring case, spacing and what a text field adds (it may be shortened with "…"). */
    fun sameText(a: String, b: String): Boolean {
        fun norm(s: String) = s.lowercase().replace(Regex("\\s+"), " ").trim().removeSuffix("…").removeSuffix("...")
        val x = norm(a)
        val y = norm(b)
        return x == y || (x.length >= 20 && y.startsWith(x)) || (y.length >= 20 && x.startsWith(y))
    }

    fun cap(text: String) = if (text.length <= MAX_CHARS) text else text.take(MAX_CHARS) + "…"

    private fun texts(screenText: String) = screenText.lines().mapNotNull { quoted(it) }

    /** "текст: «2 + 2 = 4.»" / "[3] кнопка «Send»" → the text inside the quotes. */
    private fun quoted(line: String) = Regex("«(.*)»").find(line)?.groupValues?.get(1)?.trim()
}
