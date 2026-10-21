package com.dmytro.leo.reminders

import android.content.Context
import com.dmytro.leo.brain.GeminiJson
import kotlinx.serialization.Serializable
import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZonedDateTime

/** Reminders, to-dos and scheduled tasks, kept on the phone. */
interface ReminderStore {
    fun all(): List<Reminder>
    fun get(id: Int): Reminder? = all().firstOrNull { it.id == id }

    /** Saves a new one, giving it an id. */
    fun add(reminder: Reminder): Reminder

    fun update(reminder: Reminder)
    fun remove(ids: Collection<Int>)

    /** Upcoming reminders and the to-do list for the system prompt, or "" if there are none. */
    fun promptNote(now: ZonedDateTime): String {
        val items = all().filter { !it.fired }
        if (items.isEmpty()) return ""
        val timed = items.filter { it.at != null }.sortedBy { it.at }.take(MAX_IN_PROMPT)
        val places = items.filter { it.kind == Reminder.Kind.Place }.take(MAX_IN_PROMPT)
        val todos = items.filter { it.kind == Reminder.Kind.Todo }.take(MAX_IN_PROMPT)
        return buildString {
            append("Нагадування і справи користувача (номер у дужках — для update_reminder / remove_reminders):")
            timed.forEach { r ->
                val what = if (r.task != null) "завдання: ${r.task}" else r.text
                val repeat = if (r.repeat != Repeat.None) ", ${r.repeat.label}" else ""
                append("\n- [${r.id}] ${ReminderTime.describe(Instant.ofEpochMilli(r.at!!), now)}$repeat — $what")
            }
            places.forEach { append("\n- [${it.id}] біля «${it.place!!.name}» — ${it.text}") }
            if (todos.isNotEmpty()) {
                append("\nСписок справ:")
                todos.forEach { append("\n- [${it.id}] ${it.text}") }
            }
        }
    }

    private companion object {
        const val MAX_IN_PROMPT = 15
    }
}

/** In a JSON file in the app's private storage. */
class FileReminderStore(context: Context) : ReminderStore {

    private val file = File(context.applicationContext.filesDir, "reminders.json")

    @Serializable
    private data class Saved(val nextId: Int = 1, val items: List<Reminder> = emptyList())

    private val lock = Any()
    private var saved: Saved = load()

    override fun all(): List<Reminder> = synchronized(lock) { saved.items }

    override fun add(reminder: Reminder): Reminder = synchronized(lock) {
        val withId = reminder.copy(id = saved.nextId)
        write(Saved(saved.nextId + 1, saved.items + withId))
        withId
    }

    override fun update(reminder: Reminder) = synchronized(lock) {
        write(saved.copy(items = saved.items.map { if (it.id == reminder.id) reminder else it }))
    }

    override fun remove(ids: Collection<Int>) = synchronized(lock) {
        write(saved.copy(items = saved.items.filter { it.id !in ids }))
    }

    private fun load(): Saved = try {
        if (file.exists()) GeminiJson.decodeFromString<Saved>(file.readText()) else Saved()
    } catch (e: Exception) {
        Saved()
    }

    private fun write(value: Saved) {
        saved = value
        try {
            val tmp = File(file.parentFile, "${file.name}.tmp")
            tmp.writeText(GeminiJson.encodeToString(value))
            if (!tmp.renameTo(file)) {
                file.delete()
                tmp.renameTo(file)
            }
        } catch (e: IOException) {
            // Still in memory for this run
        }
    }
}
