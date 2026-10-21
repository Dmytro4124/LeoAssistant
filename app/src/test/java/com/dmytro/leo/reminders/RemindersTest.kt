package com.dmytro.leo.reminders

import com.dmytro.leo.data.PlacesStore
import com.dmytro.leo.tools.AddCalendarEventTool
import com.dmytro.leo.tools.AddTodoTool
import com.dmytro.leo.tools.CalendarEvent
import com.dmytro.leo.tools.CalendarSource
import com.dmytro.leo.tools.ConfirmationGate
import com.dmytro.leo.tools.ListRemindersTool
import com.dmytro.leo.tools.RemoveRemindersTool
import com.dmytro.leo.tools.ScheduleTaskTool
import com.dmytro.leo.tools.SetReminderTool
import com.dmytro.leo.tools.ToolResult
import com.dmytro.leo.tools.UpdateReminderTool
import com.dmytro.leo.tools.UserTurns
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Instant
import java.time.ZoneId
import java.time.ZonedDateTime

class MemReminderStore : ReminderStore {
    private var nextId = 1
    var items = listOf<Reminder>()
    override fun all() = items
    override fun add(reminder: Reminder) = reminder.copy(id = nextId++).also { items = items + it }
    override fun update(reminder: Reminder) { items = items.map { if (it.id == reminder.id) reminder else it } }
    override fun remove(ids: Collection<Int>) { items = items.filter { it.id !in ids } }
}

class FakeScheduler(var places: Boolean = true) : ReminderScheduler {
    val scheduled = mutableMapOf<Int, Reminder>()
    override fun schedule(reminder: Reminder) { scheduled[reminder.id] = reminder }
    override fun cancel(reminder: Reminder) { scheduled.remove(reminder.id) }
    override fun canUsePlaces() = places
}

class FakeAlerts : ReminderAlerts {
    val shown = mutableListOf<String>()
    val spoken = mutableListOf<String>()
    val dismissed = mutableListOf<Int>()
    override fun show(reminder: Reminder, text: String) { shown += text }
    override fun dismiss(id: Int) { dismissed += id }
    override suspend fun speak(text: String) { spoken += text }
}

class RemindersTest {

    private val zone = ZoneId.of("Europe/Kyiv")
    // Wednesday, 21 October 2026, 10:00
    private var now = ZonedDateTime.of(2026, 10, 21, 10, 0, 0, 0, zone)
    private val store = MemReminderStore()
    private val scheduler = FakeScheduler()
    private val alerts = FakeAlerts()
    private val tasks = mutableListOf<String>()
    private val reminders = Reminders(store, scheduler, alerts, now = { now }, runTask = { tasks += it; "Надіслав Олегу «Виїжджаю»." })

    private fun at(day: Int, hour: Int, minute: Int = 0) = ZonedDateTime.of(2026, 10, day, hour, minute, 0, 0, zone).toInstant()

    @Test
    fun `times are read from the model and said back naturally`() {
        assertEquals(ReminderTime.Parsed.Ok(at(22, 12, 30)), ReminderTime.parse("2026-10-22T12:30", null, now))
        assertEquals(ReminderTime.Parsed.Ok(at(21, 10, 20)), ReminderTime.parse(null, "20", now))
        assertTrue("the past is refused", ReminderTime.parse("2026-10-21T09:00", null, now) is ReminderTime.Parsed.Bad)
        assertTrue(ReminderTime.parse("завтра", null, now) is ReminderTime.Parsed.Bad)
        assertNull(ReminderTime.parse(null, null, now))

        assertEquals("сьогодні о 18:00", ReminderTime.describe(at(21, 18), now))
        assertEquals("завтра о 12:30", ReminderTime.describe(at(22, 12, 30), now))
        assertEquals("субота, 24 жовтня о 09:00", ReminderTime.describe(at(24, 9), now))
    }

    @Test
    fun `repeats come back at the same time of day`() {
        assertEquals(at(22, 9), Repeat.Daily.next(at(21, 9), now.toInstant(), zone))
        // Friday 23rd → Monday 26th
        assertEquals(at(26, 9), Repeat.Weekdays.next(at(23, 9), at(23, 9, 1), zone))
        assertEquals(at(28, 9), Repeat.Weekly.next(at(21, 9), now.toInstant(), zone))
        assertNull(Repeat.None.next(at(21, 9), now.toInstant(), zone))
    }

    @Test
    fun `a one-off goes off once, is spoken, and can be snoozed`() = runTest {
        val r = reminders.add(Reminder(0, "піти по хліб", at = at(21, 12, 30).toEpochMilli()))
        assertTrue(r.id in scheduler.scheduled)

        now = now.withHour(12).withMinute(30)
        reminders.fire(r.id)
        assertEquals(listOf("Нагадую: піти по хліб"), alerts.shown)
        assertEquals(alerts.shown, alerts.spoken)
        assertTrue(store.get(r.id)!!.fired)
        assertFalse("no longer scheduled", r.id in scheduler.scheduled)

        reminders.snooze(r.id, 10)
        assertEquals(at(21, 12, 40).toEpochMilli(), store.get(r.id)!!.at)
        assertFalse(store.get(r.id)!!.fired)
        assertTrue(r.id in scheduler.scheduled)
    }

