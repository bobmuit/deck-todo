package app.vinkje.importer

import org.junit.jupiter.api.Test
import app.vinkje.domain.Recurrence
import app.vinkje.domain.Step
import app.vinkje.domain.TaskBodyCodec
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConvertTest {
    private val zone = ZoneId.of("Europe/Amsterdam")
    private fun utc(s: String) = GraphDateTime(s, "UTC")

    @Test
    fun `due day survives To Do's midnight-in-local-time-sent-as-UTC`() {
        // 30 October, set in Amsterdam (CET): local midnight is 23:00 UTC the day before.
        assertEquals(LocalDate.of(2026, 10, 30), Convert.localDate(utc("2026-10-29T23:00:00.0000000"), zone))
        // Some clients send midnight UTC itself.
        assertEquals(LocalDate.of(2026, 10, 30), Convert.localDate(utc("2026-10-30T00:00:00.0000000"), zone))
        // And summer time.
        assertEquals(LocalDate.of(2026, 7, 1), Convert.localDate(utc("2026-06-30T22:00:00.0000000"), zone))
        assertEquals(LocalDate.of(2026, 7, 1), Convert.localDate(GraphDateTime("2026-07-01T00:00:00.0000000", "W. Europe Standard Time"), zone))
    }

    @Test
    fun `a task becomes title, note, steps and rule, due at the end of its day`() {
        val c = Convert.card(GraphTask(
            id = "t1", title = "  Melk   halen ", importance = "high",
            dueDateTime = utc("2026-10-29T23:00:00.0000000"),
            body = GraphBody("Bij de Jumbo\r\n", "text"),
            recurrence = GraphRecurrence(GraphPattern("weekly", 2, daysOfWeek = listOf("monday", "friday"))),
            checklistItems = listOf(GraphChecklistItem("halfvol"), GraphChecklistItem("lactosevrij", true), GraphChecklistItem(" ")),
            isReminderOn = true, reminderDateTime = utc("2026-10-30T07:30:00.0000000"),
        ), zone)
        assertEquals("Melk halen", c.title)
        assertTrue(c.important)
        assertEquals(Instant.parse("2026-10-30T22:59:00Z"), c.due)
        assertNull(c.done)
        assertEquals(Instant.parse("2026-10-30T07:30:00Z"), c.reminder)
        assertEquals(2, c.steps)
        val body = TaskBodyCodec.parse(c.description)
        assertEquals("Bij de Jumbo", body.note)
        assertEquals(listOf(Step("halfvol"), Step("lactosevrij", true)), body.steps)
        assertEquals(Recurrence(Recurrence.Freq.WEEKLY, 2, byDay = setOf(DayOfWeek.MONDAY, DayOfWeek.FRIDAY)), body.recurrence)
    }

    @Test
    fun `To Do's repeat options map onto the rule`() {
        assertTrue(Convert.recurrence(GraphPattern("weekly", 1, daysOfWeek = listOf("monday", "tuesday", "wednesday", "thursday", "friday")))!!.isWorkdays)
        assertEquals(Recurrence(Recurrence.Freq.DAILY, 3), Convert.recurrence(GraphPattern("daily", 3)))
        assertEquals(Recurrence(Recurrence.Freq.MONTHLY, 1, byMonthDay = 31), Convert.recurrence(GraphPattern("absoluteMonthly", 1, dayOfMonth = 31)))
        assertEquals(Recurrence(Recurrence.Freq.YEARLY, 1, byMonthDay = 14, byMonth = 2), Convert.recurrence(GraphPattern("absoluteYearly", 1, month = 2, dayOfMonth = 14)))
        assertNull(Convert.recurrence(GraphPattern("relativeMonthly", 1, daysOfWeek = listOf("monday"))))
    }

    @Test
    fun `an unsupported rule is reported, not guessed`() {
        val c = Convert.card(GraphTask("t", "Huur", recurrence = GraphRecurrence(GraphPattern("relativeMonthly"))), zone)
        assertNull(TaskBodyCodec.parse(c.description).recurrence)
        assertEquals(1, c.warnings.size)
    }

    @Test
    fun `a ticked task keeps its day but stops repeating and reminding`() {
        val c = Convert.card(GraphTask(
            "t", "Band plakken", status = "completed",
            completedDateTime = utc("2026-10-20T22:00:00.0000000"),
            recurrence = GraphRecurrence(GraphPattern("daily")),
            isReminderOn = true, reminderDateTime = utc("2026-10-20T08:00:00.0000000"),
        ), zone)
        assertEquals(LocalDate.of(2026, 10, 21), c.done!!.atZone(zone).toLocalDate())
        assertNull(TaskBodyCodec.parse(c.description).recurrence)
        assertNull(c.reminder)
        assertTrue(c.warnings.isEmpty())
    }

    @Test
    fun `notes written in Outlook arrive as HTML`() {
        val html = "<html><head><style>p{}</style></head><body><p>Regel&nbsp;1</p><div>A &amp; B<br>C</div><ul><li>een</li></ul></body></html>"
        assertEquals("Regel 1\nA & B\nC\n• een", Convert.note(GraphBody(html, "html")))
    }

    @Test
    fun `an over-long title is cut, the whole one goes into the note`() {
        val long = "x".repeat(300)
        val c = Convert.card(GraphTask("t", long, body = GraphBody("note")), zone)
        assertEquals(255, c.title.length)
        assertEquals("$long\n\nnote", TaskBodyCodec.parse(c.description).note)
        assertEquals("(zonder titel)", Convert.card(GraphTask("t", "  "), zone).title)
    }
}
