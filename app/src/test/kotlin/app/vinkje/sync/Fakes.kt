package app.vinkje.sync

import android.content.SharedPreferences
import app.vinkje.data.GroupEntity
import app.vinkje.data.ListEntity
import app.vinkje.data.ListWithCount
import app.vinkje.data.OutboxEntity
import app.vinkje.data.SmartCounts
import app.vinkje.data.SmartTask
import app.vinkje.data.TaskEntity
import app.vinkje.data.TodoDao
import kotlinx.coroutines.flow.Flow

/** SharedPreferences in a map; the real ones need a device. */
class FakePrefs : SharedPreferences {
    val map = mutableMapOf<String, Any?>()
    override fun getAll(): MutableMap<String, *> = map
    override fun getString(key: String?, defValue: String?) = map[key] as String? ?: defValue
    override fun getStringSet(key: String?, defValues: MutableSet<String>?): MutableSet<String>? = TODO()
    override fun getInt(key: String?, defValue: Int) = map[key] as Int? ?: defValue
    override fun getLong(key: String?, defValue: Long) = map[key] as Long? ?: defValue
    override fun getFloat(key: String?, defValue: Float) = map[key] as Float? ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean) = map[key] as Boolean? ?: defValue
    override fun contains(key: String?) = key in map
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) = Unit

    override fun edit(): SharedPreferences.Editor = object : SharedPreferences.Editor {
        override fun putString(key: String?, value: String?) = apply { map[key!!] = value }
        override fun putStringSet(key: String?, values: MutableSet<String>?) = apply { map[key!!] = values }
        override fun putInt(key: String?, value: Int) = apply { map[key!!] = value }
        override fun putLong(key: String?, value: Long) = apply { map[key!!] = value }
        override fun putFloat(key: String?, value: Float) = apply { map[key!!] = value }
        override fun putBoolean(key: String?, value: Boolean) = apply { map[key!!] = value }
        override fun remove(key: String?) = apply { map.remove(key) }
        override fun clear() = apply { map.clear() }
        override fun commit() = true
        override fun apply() = Unit
    }
}

/** An in-memory [TodoDao] with Room's cascades and unique server ids, for what [SyncEngine] uses. */
class FakeDao : TodoDao {
    val groups = mutableListOf<GroupEntity>()
    val lists = mutableListOf<ListEntity>()
    val tasks = mutableListOf<TaskEntity>()
    val ops = mutableListOf<OutboxEntity>()
    private var nextId = 1L

    override fun groups(): Flow<List<GroupEntity>> = TODO()
    override fun listsWithCounts(): Flow<List<ListWithCount>> = TODO()
    override fun list(id: Long): Flow<ListEntity?> = TODO()
    override fun groupFlow(id: Long): Flow<GroupEntity?> = TODO()
    override fun tasks(listId: Long): Flow<List<TaskEntity>> = TODO()
    override fun taskFlow(id: Long): Flow<TaskEntity?> = TODO()
    override fun myDay(today: String, since: Long): Flow<List<SmartTask>> = TODO()
    override fun important(since: Long): Flow<List<SmartTask>> = TODO()
    override fun planned(since: Long): Flow<List<SmartTask>> = TODO()
    override fun assigned(uid: String, since: Long): Flow<List<SmartTask>> = TODO()
    override fun smartCounts(today: String, uid: String): Flow<SmartCounts> = TODO()
    override fun anyShared(): Flow<Boolean> = TODO()
    override fun groupOfTask(taskId: Long): Flow<GroupEntity?> = TODO()
    override fun allMembers(): Flow<List<String>> = TODO()
    override fun writableLists(): Flow<List<ListEntity>> = TODO()
    override fun pendingCount(): Flow<Int> = TODO()

