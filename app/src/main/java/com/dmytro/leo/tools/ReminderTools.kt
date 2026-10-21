package com.dmytro.leo.tools

import com.dmytro.leo.data.PlacesStore
import com.dmytro.leo.reminders.PlaceFinder
import com.dmytro.leo.reminders.Reminder
import com.dmytro.leo.reminders.ReminderTime
import com.dmytro.leo.reminders.Reminders
import com.dmytro.leo.reminders.Repeat
import kotlinx.serialization.json.JsonObject
import java.time.Instant
import java.time.ZonedDateTime

private val TIME_PARAMS = arrayOf(
    Param("at", "Місцевий час «2026-10-22T12:30». Порахуй з поточної дати й часу з промпту", required = false),
    Param("in_minutes", "Або через скільки хвилин від зараз: «20» (для «через 20 хвилин», «через годину» = 60)", required = false),
    Param("repeat", "Повторення", required = false, enum = Repeat.VALUES),
)

private fun when_(r: Reminder, now: ZonedDateTime): String {
    val repeat = if (r.repeat != Repeat.None) " (${r.repeat.label})" else ""
    return when {
        r.at != null -> ReminderTime.describe(Instant.ofEpochMilli(r.at), now) + repeat
        r.place != null -> "біля «${r.place.name}»" + if (r.repeat != Repeat.None) " (щоразу)" else ""
        else -> "у списку справ"
    }
}

private fun timeOf(args: JsonObject, now: ZonedDateTime): ReminderTime.Parsed? =
    ReminderTime.parse(args.string("at"), args.string("in_minutes"), now)

/** "Нагадай завтра о 12:30 піти по хліб", "коли буду біля АТБ, нагадай про батарейки". */
class SetReminderTool(
    private val reminders: Reminders,
    private val places: PlacesStore,
    private val finder: PlaceFinder,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) : Tool {
    override val name = "set_reminder"
    override val description =
        "Поставити нагадування: на час (at або in_minutes, з повторенням repeat) або за місцем (place — нагадає, " +
            "коли користувач буде поруч). Неоднозначний час («о пів на першу» — вдень чи вночі?) спершу уточни. " +
            "Справи без часу й місця — add_todo; щоб Leo сам щось зробив у певний час — schedule_task; події й зустрічі — add_calendar_event."
    override val parameters = objectSchema(
        Param("text", "Про що нагадати, коротко: «піти в магазин по хліб»"),
        *TIME_PARAMS,
        Param("place", "Місце: збережене («дім», «робота») або конкретна адреса чи заклад з містом", required = false),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val text = args.string("text") ?: return ToolResult.Failure("Не вказано, про що нагадати")
        val current = now()
        val repeat = Repeat.parse(args.string("repeat"))
        args.string("place")?.takeIf { it.isNotBlank() }?.let { placeName ->
            if (!reminders.canUsePlaces) {
                return ToolResult.Failure(
                    "Для нагадувань за місцем Leo потрібен доступ до геолокації «Завжди»: користувач надає його в налаштуваннях Leo, розділ «Нагадування»."
                )
            }
            val query = places.find(placeName) ?: placeName
            val place = finder.find(query)?.copy(name = placeName)
                ?: return ToolResult.Failure("Не знайшов місце «$placeName» на мапі — попроси точнішу адресу або назву з містом")
            val saved = reminders.add(Reminder(0, text, place = place, repeat = if (repeat == Repeat.None) Repeat.None else Repeat.Daily))
            return ToolResult.Success("Нагадаю ${when_(saved, current)}: $text")
        }
        return when (val time = timeOf(args, current)) {
            null -> ToolResult.Failure("Не вказано ні час, ні місце. Якщо часу немає — це справа для add_todo")
            is ReminderTime.Parsed.Bad -> ToolResult.Failure(time.message)
            is ReminderTime.Parsed.Ok -> {
                val saved = reminders.add(Reminder(0, text, at = time.at.toEpochMilli(), repeat = repeat))
                val late = if (!reminders.canUseExactTimes) " (без дозволу на точні будильники може запізнитися на кілька хвилин)" else ""
                ToolResult.Success("Нагадаю ${when_(saved, current)}: $text$late")
            }
        }
    }
}

/** "Додай у список: купити батарейки". */
class AddTodoTool(private val reminders: Reminders) : Tool {
    override val name = "add_todo"
    override val description = "Додати справу в список справ користувача (без часу): «додай у список…», «запиши, що треба…»."
    override val parameters = objectSchema(Param("text", "Справа коротко: «купити батарейки»"))

    override suspend fun execute(args: JsonObject): ToolResult {
        val text = args.string("text") ?: return ToolResult.Failure("Не вказано справу")
        reminders.add(Reminder(0, text))
        return ToolResult.Success("Додав у список справ: $text")
    }
}

