package com.dmytro.leo.tools

import com.dmytro.leo.accessibility.RiskyActions
import com.dmytro.leo.accessibility.ScreenResult
import com.dmytro.leo.accessibility.UiAgentService
import com.dmytro.leo.accessibility.UiElement
import kotlinx.serialization.json.JsonObject

/** The screen, as the UI agent tools see it. The Android one is the accessibility service; tests use a fake. */
interface ScreenAgent {
    suspend fun read(): ScreenResult
    suspend fun click(id: Int): ScreenResult
    suspend fun type(id: Int, text: String): ScreenResult
    suspend fun scroll(id: Int?, forward: Boolean): ScreenResult
    suspend fun back(): ScreenResult
    /** An element of the last screen read. */
    fun element(id: Int): UiElement?
    val currentPackage: String?
}

class AccessibilityScreenAgent : ScreenAgent {
    private val service get() = UiAgentService.instance
    override suspend fun read() = service?.read() ?: ScreenResult.NotEnabled
    override suspend fun click(id: Int) = service?.click(id) ?: ScreenResult.NotEnabled
    override suspend fun type(id: Int, text: String) = service?.type(id, text) ?: ScreenResult.NotEnabled
    override suspend fun scroll(id: Int?, forward: Boolean) = service?.scroll(id, forward) ?: ScreenResult.NotEnabled
    override suspend fun back() = service?.back() ?: ScreenResult.NotEnabled
    override fun element(id: Int) = service?.element(id)
    override val currentPackage get() = service?.currentPackage
}

/** At most [max] screen actions per user phrase, so a confused model can't click around forever. */
class UiStepLimiter(private val turns: UserTurns, private val max: Int = 20) {
    private var turn = -1L
    private var steps = 0

    fun allow(): Boolean {
        if (turns.count != turn) {
            turn = turns.count
            steps = 0
        }
        return ++steps <= max
    }
}

private fun ScreenResult.toToolResult(): ToolResult = when (this) {
    is ScreenResult.Ok -> ToolResult.Success(screen.text)
    ScreenResult.NotEnabled -> ToolResult.Failure(
        "Керування екраном вимкнене. Попроси користувача увімкнути «Лео: керування екраном» у налаштуваннях Лео."
    )
    ScreenResult.NoWindow -> ToolResult.Failure("Не бачу на екрані жодної програми. Спершу відкрий її через open_app.")
    is ScreenResult.Blocked -> ToolResult.Failure(
        "«$app» — банк, гаманець чи паролі: тут я нічого не читаю й не натискаю. Скажи про це користувачу."
    )
    is ScreenResult.Failed -> ToolResult.Failure(message)
}

private const val TOO_MANY_STEPS = "Забагато дій поспіль. Зупинись і спитай користувача, чи продовжувати."

private fun JsonObject.elementId(): Int? = string("element")?.trim('[', ']', ' ')?.toIntOrNull()

class UiReadScreenTool(private val screen: ScreenAgent, private val limiter: UiStepLimiter) : Tool {
    override val name = "ui_read_screen"
    override val description =
        "Подивитися, що зараз на екрані програми: кнопки, поля й тексти з номерами [N]. " +
            "Для дій, яких немає серед інших інструментів (увімкнути перемішування, знайти щось у програмі). " +
            "Не читай екран без потреби: його текст іде в Gemini."
    override val parameters = objectSchema()

    override suspend fun execute(args: JsonObject): ToolResult =
        if (!limiter.allow()) ToolResult.Failure(TOO_MANY_STEPS) else screen.read().toToolResult()
}

class UiClickTool(
    private val screen: ScreenAgent,
    private val limiter: UiStepLimiter,
    private val gate: ConfirmationGate,
) : Tool {
    override val name = "ui_click"
    override val description =
        "Натиснути елемент екрана за номером з ui_read_screen. Повертає новий вигляд екрана. " +
            "Незворотні дії (видалити, оплатити, надіслати…) виконуються лише після підтвердження користувача."
    override val parameters = objectSchema(
        Param("element", "Номер елемента, напр. «5»"),
        Param("confirmation", "Токен підтвердження, якщо інструмент його просив", required = false),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val id = args.elementId() ?: return ToolResult.Failure("Вкажи номер елемента")
        val element = screen.element(id) ?: return ToolResult.Failure("Елемента [$id] немає — прочитай екран ще раз")
        if (RiskyActions.isRisky(element.label)) {
            val key = "ui_click|${screen.currentPackage}|${element.label}"
            if (!gate.confirm(args.string("confirmation"), key)) {
                return ToolResult.NeedsConfirmation("Натиснути «${element.label}»? Це може бути незворотно.", gate.ask(key))
            }
        }
        if (!limiter.allow()) return ToolResult.Failure(TOO_MANY_STEPS)
        return screen.click(id).toToolResult()
    }
}

class UiTypeTool(private val screen: ScreenAgent, private val limiter: UiStepLimiter) : Tool {
    override val name = "ui_type"
    override val description = "Ввести текст у поле екрана за номером з ui_read_screen (замінює те, що там було)."
    override val parameters = objectSchema(Param("element", "Номер поля"), Param("text", "Що ввести"))

    override suspend fun execute(args: JsonObject): ToolResult {
        val id = args.elementId() ?: return ToolResult.Failure("Вкажи номер поля")
        val text = args.string("text") ?: return ToolResult.Failure("Не вказано текст")
        val element = screen.element(id) ?: return ToolResult.Failure("Поля [$id] немає — прочитай екран ще раз")
        if (element.password) return ToolResult.Failure("У поля паролів я нічого не вводжу. Нехай користувач введе сам.")
        if (!limiter.allow()) return ToolResult.Failure(TOO_MANY_STEPS)
        return screen.type(id, text).toToolResult()
    }
}

class UiScrollTool(private val screen: ScreenAgent, private val limiter: UiStepLimiter) : Tool {
    override val name = "ui_scroll"
    override val description = "Прокрутити екран або список, щоб побачити більше. Повертає новий вигляд екрана."
    override val parameters = objectSchema(
        Param("direction", "down — далі (вниз), up — назад (вгору)", enum = listOf("down", "up")),
        Param("element", "Номер списку, якщо їх кілька", required = false),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!limiter.allow()) return ToolResult.Failure(TOO_MANY_STEPS)
        return screen.scroll(args.elementId(), forward = args.string("direction") != "up").toToolResult()
    }
}

class UiBackTool(private val screen: ScreenAgent, private val limiter: UiStepLimiter) : Tool {
    override val name = "ui_back"
    override val description = "Натиснути системну кнопку «Назад». Повертає новий вигляд екрана."
    override val parameters = objectSchema()

    override suspend fun execute(args: JsonObject): ToolResult =
        if (!limiter.allow()) ToolResult.Failure(TOO_MANY_STEPS) else screen.back().toToolResult()
}
