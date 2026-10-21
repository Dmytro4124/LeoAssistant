package com.dmytro.leo.tools

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.dmytro.leo.reminders.ReminderTime
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.JsonObject
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeParseException

/** An event in the user's calendar. */
data class CalendarEvent(val id: Long, val title: String, val start: Instant, val end: Instant, val allDay: Boolean, val location: String?)

/** The phone's calendar (synced with Google Calendar). Tests use a fake. */
interface CalendarSource {
    val hasAccess: Boolean
    suspend fun add(title: String, start: Instant, end: Instant, allDay: Boolean, location: String?, reminderMinutes: Int?): Long?
    suspend fun between(from: Instant, to: Instant): List<CalendarEvent>
    suspend fun delete(id: Long): Boolean
}

private const val NO_ACCESS = "Немає доступу до календаря: користувач надає його в налаштуваннях Leo, розділ «Нагадування»."

/** "Запиши в календар зустріч з Олегом у п'ятницю о 15:00". */
class AddCalendarEventTool(private val calendar: CalendarSource, private val now: () -> ZonedDateTime = { ZonedDateTime.now() }) : Tool {
    override val name = "add_calendar_event"
    override val description =
        "Додати подію в Google Календар користувача: зустрічі, візити, поїздки, дні народження — те, що має бути в календарі. " +
            "Прості «нагадай…» — це set_reminder."
    override val parameters = objectSchema(
        Param("title", "Назва події: «Зустріч з Олегом»"),
        Param("start", "Початок, місцевий час «2026-10-24T15:00»; для події на весь день — дата «2026-10-24»"),
        Param("end", "Кінець (за замовчуванням через годину)", required = false),
        Param("location", "Місце", required = false),
        Param("reminder_minutes", "За скільки хвилин нагадати (за замовчуванням календар вирішує сам)", required = false),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!calendar.hasAccess) return ToolResult.Failure(NO_ACCESS)
        val title = args.string("title") ?: return ToolResult.Failure("Не вказано назву події")
        val zone = now().zone
        val startText = args.string("start") ?: return ToolResult.Failure("Не вказано, коли подія")
        val allDay = startText.trim().length == 10
        val (start, end) = try {
            if (allDay) {
                // All-day events are stored in UTC midnight-to-midnight
                val day = LocalDate.parse(startText.trim())
                day.atStartOfDay().toInstant(ZoneOffset.UTC) to day.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
            } else {
                val s = LocalDateTime.parse(startText.trim()).atZone(zone).toInstant()
                val e = args.string("end")?.let { LocalDateTime.parse(it.trim()).atZone(zone).toInstant() } ?: s.plus(Duration.ofHours(1))
                s to e
            }
        } catch (e: DateTimeParseException) {
            return ToolResult.Failure("Не розумію час: потрібно «2026-10-24T15:00» або дата «2026-10-24»")
        }
        if (!end.isAfter(start)) return ToolResult.Failure("Кінець події має бути пізніше за початок")
        val id = calendar.add(title, start, end, allDay, args.string("location"), args.string("reminder_minutes")?.toIntOrNull())
            ?: return ToolResult.Failure("Не вдалося додати подію: на телефоні немає календаря, куди можна записати")
        val whenText = if (allDay) "на ${startText.trim()} (весь день)" else ReminderTime.describe(start, now())
        return ToolResult.Success("Додав у календар: «$title», $whenText [подія $id]")
    }
}

/** "Що в мене завтра?", "чи я вільний у п'ятницю після обіду?". */
class ListCalendarEventsTool(private val calendar: CalendarSource, private val now: () -> ZonedDateTime = { ZonedDateTime.now() }) : Tool {
    override val name = "list_calendar_events"
    override val description = "Події в календарі користувача за період: «що в мене завтра», «які плани на вихідні», «чи я вільний…»."
    override val parameters = objectSchema(
        Param("from", "Початок періоду, дата «2026-10-22» або час «2026-10-22T14:00»"),
        Param("to", "Кінець періоду (включно для дати)", required = false),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!calendar.hasAccess) return ToolResult.Failure(NO_ACCESS)
        val current = now()
        fun parse(text: String, endOfDay: Boolean): Instant {
            val t = text.trim()
            return if (t.length == 10) LocalDate.parse(t).let { if (endOfDay) it.plusDays(1) else it }.atStartOfDay(current.zone).toInstant()
            else LocalDateTime.parse(t).atZone(current.zone).toInstant()
        }
        val (from, to) = try {
            val f = parse(args.string("from") ?: return ToolResult.Failure("Не вказано період"), endOfDay = false)
            f to (args.string("to")?.let { parse(it, endOfDay = true) } ?: parse(args.string("from")!!, endOfDay = true))
        } catch (e: DateTimeParseException) {
            return ToolResult.Failure("Не розумію дату: потрібно «2026-10-22»")
        }
        val events = calendar.between(from, to)
        if (events.isEmpty()) return ToolResult.Success("У календарі за цей період нічого немає")
        return ToolResult.Success(events.joinToString("\n") { e ->
            val time = if (e.allDay) "весь день" else ReminderTime.describe(e.start, current)
            "[подія ${e.id}] $time — ${e.title}${e.location?.takeIf { it.isNotBlank() }?.let { ", $it" } ?: ""}"
        })
    }
}

