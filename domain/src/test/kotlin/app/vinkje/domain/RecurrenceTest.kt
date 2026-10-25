package app.vinkje.domain

import org.junit.jupiter.api.Test
import app.vinkje.domain.Recurrence.Freq
import java.time.DayOfWeek.MONDAY
import java.time.DayOfWeek.THURSDAY
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RecurrenceTest {
    private fun d(s: String) = LocalDate.parse(s)

    /** Follows the rule [n] times from [start] and returns every date. */
    private fun Recurrence.series(start: String, n: Int) =
        generateSequence(d(start)) { next(it) }.take(n + 1).drop(1).map { it.toString() }.toList()

    @Test
    fun `daily with interval`() {
        assertEquals(listOf("2026-10-28", "2026-10-31", "2026-11-03"), Recurrence(Freq.DAILY, 3).series("2026-10-25", 3))
    }

    @Test
    fun `workdays skip the weekend`() {
        // 2026-10-29 is a Thursday
        assertEquals(
            listOf("2026-10-30", "2026-11-02", "2026-11-03"),
            Recurrence.workdays().series("2026-10-29", 3),
        )
        assertTrue(Recurrence.workdays().isWorkdays)
    }

    @Test
    fun `every two weeks on Monday and Thursday`() {
        // 2026-10-26 is a Monday
        val rule = Recurrence(Freq.WEEKLY, 2, byDay = setOf(MONDAY, THURSDAY))
        assertEquals(
            listOf("2026-10-29", "2026-11-09", "2026-11-12", "2026-11-23"),
            rule.series("2026-10-26", 4),
        )
    }

    @Test
    fun `weekly without weekdays repeats on the same weekday`() {
        assertEquals(listOf("2026-11-01", "2026-11-08"), Recurrence(Freq.WEEKLY).series("2026-10-25", 2))
    }

    @Test
    fun `monthly on the 31st falls on the last day of short months and recovers`() {
        val rule = Recurrence.anchored(Freq.MONTHLY, d("2027-01-31"))
        assertEquals(listOf("2027-02-28", "2027-03-31", "2027-04-30", "2027-05-31"), rule.series("2027-01-31", 4))
    }

    @Test
    fun `monthly every three months`() {
        assertEquals(
            listOf("2027-01-15", "2027-04-15"),
            Recurrence(Freq.MONTHLY, 3, byMonthDay = 15).series("2026-10-15", 2),
        )
    }

    @Test
    fun `yearly on 29 February`() {
        val rule = Recurrence.anchored(Freq.YEARLY, d("2028-02-29"))
        assertEquals(listOf("2029-02-28", "2030-02-28", "2031-02-28", "2032-02-29"), rule.series("2028-02-29", 4))
    }

    @Test
    fun `an off-schedule due date moves to the next on-schedule date`() {
        assertEquals(d("2026-10-15"), Recurrence(Freq.MONTHLY, byMonthDay = 15).next(d("2026-10-10")))
    }

    @Test
    fun `format and parse round-trip`() {
        val rules = listOf(
            Recurrence(Freq.DAILY),
            Recurrence.workdays(),
            Recurrence(Freq.WEEKLY, 2, byDay = setOf(THURSDAY, MONDAY)),
            Recurrence.anchored(Freq.MONTHLY, d("2026-10-31"), interval = 2),
            Recurrence.anchored(Freq.YEARLY, d("2026-03-08")),
        )
        rules.forEach { assertEquals(it, Recurrence.parse(it.format())) }
        assertEquals("FREQ=WEEKLY;INTERVAL=2;BYDAY=MO,TH", rules[2].format())
    }

    @Test
    fun `parse is lenient about case and prefix but rejects unsupported rules`() {
        assertEquals(Recurrence(Freq.DAILY, 2), Recurrence.parse("RRULE:freq=daily;interval=2"))
        assertNull(Recurrence.parse("FREQ=HOURLY"))
        assertNull(Recurrence.parse("FREQ=MONTHLY;BYSETPOS=-1;BYDAY=FR"))
        assertNull(Recurrence.parse("FREQ=DAILY;INTERVAL=0"))
        assertNull(Recurrence.parse("onzin"))
    }
}
