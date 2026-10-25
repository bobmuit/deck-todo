package app.vinkje.domain

import java.time.LocalDate

/** What ticking off a repeating task leaves behind, the way To Do does it. */
data class NextInstance(
    /** The ticked task keeps its note and steps but stops repeating, so unticking it later spawns nothing. */
    val completedBody: TaskBody,
    /** The new open task: same note, steps unticked, same rule. */
    val body: TaskBody,
    val due: LocalDate,
)

object Recurring {
    /**
     * Returns null when [body] does not repeat. The next due date follows the old due date
     * (or [today] when the task had none), so an overdue weekly task stays on its weekday.
     */
    fun next(body: TaskBody, due: LocalDate?, today: LocalDate): NextInstance? {
        val rule = body.recurrence ?: return null
        return NextInstance(
            completedBody = body.withRecurrence(null),
            body = body.copy(steps = body.steps.map { it.copy(done = false) }),
            due = rule.next(due ?: today),
        )
    }
}