    @Test
    fun `a repeating one moves on to its next time`() = runTest {
        val r = reminders.add(Reminder(0, "звіт", at = at(21, 9).toEpochMilli() + 3_600_000, repeat = Repeat.Daily))
        now = now.withHour(10).withMinute(0).plusSeconds(1)
        reminders.fire(r.id)
        assertEquals(at(22, 10).toEpochMilli(), store.get(r.id)!!.at)
        assertFalse(store.get(r.id)!!.fired)
    }

    @Test
    fun `arriving at a place reminds once`() = runTest {
        val r = reminders.add(Reminder(0, "батарейки", place = Place("АТБ", 48.6, 26.5)))
        reminders.arrived(r.id)
        reminders.arrived(r.id)
        assertEquals(listOf("Ти біля «АТБ». Нагадую: батарейки"), alerts.shown)
    }

    @Test
    fun `a scheduled task is carried out and its result told`() = runTest {
        val r = reminders.add(Reminder(0, "x", at = at(21, 18).toEpochMilli(), task = "Надішли Олегу: Виїжджаю"))
        reminders.fire(r.id)
        assertEquals(listOf("Надішли Олегу: Виїжджаю"), tasks)
        assertTrue(alerts.spoken.single().contains("Надіслав Олегу"))
    }

    @Test
    fun `tools set, list, change and remove reminders`() = runTest {
        val places = object : PlacesStore {
            override fun all() = mapOf("дім" to "вулиця Шевченка, 5")
            override fun save(name: String, address: String) = Unit
            override fun remove(name: String) = Unit
        }
        val looked = mutableListOf<String>()
        val set = SetReminderTool(reminders, places, { q -> looked += q; Place(q, 1.0, 2.0) }, now = { now })

        val bread = set.execute(buildJsonObject { put("text", "піти по хліб"); put("at", "2026-10-22T12:30") }) as ToolResult.Success
        assertEquals("Нагадаю завтра о 12:30: піти по хліб", bread.message)
        val home = set.execute(buildJsonObject { put("text", "винести сміття"); put("place", "дім") }) as ToolResult.Success
        assertTrue(home.message.contains("біля «дім»"))
        assertEquals("a saved place is looked up by its address", listOf("вулиця Шевченка, 5"), looked)
        AddTodoTool(reminders).execute(buildJsonObject { put("text", "купити батарейки") })

        scheduler.places = false
        assertTrue(set.execute(buildJsonObject { put("text", "x"); put("place", "АТБ") }) is ToolResult.Failure)

        val list = (ListRemindersTool(reminders, now = { now }).execute(buildJsonObject { }) as ToolResult.Success).message
        assertTrue(list.startsWith("[1] завтра о 12:30 — піти по хліб"))
        assertTrue(list.contains("у списку справ — купити батарейки"))

        UpdateReminderTool(reminders, now = { now }).execute(buildJsonObject { put("id", "1"); put("at", "2026-10-22T14:00") })
        assertEquals(at(22, 14).toEpochMilli(), store.get(1)!!.at)

        RemoveRemindersTool(reminders).execute(buildJsonObject { put("ids", "1, 3") })
        assertEquals(listOf(2), store.all().map { it.id })
        assertTrue(store.promptNote(now).contains("[2] біля «дім» — винести сміття"))
    }

    @Test
    fun `a task waits for the user's yes, and runs without asking when its time comes`() = runTest {
        val turns = UserTurns()
        val gate = ConfirmationGate(turns)
        val tool = ScheduleTaskTool(reminders, gate, now = { now })
        val args = buildJsonObject { put("instruction", "Надішли Олегу: Виїжджаю"); put("at", "2026-10-21T18:00") }

        val ask = tool.execute(args) as ToolResult.NeedsConfirmation
        assertTrue(ask.question.contains("Сьогодні о 18:00"))
        assertTrue("nothing yet", store.all().isEmpty())
        turns.next()
        val ok = tool.execute(buildJsonObject { args.forEach { (k, v) -> put(k, v) }; put("confirmation", ask.token) })
        assertTrue(ok is ToolResult.Success)
        assertEquals("Надішли Олегу: Виїжджаю", store.all().single().task)

        assertFalse(gate.confirm(null, "send_message|x"))
        assertTrue("a scheduled run is pre-approved", gate.preApproved { gate.confirm(null, "send_message|x") })
        assertFalse(gate.confirm(null, "send_message|x"))
    }

    @Test
    fun `calendar events are added for an hour by default, or for the whole day`() = runTest {
        val added = mutableListOf<Triple<String, Instant, Instant>>()
        val calendar = object : CalendarSource {
            override val hasAccess = true
            override suspend fun add(title: String, start: Instant, end: Instant, allDay: Boolean, location: String?, reminderMinutes: Int?): Long {
                added += Triple(title, start, end)
                return 42
            }
            override suspend fun between(from: Instant, to: Instant) = emptyList<CalendarEvent>()
            override suspend fun delete(id: Long) = true
        }
        val tool = AddCalendarEventTool(calendar, now = { now })
        val meeting = tool.execute(buildJsonObject { put("title", "Зустріч з Олегом"); put("start", "2026-10-23T15:00") }) as ToolResult.Success
        assertEquals("Додав у календар: «Зустріч з Олегом», післязавтра о 15:00 [подія 42]", meeting.message)
        assertEquals(at(23, 16), added.single().third)

        tool.execute(buildJsonObject { put("title", "День народження Соні"); put("start", "2026-10-25") })
        assertEquals(Instant.parse("2026-10-25T00:00:00Z"), added.last().second)
        assertEquals(Instant.parse("2026-10-26T00:00:00Z"), added.last().third)
    }
}
