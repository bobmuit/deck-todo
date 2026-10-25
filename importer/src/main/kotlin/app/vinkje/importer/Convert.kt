package app.vinkje.importer

import app.vinkje.domain.DeckConventions
import app.vinkje.domain.Recurrence
import app.vinkje.domain.Recurrence.Freq
import app.vinkje.domain.Step
import app.vinkje.domain.TaskBody
import app.vinkje.domain.TaskBodyCodec
import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset

/** A To Do task as it goes onto a card, plus the personal reminder that does not. */
data class CardContent(
    val title: String,
    val description: String,
    val due: Instant?,
    val done: Instant?,
    val important: Boolean,
    /** Personal: handed to the app of the list's own account, never written on the card. */
    val reminder: Instant?,
    val steps: Int,
    /** What could not be carried over, for the report. */
    val warnings: List<String>,
)

object Convert {
    /** Deck's limit on card titles. */
    private const val MAX_TITLE = 255

    fun card(task: GraphTask, zone: ZoneId): CardContent {
        val warnings = mutableListOf<String>()
        var title = task.title.trim().replace(Regex("\\s+"), " ")
        var note = note(task.body)
        if (title.length > MAX_TITLE) {
            note = listOf(title, note).filter { it.isNotBlank() }.joinToString("\n\n")
            title = title.take(MAX_TITLE - 1).trimEnd() + "…"
        }
        if (title.isEmpty()) title = "(zonder titel)"

        val done = task.status == "completed"
        val dueDate = task.dueDateTime?.let { localDate(it, zone) }
        // A ticked repeating task has already spawned its successor in To Do.
        val rule = if (done) null else task.recurrence?.let { r ->
            recurrence(r.pattern).also { if (it == null) warnings += "herhaling '${r.pattern.type}' wordt niet ondersteund" }
        }
        val steps = task.checklistItems.orEmpty().filter { it.displayName.isNotBlank() }.map { Step(it.displayName.trim(), it.isChecked) }
        if (task.hasAttachments) warnings += "bijlagen zijn niet meegenomen"

        val body = TaskBody(note = note, steps = steps).withRecurrence(rule)
        return CardContent(
            title = title,
            description = TaskBodyCodec.format(body),
            due = dueDate?.let { DeckConventions.dueInstant(it, zone) },
            // Graph has a completion day, no time; noon keeps it on that day in any zone nearby.
            done = if (done) (task.completedDateTime?.let { localDate(it, zone) } ?: LocalDate.now(zone)).atTime(12, 0).atZone(zone).toInstant() else null,
            important = task.importance == "high",
            reminder = if (!done && task.isReminderOn) task.reminderDateTime?.let { instant(it) } else null,
            steps = steps.size,
            warnings = warnings,
        )
    }

    /**
     * To Do stores a due day as midnight in the user's zone, sent in UTC, so the day only
     * comes out right after converting back to that zone.
     */
    fun localDate(value: GraphDateTime, zone: ZoneId): LocalDate = instant(value).atZone(zone).toLocalDate()

    fun instant(value: GraphDateTime): Instant = LocalDateTime.parse(value.dateTime).atZone(zoneOf(value.timeZone)).toInstant()

    private fun zoneOf(name: String): ZoneId = WINDOWS_ZONES[name] ?: runCatching { ZoneId.of(name) }.getOrDefault(ZoneOffset.UTC)

    fun recurrence(p: GraphPattern): Recurrence? {
        val n = p.interval.coerceAtLeast(1)
        return when (p.type) {
            "daily" -> Recurrence(Freq.DAILY, n)
            "weekly" -> Recurrence(Freq.WEEKLY, n, byDay = p.daysOfWeek.mapNotNull { day -> DayOfWeek.entries.firstOrNull { it.name.equals(day, true) } }.toSet())
            "absoluteMonthly" -> Recurrence(Freq.MONTHLY, n, byMonthDay = p.dayOfMonth.takeIf { it in 1..31 })
            "absoluteYearly" -> Recurrence(Freq.YEARLY, n, byMonthDay = p.dayOfMonth.takeIf { it in 1..31 }, byMonth = p.month.takeIf { it in 1..12 })
            // "the first Monday of the month": To Do's app cannot set these up, Outlook can.
            else -> null
        }
    }

    /** The note as plain text; Graph sends HTML for notes written in Outlook. */
    fun note(body: GraphBody?): String {
        val content = body?.content.orEmpty().replace("\r\n", "\n")
        val text = if (body?.contentType.equals("html", ignoreCase = true)) htmlToText(content) else content
        return text.lines().joinToString("\n") { it.trimEnd() }.replace(Regex("\n{3,}"), "\n\n").trim()
    }

    private fun htmlToText(html: String): String = html
        .replace(Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>"), "")
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)</(p|div|li|h[1-6]|tr)>"), "\n")
        .replace(Regex("(?i)<li[^>]*>"), "• ")
        .replace(Regex("<[^>]+>"), "")
        .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
        .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&")

    /** The Windows zone names Graph may send for this part of the world. */
    private val WINDOWS_ZONES = mapOf(
        "UTC" to ZoneOffset.UTC,
        "W. Europe Standard Time" to ZoneId.of("Europe/Amsterdam"),
        "Romance Standard Time" to ZoneId.of("Europe/Paris"),
        "Central Europe Standard Time" to ZoneId.of("Europe/Budapest"),
        "GMT Standard Time" to ZoneId.of("Europe/London"),
    )
}
