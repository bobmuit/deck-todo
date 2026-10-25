package app.vinkje.domain

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.TemporalAdjusters

/**
 * The subset of RFC 5545 RRULE that Microsoft To Do can express: daily, weekly on
 * chosen weekdays (which covers "workdays"), monthly on a day of the month, and
 * yearly, each with an interval.
 *
 * Like To Do, the next occurrence is computed from the current due date, not from
 * the moment the task was completed.
 */
data class Recurrence(
    val freq: Freq,
    val interval: Int = 1,
    val byDay: Set<DayOfWeek> = emptySet(),
    val byMonthDay: Int? = null,
    val byMonth: Int? = null,
) {
    enum class Freq { DAILY, WEEKLY, MONTHLY, YEARLY }

    init {
        require(interval >= 1) { "interval must be >= 1" }
        require(byMonthDay == null || byMonthDay in 1..31) { "byMonthDay out of range" }
        require(byMonth == null || byMonth in 1..12) { "byMonth out of range" }
    }

    val isWorkdays: Boolean get() = freq == Freq.WEEKLY && interval == 1 && byDay == WORKDAYS

    /** The first occurrence strictly after [after], treating [after] as being on the schedule. */
    fun next(after: LocalDate): LocalDate = when (freq) {
        Freq.DAILY -> after.plusDays(interval.toLong())
        Freq.WEEKLY -> nextWeekly(after)
        Freq.MONTHLY -> nextMonthly(after)
        Freq.YEARLY -> nextYearly(after)
    }

    private fun nextWeekly(after: LocalDate): LocalDate {
        if (byDay.isEmpty()) return after.plusWeeks(interval.toLong())
        val days = byDay.sortedBy { it.value }
        days.firstOrNull { it.value > after.dayOfWeek.value }?.let {
            return after.with(TemporalAdjusters.next(it))
        }
        val weekStart = after.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
            .plusWeeks(interval.toLong())
        return weekStart.plusDays((days.first().value - 1).toLong())
    }

    private fun nextMonthly(after: LocalDate): LocalDate {
        val day = byMonthDay ?: after.dayOfMonth
        val thisMonth = YearMonth.from(after).clamp(day)
        if (thisMonth.isAfter(after)) return thisMonth
        return YearMonth.from(after).plusMonths(interval.toLong()).clamp(day)
    }

    private fun nextYearly(after: LocalDate): LocalDate {
        val month = byMonth ?: after.monthValue
        val day = byMonthDay ?: after.dayOfMonth
        val thisYear = YearMonth.of(after.year, month).clamp(day)
        if (thisYear.isAfter(after)) return thisYear
        return YearMonth.of(after.year + interval, month).clamp(day)
    }

    /** Day 31 in a 30-day month falls on the 30th, as in To Do; the anchor day is kept for later months. */
    private fun YearMonth.clamp(day: Int): LocalDate = atDay(minOf(day, lengthOfMonth()))

    fun format(): String = buildList {
        add("FREQ=${freq.name}")
        if (interval != 1) add("INTERVAL=$interval")
        if (byDay.isNotEmpty()) add("BYDAY=" + byDay.sortedBy { it.value }.joinToString(",") { DAY_CODES.getValue(it) })
        byMonthDay?.let { add("BYMONTHDAY=$it") }
        byMonth?.let { add("BYMONTH=$it") }
    }.joinToString(";")

    companion object {
        val WORKDAYS: Set<DayOfWeek> = DayOfWeek.entries.filter { it.value <= 5 }.toSet()

        private val DAY_CODES = mapOf(
            DayOfWeek.MONDAY to "MO", DayOfWeek.TUESDAY to "TU", DayOfWeek.WEDNESDAY to "WE",
            DayOfWeek.THURSDAY to "TH", DayOfWeek.FRIDAY to "FR", DayOfWeek.SATURDAY to "SA",
            DayOfWeek.SUNDAY to "SU",
        )
        private val CODE_DAYS = DAY_CODES.entries.associate { (d, c) -> c to d }

        fun workdays() = Recurrence(Freq.WEEKLY, byDay = WORKDAYS)

        /**
         * A new rule anchored on [due], the way To Do sets one up: weekly repeats on the
         * due date's weekday, monthly and yearly on its day (and month).
         */
        fun anchored(freq: Freq, due: LocalDate, interval: Int = 1): Recurrence = when (freq) {
            Freq.DAILY -> Recurrence(freq, interval)
            Freq.WEEKLY -> Recurrence(freq, interval, byDay = setOf(due.dayOfWeek))
            Freq.MONTHLY -> Recurrence(freq, interval, byMonthDay = due.dayOfMonth)
            Freq.YEARLY -> Recurrence(freq, interval, byMonthDay = due.dayOfMonth, byMonth = due.monthValue)
        }

        /** Returns null for anything outside the supported subset; the raw text is kept in [TaskBody.meta]. */
        fun parse(text: String): Recurrence? {
            val parts = text.trim().removePrefix("RRULE:").split(';').filter { it.isNotBlank() }
            val fields = parts.associate { part ->
                val (k, v) = part.split('=', limit = 2).takeIf { it.size == 2 } ?: return null
                k.trim().uppercase() to v.trim().uppercase()
            }
            if (fields.keys.any { it !in setOf("FREQ", "INTERVAL", "BYDAY", "BYMONTHDAY", "BYMONTH") }) return null
            return runCatching {
                Recurrence(
                    freq = Freq.valueOf(fields.getValue("FREQ")),
                    interval = fields["INTERVAL"]?.toInt() ?: 1,
                    byDay = fields["BYDAY"]?.split(',')?.map { CODE_DAYS.getValue(it.trim()) }?.toSet() ?: emptySet(),
                    byMonthDay = fields["BYMONTHDAY"]?.toInt(),
                    byMonth = fields["BYMONTH"]?.toInt(),
                )
            }.getOrNull()
        }
    }
}
