package app.vinkje.domain

/** What someone else did to a task, as far as a notification is concerned. */
enum class ChangeKind { ADDED, COMPLETED, REOPENED, ASSIGNED_TO_ME, EDITED }

object Changes {
    /**
     * The change from [before] (null: a new task) to [after], or null when there is nothing
     * worth telling: a task moved within its list, or assigned to someone other than [self].
     */
    fun kind(before: TaskState?, after: TaskState, self: String?): ChangeKind? = when {
        before == null -> ChangeKind.ADDED
        !before.done && after.done -> ChangeKind.COMPLETED
        before.done && !after.done -> ChangeKind.REOPENED
        self != null && self in after.assignees && self !in before.assignees -> ChangeKind.ASSIGNED_TO_ME
        before.copy(assignees = after.assignees) != after -> ChangeKind.EDITED
        else -> null
    }
}