/** "О 18:00 надішли Олегу, що я виїжджаю", "щоранку о 8 розкажи погоду". */
class ScheduleTaskTool(
    private val reminders: Reminders,
    private val gate: ConfirmationGate,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
) : Tool {
    override val name = "schedule_task"
    override val description =
        "Запланувати, щоб Leo САМ виконав доручення в певний час: надіслав повідомлення, розповів погоду, " +
            "перевірив щось і сказав результат. instruction — повне доручення так, ніби користувач скаже його в той момент " +
            "(з іменами й текстом повідомлення). Потребує підтвердження. Екран у цей час може бути заблокований, тож дії через екран можуть не вдатися."
    override val parameters = objectSchema(
        Param("instruction", "Що зробити, повністю: «Надішли Олегу в Telegram: Виїжджаю, буду о 19:00»"),
        *TIME_PARAMS,
        Param("confirmation", "Токен підтвердження, якщо інструмент його просив", required = false),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val instruction = args.string("instruction") ?: return ToolResult.Failure("Не вказано, що зробити")
        val current = now()
        val at = when (val time = timeOf(args, current)) {
            null -> return ToolResult.Failure("Не вказано, коли виконати")
            is ReminderTime.Parsed.Bad -> return ToolResult.Failure(time.message)
            is ReminderTime.Parsed.Ok -> time.at
        }
        val repeat = Repeat.parse(args.string("repeat"))
        val draft = Reminder(0, instruction, at = at.toEpochMilli(), repeat = repeat, task = instruction)
        val key = "schedule_task|$instruction|${args.string("at")}|${args.string("in_minutes")}|$repeat"
        if (!gate.confirm(args.string("confirmation"), key)) {
            return ToolResult.NeedsConfirmation("${when_(draft, current).replaceFirstChar { it.uppercase() }} виконати: «$instruction»?", gate.ask(key))
        }
        val saved = reminders.add(draft)
        return ToolResult.Success("Заплановано ${when_(saved, current)}: $instruction")
    }
}

/** "Які в мене нагадування на завтра?", "що в списку справ?". */
class ListRemindersTool(private val reminders: Reminders, private val now: () -> ZonedDateTime = { ZonedDateTime.now() }) : Tool {
    override val name = "list_reminders"
    override val description = "Перелічити нагадування, заплановані завдання й список справ. Читай коротко, найближчі першими."
    override val parameters = objectSchema(
        Param("kind", "Що саме", required = false, enum = listOf("all", "reminders", "todo", "tasks")),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val current = now()
        val items = reminders.store.all().filter { !it.fired }.filter {
            when (args.string("kind")) {
                "reminders" -> it.kind == Reminder.Kind.Time || it.kind == Reminder.Kind.Place
                "todo" -> it.kind == Reminder.Kind.Todo
                "tasks" -> it.kind == Reminder.Kind.Task
                else -> true
            }
        }.sortedWith(compareBy(nullsLast()) { it.at })
        if (items.isEmpty()) return ToolResult.Success("Нічого немає")
        return ToolResult.Success(items.joinToString("\n") { r ->
            val what = if (r.task != null) "завдання: ${r.task}" else r.text
            "[${r.id}] ${when_(r, current)} — $what"
        })
    }
}

/** "Перенеси хліб на 14:00", "зроби його щоденним". */
class UpdateReminderTool(private val reminders: Reminders, private val now: () -> ZonedDateTime = { ZonedDateTime.now() }) : Tool {
    override val name = "update_reminder"
    override val description = "Змінити нагадування чи справу за номером: текст, час, повторення."
    override val parameters = objectSchema(
        Param("id", "Номер з промпту або list_reminders"),
        Param("text", "Новий текст", required = false),
        *TIME_PARAMS,
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        val current = now()
        val old = args.string("id")?.trim()?.toIntOrNull()?.let { reminders.store.get(it) }
            ?: return ToolResult.Failure("Такого нагадування немає")
        if (old.task != null) return ToolResult.Failure("Заплановане завдання не змінюю: видали його й заплануй заново через schedule_task")
        var updated = old.copy(text = args.string("text")?.takeIf { it.isNotBlank() } ?: old.text, fired = false)
        when (val time = timeOf(args, current)) {
            is ReminderTime.Parsed.Bad -> return ToolResult.Failure(time.message)
            is ReminderTime.Parsed.Ok -> updated = updated.copy(at = time.at.toEpochMilli(), place = null)
            null -> Unit
        }
        args.string("repeat")?.let { updated = updated.copy(repeat = Repeat.parse(it)) }
        reminders.update(updated)
        return ToolResult.Success("Змінив: ${when_(updated, current)} — ${updated.text}")
    }
}

/** "Скасуй нагадування про звіт", "хліб купив" (справу виконано). */
class RemoveRemindersTool(private val reminders: Reminders) : Tool {
    override val name = "remove_reminders"
    override val description = "Прибрати нагадування, завдання чи справи за номерами — скасовані або виконані («купив хліб», «скасуй…»)."
    override val parameters = objectSchema(Param("ids", "Номери через кому: «3» або «3, 7»"))

    override suspend fun execute(args: JsonObject): ToolResult {
        val ids = args.string("ids").orEmpty().split(Regex("[^0-9]+")).mapNotNull { it.toIntOrNull() }.toSet()
        val removed = reminders.remove(ids)
        if (removed.isEmpty()) return ToolResult.Failure("Таких нагадувань немає")
        return ToolResult.Success("Прибрав: ${removed.joinToString("; ") { it.task ?: it.text }}")
    }
}