    override suspend fun task(id: Long) = tasks.firstOrNull { it.id == id }
    override suspend fun taskByServerId(serverId: Long) = tasks.firstOrNull { it.serverId == serverId }
    override suspend fun listNow(id: Long) = lists.firstOrNull { it.id == id }
    override suspend fun listByServerId(serverId: Long) = lists.firstOrNull { it.serverId == serverId }
    override suspend fun listsOf(groupId: Long) = lists.filter { it.groupId == groupId }
    override suspend fun group(id: Long) = groups.firstOrNull { it.id == id }
    override suspend fun groupByServerId(serverId: Long) = groups.firstOrNull { it.serverId == serverId }
    override suspend fun allGroups() = groups.toList()
    override suspend fun tasksOf(listId: Long) = tasks.filter { it.listId == listId }
    override suspend fun tasksWithReminder(): List<TaskEntity> = TODO()
    override suspend fun doneTasks(listId: Long): List<TaskEntity> = TODO()
    override suspend fun setMyDay(id: Long, day: String?) = TODO()
    override suspend fun setReminder(id: Long, at: Long?) = TODO()
    override suspend fun minOrder(listId: Long): Int? = TODO()
    override suspend fun maxListOrder(groupId: Long): Int? = TODO()
    override suspend fun ownedGroupTitled(title: String, uid: String): GroupEntity? = TODO()
    override suspend fun groupCount(): Int = TODO()
    override suspend fun maxPinned(): Int? = TODO()

    override suspend fun insertTask(task: TaskEntity): Long {
        check(task.serverId == null || tasks.none { it.serverId == task.serverId }) { "unique serverId" }
        val t = task.copy(id = nextId++); tasks += t; return t.id
    }
    override suspend fun updateTask(task: TaskEntity) { tasks.replaceAll { if (it.id == task.id) task else it } }
    override suspend fun deleteTask(id: Long) { tasks.removeAll { it.id == id } }
    override suspend fun upsertGroup(group: GroupEntity): Long =
        if (group.id == 0L) insertGroup(group) else { updateGroup(group); -1 }
    override suspend fun upsertList(list: ListEntity): Long = TODO()
    override suspend fun insertGroup(group: GroupEntity): Long { val g = group.copy(id = nextId++); groups += g; return g.id }
    override suspend fun insertList(list: ListEntity): Long { val l = list.copy(id = nextId++); lists += l; return l.id }
    override suspend fun updateGroup(group: GroupEntity) { groups.replaceAll { if (it.id == group.id) group else it } }
    override suspend fun updateList(list: ListEntity) { lists.replaceAll { if (it.id == list.id) list else it } }
    override suspend fun setGroupCollapsed(id: Long, collapsed: Boolean) = TODO()
    override suspend fun setGroupEtag(id: Long, etag: String) { groups.replaceAll { if (it.id == id) it.copy(etag = etag) else it } }
    override suspend fun deleteGroup(id: Long) { lists.filter { it.groupId == id }.forEach { deleteList(it.id) }; groups.removeAll { it.id == id } }
    override suspend fun setGroupHidden(id: Long, hidden: Boolean) = TODO()
    override suspend fun deleteList(id: Long) { tasks.removeAll { it.listId == id }; lists.removeAll { it.id == id } }
    override suspend fun setGroupPosition(id: Long, position: Int?) = TODO()
    override suspend fun setListPosition(id: Long, position: Int?) = TODO()
    override suspend fun setListPinned(id: Long, pinned: Int?) = TODO()
    override suspend fun unpinAll() = TODO()

    override suspend fun enqueue(op: OutboxEntity) { ops += op.copy(id = nextId++) }
    override suspend fun outbox() = ops.sortedBy { it.id }
    override suspend fun dequeue(id: Long) { ops.removeAll { it.id == id } }
    override suspend fun pendingFor(taskId: Long) = ops.count { it.rowId == taskId && it.kind in OutboxEntity.Kind.TASK }
    override suspend fun pendingForList(listId: Long) = ops.count { it.rowId == listId && it.kind in OutboxEntity.Kind.LIST }
    override suspend fun pendingForGroup(groupId: Long) = ops.count { it.rowId == groupId && it.kind in OutboxEntity.Kind.GROUP }
    override suspend fun pendingOfKind(rowId: Long, kind: OutboxEntity.Kind) = ops.count { it.rowId == rowId && it.kind == kind }
    override suspend fun dropPending(rowId: Long, kind: OutboxEntity.Kind) { ops.removeAll { it.rowId == rowId && it.kind == kind } }
    override suspend fun setArg(id: Long, arg: String?) { ops.replaceAll { if (it.id == id) it.copy(arg = arg) else it } }
    override suspend fun setNotBefore(id: Long, at: Long) { ops.replaceAll { if (it.id == id) it.copy(notBefore = at) else it } }
}