/** "Видали зустріч з Олегом" — only after the user's "так". */
class DeleteCalendarEventTool(private val calendar: CalendarSource, private val gate: ConfirmationGate) : Tool {
    override val name = "delete_calendar_event"
    override val description = "Видалити подію з календаря за номером (з add_calendar_event чи list_calendar_events). Потребує підтвердження."
    override val parameters = objectSchema(
        Param("id", "Номер події"),
        Param("title", "Назва події — для питання користувачу"),
        Param("confirmation", "Токен підтвердження, якщо інструмент його просив", required = false),
    )

    override suspend fun execute(args: JsonObject): ToolResult {
        if (!calendar.hasAccess) return ToolResult.Failure(NO_ACCESS)
        val id = args.string("id")?.filter { it.isDigit() }?.toLongOrNull() ?: return ToolResult.Failure("Не вказано номер події")
        val key = "delete_calendar_event|$id"
        if (!gate.confirm(args.string("confirmation"), key)) {
            return ToolResult.NeedsConfirmation("Видалити з календаря «${args.string("title") ?: "подію"}»?", gate.ask(key))
        }
        return if (calendar.delete(id)) ToolResult.Success("Видалив подію з календаря") else ToolResult.Failure("Такої події немає")
    }
}

class AndroidCalendar(context: Context) : CalendarSource {
    private val appContext = context.applicationContext
    private val resolver = appContext.contentResolver

    override val hasAccess: Boolean
        get() = listOf(Manifest.permission.READ_CALENDAR, Manifest.permission.WRITE_CALENDAR)
            .all { ContextCompat.checkSelfPermission(appContext, it) == PackageManager.PERMISSION_GRANTED }

    override suspend fun add(title: String, start: Instant, end: Instant, allDay: Boolean, location: String?, reminderMinutes: Int?): Long? =
        withContext(Dispatchers.IO) {
            val calendarId = writableCalendar() ?: return@withContext null
            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, calendarId)
                put(CalendarContract.Events.TITLE, title)
                put(CalendarContract.Events.DTSTART, start.toEpochMilli())
                put(CalendarContract.Events.DTEND, end.toEpochMilli())
                put(CalendarContract.Events.ALL_DAY, if (allDay) 1 else 0)
                put(CalendarContract.Events.EVENT_TIMEZONE, if (allDay) "UTC" else java.util.TimeZone.getDefault().id)
                location?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
            }
            val id = resolver.insert(CalendarContract.Events.CONTENT_URI, values)?.let { ContentUris.parseId(it) } ?: return@withContext null
            reminderMinutes?.let { minutes ->
                resolver.insert(CalendarContract.Reminders.CONTENT_URI, ContentValues().apply {
                    put(CalendarContract.Reminders.EVENT_ID, id)
                    put(CalendarContract.Reminders.MINUTES, minutes)
                    put(CalendarContract.Reminders.METHOD, CalendarContract.Reminders.METHOD_ALERT)
                })
            }
            id
        }

    override suspend fun between(from: Instant, to: Instant): List<CalendarEvent> = withContext(Dispatchers.IO) {
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
        )
        val events = mutableListOf<CalendarEvent>()
        CalendarContract.Instances.query(resolver, projection, from.toEpochMilli(), to.toEpochMilli())?.use { c ->
            while (c.moveToNext()) {
                events += CalendarEvent(
                    id = c.getLong(0), title = c.getString(1).orEmpty(), start = Instant.ofEpochMilli(c.getLong(2)),
                    end = Instant.ofEpochMilli(c.getLong(3)), allDay = c.getInt(4) == 1, location = c.getString(5),
                )
            }
        }
        events.sortedBy { it.start }.take(MAX_EVENTS)
    }

    override suspend fun delete(id: Long): Boolean = withContext(Dispatchers.IO) {
        resolver.delete(ContentUris.withAppendedId(CalendarContract.Events.CONTENT_URI, id), null, null) > 0
    }

    /** The primary Google calendar if there is one, otherwise any calendar we may write to. */
    private fun writableCalendar(): Long? {
        val projection = arrayOf(
            CalendarContract.Calendars._ID, CalendarContract.Calendars.IS_PRIMARY, CalendarContract.Calendars.ACCOUNT_TYPE,
        )
        val selection = "${CalendarContract.Calendars.VISIBLE} = 1 AND ${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}"
        val found = mutableListOf<Triple<Long, Boolean, String>>()
        resolver.query(CalendarContract.Calendars.CONTENT_URI, projection, selection, null, null)?.use { c ->
            while (c.moveToNext()) found += Triple(c.getLong(0), c.getInt(1) == 1, c.getString(2).orEmpty())
        }
        return (found.firstOrNull { it.second && it.third == "com.google" } ?: found.firstOrNull { it.second } ?: found.firstOrNull())?.first
    }

    private companion object {
        const val MAX_EVENTS = 30
    }
}
