package app.vinkje.data

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import app.vinkje.domain.HomeLayout
import kotlinx.coroutines.flow.Flow

data class ListWithCount(
    val id: Long,
    val groupId: Long,
    val title: String,
    val openCount: Int,
    val order: Int,
    val position: Int?,
    val pinned: Int?,
)

/** A task with the list it lives in, for the smart lists that mix lists. */
data class SmartTask(
    @Embedded val task: TaskEntity,
    val listTitle: String,
)

data class SmartCounts(val myDay: Int, val important: Int, val planned: Int, val assigned: Int)

/** Tasks the smart lists can see: not deleted, in a group that is not hidden on this phone. */
private const val VISIBLE = """FROM tasks t JOIN lists l ON l.id = t.listId JOIN groups g ON g.id = l.groupId
    WHERE NOT t.deleted AND NOT l.deleted AND NOT g.deleted AND NOT g.hidden"""

/** Outbox kinds for one table, as SQL; the enum is stored by name. */
private const val TASK_OPS = "('CREATE', 'UPDATE', 'DELETE')"
private const val LIST_OPS = "('LIST_CREATE', 'LIST_UPDATE', 'LIST_DELETE', 'LIST_MOVE')"
private const val GROUP_OPS = "('GROUP_CREATE', 'GROUP_UPDATE', 'GROUP_DELETE', 'SHARE', 'UNSHARE')"

/** [TaskEntity.assignees] is a comma-separated list; this matches one uid in it. */
private const val ASSIGNED = "(',' || t.assignees || ',') LIKE '%,' || :uid || ',%'"

@Dao
interface TodoDao {
    // --- reads for the UI ---

    @Query("SELECT * FROM groups WHERE NOT deleted ORDER BY title COLLATE NOCASE")
    fun groups(): Flow<List<GroupEntity>>

    @Query(
        """SELECT l.id, l.groupId, l.title, l.`order`, l.position, l.pinned,
           (SELECT COUNT(*) FROM tasks t WHERE t.listId = l.id AND t.doneEpochSecond IS NULL AND NOT t.deleted) AS openCount
           FROM lists l WHERE NOT l.deleted ORDER BY l.`order`, l.id"""
    )
    fun listsWithCounts(): Flow<List<ListWithCount>>

    @Query("SELECT * FROM lists WHERE id = :id")
    fun list(id: Long): Flow<ListEntity?>

    @Query("SELECT * FROM groups WHERE id = :id")
    fun groupFlow(id: Long): Flow<GroupEntity?>

    /** Open tasks first in Deck order, then done ones newest first, as in To Do. */
    @Query(
        """SELECT * FROM tasks WHERE listId = :listId AND NOT deleted
           ORDER BY doneEpochSecond IS NOT NULL, `order`, id"""
    )
    fun tasks(listId: Long): Flow<List<TaskEntity>>

    @Query("SELECT * FROM tasks WHERE id = :id")
    fun taskFlow(id: Long): Flow<TaskEntity?>

    // Smart lists. Done tasks come along when ticked since [since], so a row ticked on
    // screen can stay put for a moment (and Mijn dag can show its "Voltooid" section).

    @Query("SELECT t.*, l.title AS listTitle $VISIBLE AND t.myDay = :today AND (t.doneEpochSecond IS NULL OR t.doneEpochSecond >= :since) ORDER BY g.title COLLATE NOCASE, l.`order`, t.`order`")
    fun myDay(today: String, since: Long): Flow<List<SmartTask>>

    @Query("SELECT t.*, l.title AS listTitle $VISIBLE AND t.important AND (t.doneEpochSecond IS NULL OR t.doneEpochSecond >= :since) ORDER BY g.title COLLATE NOCASE, l.`order`, t.`order`")
    fun important(since: Long): Flow<List<SmartTask>>

    @Query("SELECT t.*, l.title AS listTitle $VISIBLE AND t.dueEpochSecond IS NOT NULL AND (t.doneEpochSecond IS NULL OR t.doneEpochSecond >= :since) ORDER BY t.dueEpochSecond, t.`order`")
    fun planned(since: Long): Flow<List<SmartTask>>

    @Query("SELECT t.*, l.title AS listTitle $VISIBLE AND $ASSIGNED AND (t.doneEpochSecond IS NULL OR t.doneEpochSecond >= :since) ORDER BY g.title COLLATE NOCASE, l.`order`, t.`order`")
    fun assigned(uid: String, since: Long): Flow<List<SmartTask>>

    @Query(
        """SELECT
           (SELECT COUNT(*) $VISIBLE AND t.doneEpochSecond IS NULL AND t.myDay = :today) AS myDay,
           (SELECT COUNT(*) $VISIBLE AND t.doneEpochSecond IS NULL AND t.important) AS important,
           (SELECT COUNT(*) $VISIBLE AND t.doneEpochSecond IS NULL AND t.dueEpochSecond IS NOT NULL) AS planned,
           (SELECT COUNT(*) $VISIBLE AND t.doneEpochSecond IS NULL AND $ASSIGNED) AS assigned"""
    )
    fun smartCounts(today: String, uid: String): Flow<SmartCounts>

