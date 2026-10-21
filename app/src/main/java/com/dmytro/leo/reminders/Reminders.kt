package com.dmytro.leo.reminders

import java.time.Instant
import java.time.ZonedDateTime

/** How a reminder reaches the user when it goes off. */
interface ReminderAlerts {
    /** A notification with "Готово" / "Відкласти" (replaces an earlier one for the same reminder). */
    fun show(reminder: Reminder, text: String)
    fun dismiss(id: Int)

    /** Says it aloud when that is appropriate (not on silent, not during a call or a conversation). */
    suspend fun speak(text: String)
}

/**
 * Everything about reminders in one place: the tools and the settings screen change them
 * through here, the system calls [fire] / [arrived] when they go off. Pure Kotlin, unit-tested.
 */
class Reminders(
    val store: ReminderStore,
    private val scheduler: ReminderScheduler,
    private val alerts: ReminderAlerts,
    private val now: () -> ZonedDateTime = { ZonedDateTime.now() },
    /** Carries out a scheduled task and returns what Leo said about it. */
    private val runTask: suspend (task: String) -> String = { "" },
) {
    val canUseExactTimes get() = scheduler.canUseExactTimes()
    val canUsePlaces get() = scheduler.canUsePlaces()

    fun add(reminder: Reminder): Reminder {
        val saved = store.add(reminder.copy(createdAt = now().toInstant().toEpochMilli()))
        scheduler.schedule(saved)
        return saved
    }

    fun update(reminder: Reminder) {
        store.update(reminder)
        scheduler.schedule(reminder)
    }

    fun remove(ids: Collection<Int>): List<Reminder> {
        val found = store.all().filter { it.id in ids }
        found.forEach {
            scheduler.cancel(it)
            alerts.dismiss(it.id)
        }
        store.remove(found.map { it.id })
        return found
    }

    /** After a reboot or an update the system forgets alarms: set them all again. */
    fun rescheduleAll() {
        val current = now()
        store.all().forEach { r ->
            when {
                r.fired -> Unit
                // Missed while the phone was off: say it now rather than never
                r.at != null && r.at <= current.toInstant().toEpochMilli() -> scheduler.schedule(r.copy(at = current.toInstant().toEpochMilli() + 5_000))
                else -> scheduler.schedule(r)
            }
        }
    }

    /** The time of reminder [id] has come. */
    suspend fun fire(id: Int) {
        val reminder = store.get(id) ?: return
        if (reminder.fired) return
        val text = if (reminder.task != null) {
            val said = runCatching { runTask(reminder.task) }.getOrElse { "Не вдалося виконати: ${it.message}" }
            "Заплановане завдання «${reminder.task}». ${said}".trim()
        } else {
            "Нагадую: ${reminder.text}"
        }
        alerts.show(reminder, text)
        afterGoingOff(reminder)
        alerts.speak(text)
    }

    /** The user came to the place of reminder [id]. */
    suspend fun arrived(id: Int) {
        val reminder = store.get(id) ?: return
        if (reminder.fired || reminder.place == null) return
        val text = "Ти біля «${reminder.place.name}». Нагадую: ${reminder.text}"
        alerts.show(reminder, text)
        afterGoingOff(reminder)
        alerts.speak(text)
    }

    /** "Відкласти на 10 хвилин": brings a fired reminder back. */
    fun snooze(id: Int, minutes: Long) {
        val reminder = store.get(id) ?: return
        alerts.dismiss(id)
        val at = now().plusMinutes(minutes).toInstant().toEpochMilli()
        // A repeating one keeps its schedule: the snooze is a separate one-off
        if (reminder.repeat != Repeat.None) add(reminder.copy(id = 0, at = at, repeat = Repeat.None, place = null, fired = false))
        else update(reminder.copy(at = at, fired = false, place = null))
    }

    /** "Готово": a fired one-off is done. */
    fun done(id: Int) {
        alerts.dismiss(id)
        val reminder = store.get(id) ?: return
        if (reminder.fired) remove(listOf(id))
    }

    private fun afterGoingOff(reminder: Reminder) {
        val next = reminder.at?.let { reminder.repeat.next(Instant.ofEpochMilli(it), now().toInstant(), now().zone) }
        when {
            next != null -> update(reminder.copy(at = next.toEpochMilli()))
            reminder.kind == Reminder.Kind.Place && reminder.repeat != Repeat.None -> Unit // every time you come
            else -> {
                scheduler.cancel(reminder)
                store.update(reminder.copy(fired = true))
            }
        }
    }
}
