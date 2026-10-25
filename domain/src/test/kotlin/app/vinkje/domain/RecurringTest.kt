package app.vinkje.domain

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class RecurringTest {
    private val today = LocalDate.parse("2026-10-25")

    @Test
    fun `a task without a rule spawns nothing`() {
        assertNull(Recurring.next(TaskBody(note = "x"), null, today))
    }

    @Test
    fun `next instance resets steps and keeps the rule`() {
        val body = TaskBody(note = "n", steps = listOf(Step("a", true), Step("b"))).withRecurrence(Recurrence.workdays())
        val next = assertNotNull(Recurring.next(body, LocalDate.parse("2026-10-30"), today))
        assertEquals(LocalDate.parse("2026-11-02"), next.due)
        assertEquals(listOf(Step("a"), Step("b")), next.body.steps)
        assertEquals("n", next.body.note)
        assertEquals(Recurrence.workdays(), next.body.recurrence)
        assertNull(next.completedBody.recurrence)
        assertEquals(body.steps, next.completedBody.steps)
    }

    @Test
    fun `without a due date the series starts from today`() {
        val body = TaskBody().withRecurrence(Recurrence(Recurrence.Freq.DAILY))
        assertEquals(LocalDate.parse("2026-10-26"), Recurring.next(body, null, today)!!.due)
    }

    @Test
    fun `an overdue task stays on its schedule`() {
        val body = TaskBody().withRecurrence(Recurrence.anchored(Recurrence.Freq.WEEKLY, LocalDate.parse("2026-10-05")))
        assertEquals(LocalDate.parse("2026-10-12"), Recurring.next(body, LocalDate.parse("2026-10-05"), today)!!.due)
    }
}
