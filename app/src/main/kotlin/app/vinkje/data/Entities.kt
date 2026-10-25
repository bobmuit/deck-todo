package app.vinkje.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import app.vinkje.domain.DeckConventions

/*
 * The local database is the source of truth for the UI. Every row has a local id
 * and, once it exists on the server, a Deck id. Local edits bump [TaskEntity.localVersion]
 * and queue an [OutboxEntity]; the sync pushes the queue before it pulls.
 */

/** A Deck board, shown as a To Do list group. */
@Entity(tableName = "groups", indices = [Index(value = ["serverId"], unique = true)])
data class GroupEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long?,
    val title: String,
    val color: String,
    val ownerUid: String,
    val canEdit: Boolean,
    /** Display names of the other people with access, for the "gedeeld" hint. */
    val sharedWith: String,
    /** Id of this board's "Belangrijk" label, once known or created. */
    val importantLabelId: Long?,
    val etag: String,
    /** Hidden on this phone (e.g. one person's work boards); never synced. */
    val hidden: Boolean = false,
    /** Who can be assigned here: the owner and every user the board is shared with, see [Members]. */
    @ColumnInfo(defaultValue = "") val members: String = "",
    /** Deck's manage permission: rename the group, add, rename and remove its lists. */
    @ColumnInfo(defaultValue = "0") val canManage: Boolean = false,
    @ColumnInfo(defaultValue = "0") val canShare: Boolean = false,
    /** Hidden at once; the row goes when the board's delete has reached the server. */
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
    /** Folded shut on the home screen of this phone; never synced. */
    @ColumnInfo(defaultValue = "0") val collapsed: Boolean = false,
) {
    companion object {
        const val LOOSE_TITLE = DeckConventions.LOOSE_BOARD
    }
}

/** [GroupEntity.members] is one "uid<TAB>display name" per line. */
object Members {
    fun encode(people: Map<String, String>) = people.entries.joinToString("\n") { (uid, name) -> "$uid\t$name" }

    fun decode(column: String): Map<String, String> = column.lineSequence()
        .mapNotNull { line -> line.split('\t', limit = 2).takeIf { it.size == 2 }?.let { it[0] to it[1] } }
        .toMap()
}

/** A Deck stack, shown as a To Do list. */
@Entity(
    tableName = "lists",
    indices = [Index("groupId"), Index(value = ["serverId"], unique = true)],
    foreignKeys = [ForeignKey(GroupEntity::class, ["id"], ["groupId"], onDelete = ForeignKey.CASCADE)],
)
data class ListEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long?,
    val groupId: Long,
    val title: String,
    val order: Int,
    /**
     * The group whose board holds the stack on the server, when that differs from [groupId]
     * because a move to another group is still queued. Null means [groupId].
     */
    val serverGroupId: Long? = null,
    @ColumnInfo(defaultValue = "0") val deleted: Boolean = false,
) {
    val homeGroupId get() = serverGroupId ?: groupId
}

/** A Deck card, shown as a To Do task. */
@Entity(
    tableName = "tasks",
    indices = [Index("listId"), Index(value = ["serverId"], unique = true)],
    foreignKeys = [ForeignKey(ListEntity::class, ["id"], ["listId"], onDelete = ForeignKey.CASCADE)],
)
data class TaskEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val serverId: Long? = null,
    val listId: Long,
    val title: String,
    val order: Int,
    /** The card description: note, steps and footer, see TaskBodyCodec. */
    val description: String = "",
    val dueEpochSecond: Long? = null,
    val doneEpochSecond: Long? = null,
    val important: Boolean = false,
    val ownerUid: String? = null,
    /** Comma-separated uids. */
    val assignees: String = "",
    /** Hidden while a delete waits in the outbox, so it can still be undone. */
    val deleted: Boolean = false,
    val localVersion: Int = 0,
    /** Server lastModified when [base] was taken; a different value at push time means a conflict. */
    val serverLastModified: Long = 0,
    /** JSON of the TaskState both sides last agreed on, for the three-way merge. Null until first synced. */
    val base: String? = null,
    /**
     * The day this task was put in "Mijn dag" (ISO date); it shows there only on that day.
     * Personal, like the reminder below, so neither is synced to Deck.
     */
    val myDay: String? = null,
    val reminderEpochSecond: Long? = null,
)

/**
 * One change still to be pushed. [rowId] is a task, list or group id, by [kind]. The
 * queue runs in order, so a list is created on the server before the tasks typed into it.
 */
@Entity(tableName = "outbox", indices = [Index("rowId")])
data class OutboxEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val kind: Kind,
    val rowId: Long,
    /** Not pushed before this time; gives the undo snackbar a chance for deletes. */
    val notBefore: Long = 0,
    /** The uid for [Kind.SHARE] and [Kind.UNSHARE]; for [Kind.LIST_MOVE] the stack created so far. */
    val arg: String? = null,
) {
    enum class Kind {
        // Tasks. The names predate lists and groups in the queue and are stored, so they stay.
        CREATE, UPDATE, DELETE,
        LIST_CREATE, LIST_UPDATE, LIST_DELETE, LIST_MOVE,
        GROUP_CREATE, GROUP_UPDATE, GROUP_DELETE, SHARE, UNSHARE;

        companion object {
            val TASK = listOf(CREATE, UPDATE, DELETE)
            val LIST = listOf(LIST_CREATE, LIST_UPDATE, LIST_DELETE, LIST_MOVE)
            val GROUP = listOf(GROUP_CREATE, GROUP_UPDATE, GROUP_DELETE, SHARE, UNSHARE)
        }
    }
}
