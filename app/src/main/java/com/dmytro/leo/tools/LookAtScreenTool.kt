package com.dmytro.leo.tools

import com.dmytro.leo.accessibility.Snapshot
import com.dmytro.leo.brain.Content
import com.dmytro.leo.brain.GeminiApi
import com.dmytro.leo.brain.GeminiResult
import com.dmytro.leo.brain.GenerateContentRequest
import com.dmytro.leo.brain.GenerationConfig
import com.dmytro.leo.brain.InlineData
import com.dmytro.leo.brain.Part
import com.dmytro.leo.brain.toUserMessage
import kotlinx.serialization.json.JsonObject
import java.util.Base64

/**
 * "Leo, подивись…": takes a screenshot and asks Gemini (which understands images) about it.
 * Sees what text reading can't: photos, pictures, charts, maps, games.
 */
class LookAtScreenTool(private val screen: ScreenAgent, private val vision: GeminiApi) : Tool {

    override val name = "look_at_screen"
    override val description =
        "Зробити знімок екрана й подивитися на нього: «подивись, що тут», «що на цьому фото», «що на екрані», " +
            "«прочитай, що тут написано» на картинці. Бачить фото, картинки, графіки, мапи, ігри. " +
            "Знімок робиться лише на прохання і йде в Gemini. Для натискання кнопок — ui_read_screen."
    override val parameters = objectSchema(
        Param("question", "Що саме цікавить користувача, його словами: «що це за будівля», «про що цей мем». Без питання — опиши екран", required = false),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val question = args.string("question")
        val jpeg = when (val shot = screen.screenshot()) {
            is Snapshot.Image -> shot.jpeg
            Snapshot.NotEnabled -> return ToolResult.Failure(
                "Знімок екрана недоступний: попроси користувача увімкнути «Leo: керування екраном» у налаштуваннях Leo."
            )
            is Snapshot.Blocked -> return ToolResult.Failure(
                "На екрані «${shot.app}» — банк, гаманець чи паролі: такий екран я не знімаю. Скажи про це користувачу."
            )
            is Snapshot.Failed -> return ToolResult.Failure(shot.message)
        }

        val request = GenerateContentRequest(
            contents = listOf(
                Content(
                    role = "user",
                    parts = listOf(
                        Part(text = LookAtScreen.prompt(question)),
                        Part(inlineData = InlineData("image/jpeg", Base64.getEncoder().encodeToString(jpeg))),
                    ),
                )
            ),
            generationConfig = GenerationConfig(maxOutputTokens = 800),
        )
        return when (val result = vision.generateContent(request)) {
            is GeminiResult.Success -> result.text.takeIf { it.isNotBlank() }
                ?.let { ToolResult.Success("На знімку екрана: $it") }
                ?: ToolResult.Failure("Не вдалося роздивитися знімок")
            is GeminiResult.Failure -> ToolResult.Failure("Не вдалося роздивитися знімок: ${result.error.toUserMessage()}")
        }
    }
}

/** Pure Kotlin, unit-tested. */
object LookAtScreen {
    fun prompt(question: String?) = buildString {
        append("Це знімок екрана телефона користувача (поверх може бути панель асистента Leo — не зважай на неї). ")
        if (question != null) append("Користувач питає: «$question». Відповідай саме на це. ")
        else append("Опиши, що на екрані: що це за програма і що на ній головне. ")
        append(
            "Відповідай українською, коротко й точно; потрібний текст з екрана цитуй. Не вигадуй того, чого не видно. " +
                "Номери телефонів, адреси пошти, коди й паролі не повторюй."
        )
    }
}