    /** Whether any visible group is shared, which is when "Aan mij toegewezen" is worth showing. */
    @Query("SELECT EXISTS(SELECT 1 FROM groups WHERE sharedWith != '' AND NOT hidden AND NOT deleted)")
    fun anyShared(): Flow<Boolean>

    @Query("SELECT g.* FROM groups g JOIN lists l ON l.groupId = g.id JOIN tasks t ON t.listId = l.id WHERE t.id = :taskId")
    fun groupOfTask(taskId: Long): Flow<GroupEntity?>

    @Query("SELECT members FROM groups")
    fun allMembers(): Flow<List<String>>

    /** Lists a smart list can add a new task to. */
    @Query("SELECT l.* FROM lists l JOIN groups g ON g.id = l.groupId WHERE NOT g.hidden AND NOT g.deleted AND NOT l.deleted AND g.canEdit ORDER BY g.title COLLATE NOCASE, l.`order`, l.id")
    fun writableLists(): Flow<List<ListEntity>>

    // --- plain lookups ---

    @Query("SELECT * FROM tasks WHERE id = :id") suspend fun task(id: Long): TaskEntity?
    @Query("SELECT * FROM tasks WHERE serverId = :serverId") suspend fun taskByServerId(serverId: Long): TaskEntity?
    @Query("SELECT * FROM lists WHERE id = :id") suspend fun listNow(id: Long): ListEntity?
    @Query("SELECT * FROM lists WHERE serverId = :serverId") suspend fun listByServerId(serverId: Long): ListEntity?
    @Query("SELECT * FROM lists WHERE groupId = :groupId") suspend fun listsOf(groupId: Long): List<ListEntity>
    @Query("SELECT * FROM groups WHERE id = :id") suspend fun group(id: Long): GroupEntity?
    @Query("SELECT * FROM groups WHERE serverId = :serverId") suspend fun groupByServerId(serverId: Long): GroupEntity?
    @Query("SELECT * FROM groups") suspend fun allGroups(): List<GroupEntity>
    @Query("SELECT * FROM tasks WHERE listId = :listId") suspend fun tasksOf(listId: Long): List<TaskEntity>
    @Query("SELECT * FROM tasks WHERE reminderEpochSecond IS NOT NULL AND doneEpochSecond IS NULL AND NOT deleted") suspend fun tasksWithReminder(): List<TaskEntity>
    @Query("SELECT * FROM tasks WHERE listId = :listId AND doneEpochSecond IS NOT NULL AND NOT deleted") suspend fun doneTasks(listId: Long): List<TaskEntity>
    @Query("UPDATE tasks SET myDay = :day WHERE id = :id") suspend fun setMyDay(id: Long, day: String?)
    @Query("UPDATE tasks SET reminderEpochSecond = :at WHERE id = :id") suspend fun setReminder(id: Long, at: Long?)
    @Query("SELECT MIN(`order`) FROM tasks WHERE listId = :listId AND doneEpochSecond IS NULL") suspend fun minOrder(listId: Long): Int?
    @Query("SELECT MAX(`order`) FROM lists WHERE groupId = :groupId AND NOT deleted") suspend fun maxListOrder(groupId: Long): Int?
    @Query("SELECT * FROM groups WHERE title = :title AND ownerUid = :uid AND NOT deleted ORDER BY id LIMIT 1") suspend fun ownedGroupTitled(title: String, uid: String): GroupEntity?
    @Query("SELECT COUNT(*) FROM groups") suspend fun groupCount(): Int
    @Query("SELECT MAX(pinned) FROM lists WHERE NOT deleted") suspend fun maxPinned(): Int?

    // --- writes ---

    @Insert suspend fun insertTask(task: TaskEntity): Long
    @Update suspend fun updateTask(task: TaskEntity)
    @Query("DELETE FROM tasks WHERE id = :id") suspend fun deleteTask(id: Long)
    @Upsert suspend fun upsertGroup(group: GroupEntity): Long
    @Upsert suspend fun upsertList(list: ListEntity): Long
    @Insert suspend fun insertGroup(group: GroupEntity): Long
    @Insert suspend fun insertList(list: ListEntity): Long
    @Update suspend fun updateGroup(group: GroupEntity)
    @Update suspend fun updateList(list: ListEntity)
    @Query("UPDATE groups SET collapsed = :collapsed WHERE id = :id") suspend fun setGroupCollapsed(id: Long, collapsed: Boolean)
    @Query("UPDATE groups SET etag = :etag WHERE id = :id") suspend fun setGroupEtag(id: Long, etag: String)
    @Query("DELETE FROM groups WHERE id = :id") suspend fun deleteGroup(id: Long)
    @Query("UPDATE groups SET hidden = :hidden WHERE id = :id") suspend fun setGroupHidden(id: Long, hidden: Boolean)
    @Query("DELETE FROM lists WHERE id = :id") suspend fun deleteList(id: Long)
    @Query("UPDATE groups SET position = :position WHERE id = :id") suspend fun setGroupPosition(id: Long, position: Int?)
    @Query("UPDATE lists SET position = :position WHERE id = :id") suspend fun setListPosition(id: Long, position: Int?)
    @Query("UPDATE lists SET pinned = :pinned WHERE id = :id") suspend fun setListPinned(id: Long, pinned: Int?)
    @Query("UPDATE lists SET pinned = NULL") suspend fun unpinAll()

