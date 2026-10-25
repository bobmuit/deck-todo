package app.vinkje.sync

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import app.vinkje.data.TaskEntity
import app.vinkje.deck.Card
import app.vinkje.domain.DeckConventions
import app.vinkje.domain.TaskBodyCodec
import app.vinkje.domain.TaskState
import java.time.OffsetDateTime

const val IMPORTANT_LABEL = DeckConventions.IMPORTANT_LABEL
const val IMPORTANT_COLOR = DeckConventions.IMPORTANT_COLOR

fun parseDeckTime(value: String?): Long? = value?.let { OffsetDateTime.parse(it).toEpochSecond() }

fun TaskEntity.toState() = TaskState(
    title = title,
    body = TaskBodyCodec.parse(description),
    dueEpochSecond = dueEpochSecond,
    doneEpochSecond = doneEpochSecond,
    important = important,
    assignees = assigneeSet(assignees),
)

fun assigneeSet(column: String): Set<String> = column.split(',').filter { it.isNotBlank() }.toSet()

fun Card.toState(importantLabelId: Long?) = TaskState(
    title = title,
    body = TaskBodyCodec.parse(description),
    dueEpochSecond = parseDeckTime(duedate),
    doneEpochSecond = parseDeckTime(done),
    important = labels.orEmpty().any { it.title == IMPORTANT_LABEL || it.id == importantLabelId },
    assignees = assignedUsers.orEmpty().map { it.participant.uid }.toSet(),
)

/** Applies a merged or server state to a local row, keeping identity and bookkeeping. */
fun TaskEntity.withState(state: TaskState) = copy(
    title = state.title,
    description = TaskBodyCodec.format(state.body),
    dueEpochSecond = state.dueEpochSecond,
    doneEpochSecond = state.doneEpochSecond,
    important = state.important,
    assignees = state.assignees.sorted().joinToString(","),
)

/** The base snapshot is stored as JSON in [TaskEntity.base]. */
@Serializable
private data class StoredState(
    val title: String,
    val description: String,
    val due: Long? = null,
    val done: Long? = null,
    val important: Boolean = false,
    val assignees: List<String> = emptyList(),
)

private val json = Json { ignoreUnknownKeys = true }

fun encodeBase(state: TaskState): String = json.encodeToString(
    StoredState(state.title, TaskBodyCodec.format(state.body), state.dueEpochSecond, state.doneEpochSecond, state.important, state.assignees.sorted())
)

fun decodeBase(text: String?): TaskState? = text?.let {
    runCatching { json.decodeFromString<StoredState>(it) }.getOrNull()?.let { s ->
        TaskState(s.title, TaskBodyCodec.parse(s.description), s.due, s.done, s.important, s.assignees.toSet())
    }
}
