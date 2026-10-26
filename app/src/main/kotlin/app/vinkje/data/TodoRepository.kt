package app.vinkje.data

import android.content.SharedPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.withContext
import app.vinkje.deck.DeckClient
import app.vinkje.deck.User
import app.vinkje.domain.DeckConventions
import app.vinkje.domain.HomeLayout
import app.vinkje.domain.Recurrence
import app.vinkje.domain.Recurring
import app.vinkje.domain.TaskBody
import app.vinkje.domain.TaskBodyCodec
import app.vinkje.reminders.Reminders
import app.vinkje.sync.assigneeSet
import app.vinkje.sync.SyncScheduler
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** Everything that decides which rows the home screen has. */
data class HomeData(val groups: List<GroupEntity>, val lists: List<ListWithCount>, val anyShared: Boolean)

/** Undo information for ticking a task off; see [TodoRepository.complete]. */
data class Completion(val taskId: Long, val previousDescription: String, val spawnedId: Long?, val nextDue: LocalDate?)

/**
 * Every UI action goes through here: write the local row first (the UI updates at
 * once, also offline), queue the change, and nudge the sync.
 */
class TodoRepository(
    private val dao: TodoDao,
    private val scheduler: SyncScheduler,
    private val reminders: Reminders,
    private val session: Session,
    private val prefs: SharedPreferences,
    /** For what only works online, like finding someone to share with; null when logged out. */
    private val deck: () -> DeckClient? = { null },
    private val zone: () -> ZoneId = ZoneId::systemDefault,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {
    /**
     * Outlives the home screen, which is disposed while a list is open: coming back, its
     * rows are there in the first frame, so the list does not lose its scroll position.
     */
    val home: StateFlow<HomeData?> = combine(dao.groups(), dao.listsWithCounts(), dao.anyShared(), ::HomeData)
        .stateIn(scope, SharingStarted.WhileSubscribed(stopTimeoutMillis = 60_000), null)

    fun groups() = dao.groups()
    fun lists() = dao.listsWithCounts()
    fun list(id: Long) = dao.list(id)
    fun group(id: Long) = dao.groupFlow(id)
    fun tasks(listId: Long) = dao.tasks(listId)
    fun task(id: Long) = dao.taskFlow(id)
    fun pendingCount() = dao.pendingCount()
    fun writableLists() = dao.writableLists()

    fun today(): LocalDate = LocalDate.now(zone())
    val selfUid get() = session.selfUid

    @OptIn(ExperimentalCoroutinesApi::class)
    fun smartCounts() = session.selfUid.flatMapLatest { dao.smartCounts(today().toString(), it ?: "") }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun assignedToMe(since: Long) = session.selfUid.flatMapLatest { dao.assigned(it ?: "", since) }

    fun anyShared() = dao.anyShared()

    /** Who can be assigned to this task: the people its group is shared with. */
    fun members(taskId: Long): Flow<Map<String, String>> = dao.groupOfTask(taskId).map { Members.decode(it?.members ?: "") }

    /** Everyone this phone knows by uid, for showing names on task rows. */
    fun people(): Flow<Map<String, String>> = dao.allMembers().map { columns -> columns.fold(emptyMap()) { all, c -> all + Members.decode(c) } }
    fun myDay(since: Long) = dao.myDay(today().toString(), since)
    fun important(since: Long) = dao.important(since)
    fun planned(since: Long) = dao.planned(since)

    fun syncNow() = scheduler.kick(delayMs = 0)

    suspend fun setGroupHidden(id: Long, hidden: Boolean) = dao.setGroupHidden(id, hidden)
    suspend fun setGroupCollapsed(id: Long, collapsed: Boolean) = dao.setGroupCollapsed(id, collapsed)

    /**
     * The home screen's order. Lists outside a group and groups share one level; until
     * arranged, the lists outside a group come first in Deck order, then the groups by title.
     */
    fun layout(home: HomeData): HomeLayout {
        val (loose, grouped) = home.groups.partition(::isLoose)
        val looseIds = loose.map { it.id }.toSet()
        val top = home.lists.filter { it.groupId in looseIds }.map { it.position to HomeLayout.Top.Loose(it.id) } +
            grouped.map { group -> group.position to HomeLayout.Top.Group(group.id, home.lists.filter { it.groupId == group.id }.map { it.id }, group.canManage) }
        val groupIds = home.groups.map { it.id }.toSet()
        val pinned = home.lists.filter { it.pinned != null && it.groupId in groupIds }.sortedBy { it.pinned }.map { it.id }
        return HomeLayout(pinned, HomeLayout.byPosition(top) { it.first }.map { it.second })
    }

    suspend fun saveLayout(layout: HomeLayout) {
        if (dao.saveLayout(layout)) scheduler.kick()
    }

    /** Pinned at the bottom of the pinned lists, on this phone only. */
    suspend fun setPinned(listId: Long, pinned: Boolean) = dao.setListPinned(listId, if (pinned) (dao.maxPinned() ?: -1) + 1 else null)

    // ---------------------------------------------------------------- lists and groups

    /** A new list at the bottom of [groupId], or among the lists outside any group. */
    suspend fun createList(title: String, groupId: Long? = null): Long {
        val group = groupId ?: looseGroup()
        val id = dao.insertList(ListEntity(serverId = null, groupId = group, title = title.trim(), order = (dao.maxListOrder(group) ?: -1) + 1))
        dao.enqueue(OutboxEntity(kind = OutboxEntity.Kind.LIST_CREATE, rowId = id))
        scheduler.kick()
        return id
    }

    suspend fun renameList(id: Long, title: String) {
        if (title.isBlank()) return
        dao.editList(id) { it.copy(title = title.trim()) }
        scheduler.kick()
    }

    /** Lists go for good, as in To Do, which asks first; so there is no undo window. */
    suspend fun deleteList(id: Long) {
        val list = dao.listNow(id) ?: return
        dao.updateList(list.copy(deleted = true))
        dao.enqueue(OutboxEntity(kind = OutboxEntity.Kind.LIST_DELETE, rowId = id))
        scheduler.kick()
    }

    /** Moves a list to [groupId], or out of any group when null. */
    suspend fun moveList(id: Long, groupId: Long?) {
        val list = dao.listNow(id) ?: return
        val target = groupId ?: looseGroup()
        if (target == list.groupId) return
        dao.updateList(
            list.copy(
                groupId = target,
                serverGroupId = list.homeGroupId.takeIf { it != target },
                order = (dao.maxListOrder(target) ?: -1) + 1,
                // Out of a group it goes to the bottom, not to where it once was.
                position = null,
            )
        )
        dao.enqueue(OutboxEntity(kind = OutboxEntity.Kind.LIST_MOVE, rowId = id))
        scheduler.kick()
    }

    suspend fun createGroup(title: String): Long {
        val id = newGroup(title.trim())
        scheduler.kick()
        return id
    }

    suspend fun renameGroup(id: Long, title: String) {
        if (title.isBlank()) return
        dao.editGroup(id) { it.copy(title = title.trim()) }
        scheduler.kick()
    }

    /** The group and every list in it, for everyone it is shared with. */
    suspend fun deleteGroup(id: Long) {
        val group = dao.group(id) ?: return
        dao.updateGroup(group.copy(deleted = true))
        dao.enqueue(OutboxEntity(kind = OutboxEntity.Kind.GROUP_DELETE, rowId = id))
        scheduler.kick()
    }

    /** Shows the person at once; the board's member list from the server replaces this after the push. */
    suspend fun share(groupId: Long, user: User) {
        val group = dao.group(groupId) ?: return
        val members = Members.decode(group.members) + (user.uid to user.displayname)
        val names = group.sharedWith.split(", ").filter { it.isNotBlank() } + user.displayname
        dao.updateGroup(group.copy(members = Members.encode(members), sharedWith = names.distinct().joinToString(", ")))
        dao.enqueue(OutboxEntity(kind = OutboxEntity.Kind.SHARE, rowId = groupId, arg = user.uid))
        scheduler.kick()
    }

    suspend fun unshare(groupId: Long, uid: String) {
        val group = dao.group(groupId) ?: return
        val members = Members.decode(group.members)
        val name = members[uid]
        dao.updateGroup(
            group.copy(
                members = Members.encode(members - uid),
                sharedWith = group.sharedWith.split(", ").filter { it.isNotBlank() && it != name }.joinToString(", "),
            )
        )
        dao.enqueue(OutboxEntity(kind = OutboxEntity.Kind.UNSHARE, rowId = groupId, arg = uid))
        scheduler.kick()
    }

    /** People to share with; needs the server, so it throws when offline. */
    suspend fun findUsers(search: String): List<User> = withContext(Dispatchers.IO) {
        val client = deck() ?: return@withContext emptyList()
        val self = session.selfUid.value
        client.findUsers(search).filter { it.uid != self }
    }

    /** Whether [group] holds the lists shown outside any group. */
    fun isLoose(group: GroupEntity) = group.title == GroupEntity.LOOSE_TITLE && group.ownerUid == session.selfUid.value

    /** The private board for lists outside a group, made on first use. */
    private suspend fun looseGroup(): Long =
        dao.ownedGroupTitled(GroupEntity.LOOSE_TITLE, session.selfUid.value ?: "")?.id ?: newGroup(GroupEntity.LOOSE_TITLE)

    private suspend fun newGroup(title: String): Long {
        val id = dao.insertGroup(
            GroupEntity(
                serverId = null, title = title, color = GROUP_COLORS[dao.groupCount() % GROUP_COLORS.size],
                ownerUid = session.selfUid.value ?: "", canEdit = true, canManage = true, canShare = true,
                sharedWith = "", importantLabelId = null, etag = "",
            )
        )
        dao.enqueue(OutboxEntity(kind = OutboxEntity.Kind.GROUP_CREATE, rowId = id))
        return id
    }

    /** Where tasks typed into a smart list go: the last list picked there, else the first one. */
    suspend fun defaultListId(): Long? {
        val lists = dao.writableLists().first()
        val stored = prefs.getLong(KEY_DEFAULT_LIST, -1)
        return lists.firstOrNull { it.id == stored }?.id ?: lists.firstOrNull()?.id
    }

    fun setDefaultList(id: Long) = prefs.edit().putLong(KEY_DEFAULT_LIST, id).apply()

    /** New tasks go on top, as in To Do. */
    suspend fun addTask(
        listId: Long,
        title: String,
        important: Boolean = false,
        due: LocalDate? = null,
        myDay: Boolean = false,
        description: String = "",
    ): Long {
        val order = (dao.minOrder(listId) ?: 1) - 1
        val id = dao.insertTask(
            TaskEntity(
                listId = listId, title = title.trim(), order = order, important = important, description = description,
                dueEpochSecond = due?.let(::dueInstant), myDay = if (myDay) today().toString() else null,
            )
        )
        dao.enqueue(OutboxEntity(kind = OutboxEntity.Kind.CREATE, rowId = id))
        scheduler.kick()
        return id
    }

    /**
     * Ticks a task off. A repeating task hands its rule to a fresh copy with the next due
     * date (and the reminder moved along), as To Do does; the returned [Completion] undoes both.
     */
    suspend fun complete(id: Long): Completion? {
        val task = dao.task(id) ?: return null
        if (task.doneEpochSecond != null) return null
        val body = TaskBodyCodec.parse(task.description)
        val oldDue = task.dueEpochSecond?.let(::dueDate)
        val next = Recurring.next(body, oldDue, today())
        var spawned: Long? = null
        if (next != null) {
            spawned = addTask(task.listId, task.title, important = task.important, due = next.due, description = TaskBodyCodec.format(next.body))
            task.reminderEpochSecond?.let { at ->
                setReminder(spawned, at + ChronoUnit.DAYS.between(oldDue ?: today(), next.due) * 86_400)
            }
        }
        edit(id) {
            it.copy(
                doneEpochSecond = System.currentTimeMillis() / 1000,
                description = if (next != null) TaskBodyCodec.format(next.completedBody) else it.description,
            )
        }
        return Completion(id, task.description, spawned, next?.due)
    }

    suspend fun undoComplete(c: Completion) {
        c.spawnedId?.let { discard(it) }
        edit(c.taskId) { it.copy(doneEpochSecond = null, description = c.previousDescription) }
    }

    suspend fun reopen(id: Long) = edit(id) { it.copy(doneEpochSecond = null) }

    suspend fun setImportant(id: Long, important: Boolean) = edit(id) { it.copy(important = important) }

    suspend fun setAssigned(id: Long, uid: String, assigned: Boolean) = edit(id) { task ->
        val now = assigneeSet(task.assignees).let { if (assigned) it + uid else it - uid }
        task.copy(assignees = now.sorted().joinToString(","))
    }

    suspend fun rename(id: Long, title: String) {
        if (title.isNotBlank()) edit(id) { it.copy(title = title) }
    }

    suspend fun updateBody(id: Long, change: (TaskBody) -> TaskBody) = edit(id) {
        it.copy(description = TaskBodyCodec.format(change(TaskBodyCodec.parse(it.description))))
    }

    /** Clearing the due date of a repeating task also ends the repeat: in To Do a rule needs a date. */
    suspend fun setDue(id: Long, due: LocalDate?) = edit(id) { task ->
        val body = TaskBodyCodec.parse(task.description)
        val description = if (due == null && body.recurrence != null) TaskBodyCodec.format(body.withRecurrence(null)) else task.description
        task.copy(dueEpochSecond = due?.let(::dueInstant), description = description)
    }

    /** Setting a rule on a task without a due date makes it due today. */
    suspend fun setRecurrence(id: Long, rule: Recurrence?) = edit(id) { task ->
        val body = TaskBodyCodec.parse(task.description).withRecurrence(rule)
        task.copy(
            description = TaskBodyCodec.format(body),
            dueEpochSecond = task.dueEpochSecond ?: if (rule != null) dueInstant(today()) else null,
        )
    }

    suspend fun setMyDay(id: Long, inMyDay: Boolean) = dao.setMyDay(id, if (inMyDay) today().toString() else null)

    suspend fun setReminder(id: Long, atEpochSecond: Long?) {
        dao.setReminder(id, atEpochSecond)
        reminders.schedule(id, atEpochSecond)
    }

    /** Hidden at once; reaches the server only after the undo window. */
    suspend fun delete(id: Long) {
        val task = dao.task(id) ?: return
        dao.updateTask(task.copy(deleted = true))
        dao.enqueue(OutboxEntity(kind = OutboxEntity.Kind.DELETE, rowId = id, notBefore = System.currentTimeMillis() + UNDO_WINDOW_MS))
        scheduler.kick(delayMs = UNDO_WINDOW_MS + 500)
    }

    /** Every ticked-off task in the list, with the same undo window as a single delete; returns their ids. */
    suspend fun deleteDone(listId: Long): List<Long> {
        val done = dao.doneTasks(listId)
        for (task in done) {
            dao.updateTask(task.copy(deleted = true))
            dao.enqueue(OutboxEntity(kind = OutboxEntity.Kind.DELETE, rowId = task.id, notBefore = System.currentTimeMillis() + UNDO_WINDOW_MS))
        }
        if (done.isNotEmpty()) scheduler.kick(delayMs = UNDO_WINDOW_MS + 500)
        return done.map { it.id }
    }

    suspend fun undoDelete(id: Long) {
        val task = dao.task(id) ?: return
        dao.dropPending(id, OutboxEntity.Kind.DELETE)
        dao.updateTask(task.copy(deleted = false))
    }

    /**
     * Removes a task without an undo window. Its CREATE may already be on its way to the
     * server, so dropping it from the outbox could leave an orphan card behind; queueing
     * a DELETE after it is always right, at worst a create that is deleted straight away.
     */
    private suspend fun discard(id: Long) {
        val task = dao.task(id) ?: return
        reminders.schedule(id, null)
        dao.updateTask(task.copy(deleted = true))
        dao.enqueue(OutboxEntity(kind = OutboxEntity.Kind.DELETE, rowId = id))
        scheduler.kick()
    }

    private suspend fun edit(id: Long, change: (TaskEntity) -> TaskEntity) {
        dao.edit(id, change)
        scheduler.kick()
    }

    private fun dueInstant(date: LocalDate): Long = DeckConventions.dueInstant(date, zone()).epochSecond

    fun dueDate(epochSecond: Long): LocalDate = Instant.ofEpochSecond(epochSecond).atZone(zone()).toLocalDate()

    companion object {
        const val UNDO_WINDOW_MS = 6_000L

        private val GROUP_COLORS = DeckConventions.GROUP_COLORS
        private const val KEY_DEFAULT_LIST = "defaultList"
    }
}