    // --- outbox ---

    @Insert suspend fun enqueue(op: OutboxEntity)
    @Query("SELECT * FROM outbox ORDER BY id") suspend fun outbox(): List<OutboxEntity>
    @Query("DELETE FROM outbox WHERE id = :id") suspend fun dequeue(id: Long)
    @Query("SELECT COUNT(*) FROM outbox WHERE rowId = :taskId AND kind IN $TASK_OPS") suspend fun pendingFor(taskId: Long): Int
    @Query("SELECT COUNT(*) FROM outbox WHERE rowId = :listId AND kind IN $LIST_OPS") suspend fun pendingForList(listId: Long): Int
    @Query("SELECT COUNT(*) FROM outbox WHERE rowId = :groupId AND kind IN $GROUP_OPS") suspend fun pendingForGroup(groupId: Long): Int
    @Query("SELECT COUNT(*) FROM outbox WHERE rowId = :rowId AND kind = :kind") suspend fun pendingOfKind(rowId: Long, kind: OutboxEntity.Kind): Int
    @Query("DELETE FROM outbox WHERE rowId = :rowId AND kind = :kind") suspend fun dropPending(rowId: Long, kind: OutboxEntity.Kind)
    @Query("UPDATE outbox SET arg = :arg WHERE id = :id") suspend fun setArg(id: Long, arg: String?)
    @Query("SELECT COUNT(*) FROM outbox") fun pendingCount(): Flow<Int>

    /** A local edit: save the row with a bumped version and queue one UPDATE (or none if a CREATE is still queued). */
    @Transaction
    suspend fun edit(id: Long, change: (TaskEntity) -> TaskEntity) {
        val current = task(id) ?: return
        updateTask(change(current).copy(localVersion = current.localVersion + 1))
        val queued = pendingOfKind(id, OutboxEntity.Kind.CREATE) + pendingOfKind(id, OutboxEntity.Kind.UPDATE)
        if (queued == 0) enqueue(OutboxEntity(kind = OutboxEntity.Kind.UPDATE, rowId = id))
    }

    /**
     * A rename (or reorder) of a list: the push sends the row as it then is, so one queued
     * LIST_UPDATE covers any number of edits. One is queued even behind a LIST_CREATE: the
     * create may already be in flight with the old title.
     */
    @Transaction
    suspend fun editList(id: Long, change: (ListEntity) -> ListEntity) {
        val current = listNow(id) ?: return
        updateList(change(current))
        if (pendingOfKind(id, OutboxEntity.Kind.LIST_UPDATE) == 0) enqueue(OutboxEntity(kind = OutboxEntity.Kind.LIST_UPDATE, rowId = id))
    }

    /** As [editList], for a group's title. */
    @Transaction
    suspend fun editGroup(id: Long, change: (GroupEntity) -> GroupEntity) {
        val current = group(id) ?: return
        updateGroup(change(current))
        if (pendingOfKind(id, OutboxEntity.Kind.GROUP_UPDATE) == 0) enqueue(OutboxEntity(kind = OutboxEntity.Kind.GROUP_UPDATE, rowId = id))
    }

    /**
     * Stores what "Volgorde aanpassen" ended with. Places and pins stay on this phone; the
     * order of lists within a board goes to Deck as stack order, but only where it changed,
     * so saving an untouched layout sends nothing. Returns whether anything was queued.
     */
    @Transaction
    suspend fun saveLayout(layout: HomeLayout): Boolean {
        layout.top.forEachIndexed { place, item ->
            when (item) {
                is HomeLayout.Top.Loose -> setListPosition(item.listId, place)
                is HomeLayout.Top.Group -> setGroupPosition(item.groupId, place)
            }
        }
        unpinAll()
        layout.pinned.forEachIndexed { place, id -> setListPinned(id, place) }

        val loose = layout.top.filterIsInstance<HomeLayout.Top.Loose>().map { it.listId }
        val grouped = layout.top.filterIsInstance<HomeLayout.Top.Group>().filter { it.canReorder }.map { it.lists }
        var queued = false
        for (ids in grouped + listOf(loose)) {
            val lists = ids.mapNotNull { listNow(it) }
            if (lists.zipWithNext().all { (a, b) -> a.order < b.order }) continue
            lists.forEachIndexed { order, list ->
                if (list.order != order) {
                    editList(list.id) { it.copy(order = order) }
                    queued = true
                }
            }
        }
        return queued
    }
}
