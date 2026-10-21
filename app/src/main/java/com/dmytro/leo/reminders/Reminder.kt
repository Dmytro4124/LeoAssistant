package com.dmytro.leo.reminders

import kotlinx.serialization.Serializable
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * Something Leo keeps for the user: a reminder at a time ([at]) or at a place ([place]),
 * a to-do item (neither), or a task Leo itself carries out at the time ([task]).
 */
@Serializable
data class Reminder(
    val id: Int,
    val text: String,
    /** Next time it fires (epoch ms), for time reminders and tasks. */
    val at: Long? = null,
    val repeat: Repeat = Repeat.None,
    val place: Place? = null,
    /** For a scheduled task: what Leo should do (already confirmed by the user). */
    val task: String? = null,
    val createdAt: Long = 0,
    /** A one-off that has gone off: kept (for "відкласти") until dismissed, no longer scheduled. */
    val fired: Boolean = false,
) {
    val kind: Kind
        get() = when {
            task != null -> Kind.Task
            place != null -> Kind.Place
            at != null -> Kind.Time
            else -> Kind.Todo
        }

    enum class Kind { Time, Place, Todo, Task }
}

@Serializable
data class Place(val name: String, val latitude: Double, val longitude: Double, val radiusM: Float = 200f)

@Serializable
enum class Repeat(val label: String) {
    None("один раз"),
    Daily("щодня"),
    Weekdays("по буднях"),
    Weekly("щотижня"),
    Monthly("щомісяця"),
    Yearly("щороку"),
    ;

    /** The next occurrence after [now], keeping the original time of day (null for [None]). */
    fun next(previous: Instant, now: Instant, zone: ZoneId): Instant? {
        if (this == None) return null
        var t = previous.atZone(zone)
        while (!t.toInstant().isAfter(now)) {
            t = when (this) {
                Daily -> t.plusDays(1)
                Weekdays -> t.plusDays(if (t.dayOfWeek == DayOfWeek.FRIDAY) 3 else if (t.dayOfWeek == DayOfWeek.SATURDAY) 2 else 1)
                Weekly -> t.plusWeeks(1)
                Monthly -> t.plusMonths(1)
                Yearly -> t.plusYears(1)
                None -> return null
            }
        }
        return t.toInstant()
    }

    companion object {
        fun parse(value: String?): Repeat = when (value?.trim()?.lowercase()) {
            "daily" -> Daily
            "weekdays" -> Weekdays
            "weekly" -> Weekly
            "monthly" -> Monthly
            "yearly" -> Yearly
            else -> None
        }

        val VALUES = listOf("none", "daily", "weekdays", "weekly", "monthly", "yearly")
    }
}

/** Reading the time the model gives and saying it back. Pure Kotlin, unit-tested. */
object ReminderTime {

    private val ukrainian = Locale.forLanguageTag("uk-UA")
    private val dayFormat = DateTimeFormatter.ofPattern("EEEE, d MMMM", ukrainian)
    private val timeFormat = DateTimeFormatter.ofPattern("HH:mm", ukrainian)

    sealed interface Parsed {
        data class Ok(val at: Instant) : Parsed
        data class Bad(val message: String) : Parsed
    }

    /**
     * "2026-10-22T12:30" (local time) or [inMinutes] from now. A time that has already passed is
     * refused — the model should ask which day was meant, not set a reminder for the past.
     */
    fun parse(at: String?, inMinutes: String?, now: ZonedDateTime): Parsed? {
        inMinutes?.trim()?.toLongOrNull()?.let { minutes ->
            if (minutes <= 0) return Parsed.Bad("Кількість хвилин має бути більшою за нуль")
            return Parsed.Ok(now.plusMinutes(minutes).truncatedTo(ChronoUnit.SECONDS).toInstant())
        }
        val text = at?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        val local = try {
            LocalDateTime.parse(text.replace(' ', 'T').let { if (it.length == 16) "$it:00" else it })
        } catch (e: DateTimeParseException) {
            return Parsed.Bad("Не розумію час «$text»: потрібно у форматі 2026-10-22T12:30")
        }
        val instant = local.atZone(now.zone).toInstant()
        if (!instant.isAfter(now.toInstant())) {
            return Parsed.Bad("Час ${describe(instant, now)} уже минув — уточни в користувача день")
        }
        return Parsed.Ok(instant)
    }

    /** "сьогодні о 18:00", "завтра о 12:30", "у п'ятницю, 24 жовтня, о 9:00". */
    fun describe(at: Instant, now: ZonedDateTime): String {
        val t = at.atZone(now.zone)
        val days = ChronoUnit.DAYS.between(now.toLocalDate(), t.toLocalDate())
        val day = when (days) {
            0L -> "сьогодні"
            1L -> "завтра"
            2L -> "післязавтра"
            else -> dayFormat.format(t)
        }
        return "$day о ${timeFormat.format(t)}"
    }
}
