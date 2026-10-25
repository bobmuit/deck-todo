package app.vinkje.sync

import android.content.SharedPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import app.vinkje.data.GroupEntity
import app.vinkje.data.ListEntity
import app.vinkje.data.Members
import app.vinkje.data.OutboxEntity
import app.vinkje.data.Session
import app.vinkje.data.TaskEntity
import app.vinkje.data.TodoDao
import app.vinkje.deck.Board
import app.vinkje.deck.Card
import app.vinkje.deck.DeckClient
import app.vinkje.deck.DeckException
import app.vinkje.deck.ImportHandoff
import app.vinkje.domain.Changes
import app.vinkje.domain.Merge
import app.vinkje.domain.TaskBodyCodec
import app.vinkje.domain.TaskState
import app.vinkje.domain.task
import java.io.IOException
import java.time.Instant

/** Serialises sync rounds, and keeps logging out from wiping the database under one. */
internal val syncLock = Mutex()

/**
 * One sync round: push the outbox in order, then pull what changed on the server.
 *
 * Each outbox operation runs NonCancellable together with its local write-back, so
 * WorkManager replacing or stopping the worker can never leave a card created on the
 * server without its id stored here (which would duplicate it on the next round).
 */
class SyncEngine(
    private val dao: TodoDao,
    private val client: DeckClient,
    private val prefs: SharedPreferences,
    private val session: Session,
    private val changes: ChangeSink = ChangeSink {},
    /** Whose changes are not news; the logged-in user, except in a debug test of the notifications. */
    private val notifyAs: () -> String? = { session.selfUid.value },
    private val now: () -> Long = System::currentTimeMillis,
    /** Sets a reminder on a task, as the task screen does; used for reminders from the To Do import. */
    private val adoptReminder: suspend (taskId: Long, atEpochSecond: Long) -> Unit = { _, _ -> },
) {
    /**
     * Returns the delay in ms until deferred work (deletes still inside their undo window) is due, or null.
     *
     * Runs are serialised: the periodic and the one-off worker can start together, and two
     * pulls racing on the same new card would both try to insert it.
     */
    suspend fun run(): Long? = syncLock.withLock {
        withContext(Dispatchers.IO) {
            if (session.selfUid.value == null) session.setSelf(client.currentUser().uid)
            val deferredUntil = push()
            val seen = pull()
            tellOthersChanges(seen)
            adoptImportedReminders()
            deferredUntil?.let { (it - now()).coerceAtLeast(0) }
        }
    }

    /**
     * To Do reminders are personal, so the importer leaves them in a file in the user's own
     * Nextcloud files rather than on the shared cards. Reminders whose card has not arrived
     * yet stay in the file for a later round; ones in the past are dropped.
     */
    private suspend fun adoptImportedReminders() {
        val uid = session.selfUid.value ?: return
        // Never worth failing the round over: the file is still there next time.
        try {
            adoptImportedReminders(uid)
        } catch (e: IOException) {
            Unit
        }
    }

    private suspend fun adoptImportedReminders(uid: String) {
        val text = client.readFile(uid, ImportHandoff.PATH) ?: return
        val handoff = runCatching { handoffJson.decodeFromString<ImportHandoff>(text) }.getOrNull() ?: return
        val nowSec = now() / 1000
        val left = handoff.reminders.filter { r ->
            if (r.at <= nowSec) return@filter false
            val task = dao.taskByServerId(r.card) ?: return@filter true
            if (task.reminderEpochSecond == null && task.doneEpochSecond == null && !task.deleted) adoptReminder(task.id, r.at)
            false
        }
        if (left.size == handoff.reminders.size) return
        withContext(NonCancellable) {
            if (left.isEmpty()) client.deleteFile(uid, ImportHandoff.PATH)
            else client.writeFile(uid, ImportHandoff.PATH, handoffJson.encodeToString(ImportHandoff(left)))
        }
    }

    // ---------------------------------------------------------------- push

    private suspend fun push(): Long? {
        var deferredUntil: Long? = null
        for (op in dao.outbox()) {
            if (op.notBefore > now()) {
                deferredUntil = minOf(deferredUntil ?: Long.MAX_VALUE, op.notBefore)
                continue
            }
            withContext(NonCancellable) {
                try {
                    when (op.kind) {
                        OutboxEntity.Kind.CREATE -> pushCreate(op.rowId)
                        OutboxEntity.Kind.UPDATE -> pushUpdate(op.rowId)
                        OutboxEntity.Kind.DELETE -> pushDelete(op.rowId)
                        OutboxEntity.Kind.LIST_CREATE -> pushListCreate(op.rowId)
                        OutboxEntity.Kind.LIST_UPDATE -> pushListUpdate(op.rowId)
                        OutboxEntity.Kind.LIST_DELETE -> pushListDelete(op.rowId)
                        OutboxEntity.Kind.LIST_MOVE -> pushListMove(op)
                        OutboxEntity.Kind.GROUP_CREATE -> pushGroupCreate(op.rowId)
                        OutboxEntity.Kind.GROUP_UPDATE -> pushGroupUpdate(op.rowId)
                        OutboxEntity.Kind.GROUP_DELETE -> pushGroupDelete(op.rowId)
                        OutboxEntity.Kind.SHARE -> pushShare(op.rowId, op.arg ?: "")
                        OutboxEntity.Kind.UNSHARE -> pushUnshare(op.rowId, op.arg ?: "")
                    }
                } catch (e: DeckException) {
                    // 5xx and auth problems: stop and let WorkManager retry later.
                    if (e.status >= 500 || e.status == 401) throw e
                    // Other 4xx (gone, no permission): this op can never succeed.
                    dropFailed(op, e.status)
                }
                dao.dequeue(op.id)
            }
        }
        return deferredUntil
    }

    /**
     * A task that cannot reach the server is dropped here, as is a list or group that could
     * not be created. Any other refused change to a list or group is undone by fetching
     * every board again on this round's pull.
     */
    private suspend fun dropFailed(op: OutboxEntity, status: Int) {
        when (op.kind) {
            in OutboxEntity.Kind.TASK -> if (status == 404 || status == 403) dao.deleteTask(op.rowId)
            OutboxEntity.Kind.LIST_CREATE -> dao.deleteList(op.rowId)
            OutboxEntity.Kind.GROUP_CREATE -> dao.deleteGroup(op.rowId)
            else -> refetchAll = true
        }
    }

    /** Set when the server refused a change to a list or group, see [dropFailed]. */
    private var refetchAll = false

    private class Target(val group: GroupEntity, val boardId: Long, val stackId: Long)

    /** Where a task's card lives on the server: its list's stack, on the board that holds it now. */
    private suspend fun target(task: TaskEntity): Target? {
        val list = dao.listNow(task.listId)?.takeIf { !it.deleted } ?: return null
        val group = dao.group(list.homeGroupId)?.takeIf { !it.deleted } ?: return null
        return Target(group, group.serverId ?: return null, list.serverId ?: return null)
    }

    private suspend fun pushCreate(taskId: Long) {
        val task = dao.task(taskId) ?: return
        if (task.serverId != null) return
        val t = target(task) ?: return
        val sent = task.toState()
        var card = client.createCard(t.boardId, t.stackId, task.title, task.order, task.description, sent.dueEpochSecond?.let(Instant::ofEpochSecond))
        if (sent.done) {
            card = client.updateCard(
                t.boardId, t.stackId, card.id, card.title, card.ownerUid ?: "", card.order,
                task.description, sent.dueEpochSecond?.let(Instant::ofEpochSecond), Instant.ofEpochSecond(sent.doneEpochSecond!!),
            )
        }
        if (sent.important) setImportant(t, card.id, true)
        sent.assignees.forEach { client.assignUser(t.boardId, t.stackId, card.id, it) }
        val final = client.card(t.boardId, t.stackId, card.id)
        writeBack(task, sent, final) { it.copy(serverId = final.id, ownerUid = final.ownerUid) }
    }

    private suspend fun pushUpdate(taskId: Long) {
        val task = dao.task(taskId) ?: return
        val serverId = task.serverId ?: return // still waiting for its CREATE
        val t = target(task) ?: return
        val server = client.card(t.boardId, t.stackId, serverId)
        val remote = server.toState(t.group.importantLabelId)
        val local = task.toState()
        val merged = if (server.lastModified == task.serverLastModified) {
            local
        } else {
            Merge.task(decodeBase(task.base) ?: remote, local, remote)
        }

        val fieldsChanged = merged.title != remote.title || merged.body != remote.body ||
            merged.dueEpochSecond != remote.dueEpochSecond || merged.doneEpochSecond != remote.doneEpochSecond
        if (fieldsChanged) {
            // The card's own stack: a PUT moves the card to the stack in its URL, and someone
            // may have moved it in Deck web since this phone last pulled.
            client.updateCard(
                t.boardId, server.stackId, serverId, merged.title, server.ownerUid ?: "", server.order,
                TaskBodyCodec.format(merged.body),
                merged.dueEpochSecond?.let(Instant::ofEpochSecond),
                merged.doneEpochSecond?.let(Instant::ofEpochSecond),
            )
        }
        if (merged.important != remote.important) setImportant(t, serverId, merged.important)
        (merged.assignees - remote.assignees).forEach { client.assignUser(t.boardId, t.stackId, serverId, it) }
        (remote.assignees - merged.assignees).forEach { client.unassignUser(t.boardId, t.stackId, serverId, it) }
        val changed = fieldsChanged || merged.important != remote.important || merged.assignees != remote.assignees
        val final = if (changed) client.card(t.boardId, t.stackId, serverId) else server
        writeBack(task, merged, final) { it }
    }

    // ---------------------------------------------------------------- push: lists and groups

    private suspend fun pushListCreate(listId: Long) {
        val list = dao.listNow(listId) ?: return
        if (list.serverId != null || list.deleted) return
        val group = dao.group(list.groupId) ?: return
        val stack = client.createStack(group.serverId ?: return, list.title, list.order)
        // Fresh row: a rename while this was in flight queued its own LIST_UPDATE.
        dao.listNow(listId)?.let { dao.updateList(it.copy(serverId = stack.id, serverGroupId = group.id.takeIf { g -> g != it.groupId })) }
    }

    private suspend fun pushListUpdate(listId: Long) {
        val list = dao.listNow(listId)?.takeIf { !it.deleted } ?: return
        val boardId = dao.group(list.homeGroupId)?.serverId ?: return
        client.updateStack(boardId, list.serverId ?: return, list.title, list.order)
    }

    private suspend fun pushListDelete(listId: Long) {
        val list = dao.listNow(listId) ?: return
        val boardId = dao.group(list.homeGroupId)?.serverId
        if (list.serverId != null && boardId != null) {
            try {
                client.deleteStack(boardId, list.serverId)
            } catch (e: DeckException) {
                if (e.status != 404) throw e
            }
        }
        dao.deleteList(listId)
    }

    /**
     * A list to another group. Deck cannot move a stack between boards, so this makes a
     * stack on the new board, moves the cards over one by one (archived ones too, or deleting
     * the old stack would take them along) and then deletes the old stack. The new stack's id
     * is kept on the op, so a round cut short carries on where it stopped.
     */
    private suspend fun pushListMove(op: OutboxEntity) {
        val list = dao.listNow(op.rowId)?.takeIf { !it.deleted } ?: return
        val oldStack = list.serverId ?: return // not created yet: its create goes to the new group
        val from = dao.group(list.homeGroupId) ?: return
        val to = dao.group(list.groupId) ?: return
        if (from.id == to.id) return // an earlier move already took it there
        val fromBoard = from.serverId ?: return
        val toBoard = to.serverId ?: return
        val newStack = op.arg?.toLongOrNull()
            ?: client.createStack(toBoard, list.title, list.order).id.also { dao.setArg(op.id, "$it") }

        val cards = (client.stacks(fromBoard) + client.archivedStacks(fromBoard))
            .filter { it.id == oldStack }
            .flatMap { it.cards.orEmpty() }
        for (card in cards) client.moveCard(card, toBoard, newStack)
        try {
            client.deleteStack(fromBoard, oldStack)
        } catch (e: DeckException) {
            if (e.status != 404) throw e
        }
        dao.listNow(list.id)?.let { dao.updateList(it.copy(serverId = newStack, serverGroupId = null)) }
        // Both boards changed; the pull fetches them whatever their ETags say.
        listOf(from, to).forEach { dao.setGroupEtag(it.id, "") }
    }

    private suspend fun pushGroupCreate(groupId: Long) {
        val group = dao.group(groupId) ?: return
        if (group.serverId != null || group.deleted) return
        val board = client.createBoard(group.title, group.color)
        dao.group(groupId)?.let {
            dao.updateGroup(it.copy(serverId = board.id, ownerUid = board.owner.uid, members = Members.encode(members(board)), etag = ""))
        }
    }

    private suspend fun pushGroupUpdate(groupId: Long) {
        val group = dao.group(groupId)?.takeIf { !it.deleted } ?: return
        client.updateBoard(group.serverId ?: return, group.title, group.color)
    }

    private suspend fun pushGroupDelete(groupId: Long) {
        val group = dao.group(groupId) ?: return
        if (group.serverId != null) {
            try {
                client.deleteBoard(group.serverId)
            } catch (e: DeckException) {
                if (e.status != 404) throw e
            }
        }
        dao.deleteGroup(groupId)
    }

    /** Shared as an equal: Deck needs the manage permission to add or rename lists. */
    private suspend fun pushShare(groupId: Long, uid: String) {
        val group = dao.group(groupId)?.takeIf { !it.deleted } ?: return
        val boardId = group.serverId ?: return
        if (client.board(boardId).acl.none { it.type == 0 && it.participant.uid == uid }) {
            client.share(boardId, uid, edit = true, share = true, manage = true)
        }
        dao.setGroupEtag(groupId, "")
    }

    private suspend fun pushUnshare(groupId: Long, uid: String) {
        val group = dao.group(groupId)?.takeIf { !it.deleted } ?: return
        val boardId = group.serverId ?: return
        client.board(boardId).acl.filter { it.type == 0 && it.participant.uid == uid }.forEach { client.unshare(boardId, it.id) }
        dao.setGroupEtag(groupId, "")
    }

    private suspend fun pushDelete(taskId: Long) {
        val task = dao.task(taskId) ?: return
        val serverId = task.serverId
        if (serverId != null) {
            val t = target(task)
            if (t != null) {
                try {
                    client.deleteCard(t.boardId, t.stackId, serverId)
                } catch (e: DeckException) {
                    if (e.status != 404) throw e
                }
            }
        }
        dao.deleteTask(taskId)
    }

    /**
     * Stores what the server now has as the new merge base. If the user edited the task
     * while this push was in flight, their newer edits are kept on top of [pushed] and
     * another UPDATE is queued (the in-flight op suppressed queueing one).
     */
    private suspend fun writeBack(sentFrom: TaskEntity, pushed: TaskState, final: Card, extra: (TaskEntity) -> TaskEntity) {
        val fresh = dao.task(sentFrom.id) ?: return
        val updated = if (fresh.localVersion == sentFrom.localVersion) {
            fresh.withState(pushed)
        } else {
            fresh.withState(Merge.task(sentFrom.toState(), fresh.toState(), pushed))
        }
        dao.updateTask(extra(updated).copy(serverLastModified = final.lastModified, base = encodeBase(pushed)))
        if (fresh.localVersion != sentFrom.localVersion && dao.pendingOfKind(fresh.id, OutboxEntity.Kind.UPDATE) <= 1) {
            dao.enqueue(OutboxEntity(kind = OutboxEntity.Kind.UPDATE, rowId = fresh.id))
        }
    }

    private suspend fun setImportant(t: Target, cardId: Long, important: Boolean) {
        val labelId = importantLabel(t.group)
        if (important) client.assignLabel(t.boardId, t.stackId, cardId, labelId)
        else client.removeLabel(t.boardId, t.stackId, cardId, labelId)
    }

    private suspend fun importantLabel(group: GroupEntity): Long {
        group.importantLabelId?.let { return it }
        val board = client.board(group.serverId!!)
        val label = board.labels.firstOrNull { it.title == IMPORTANT_LABEL }
            ?: client.createLabel(board.id, IMPORTANT_LABEL, IMPORTANT_COLOR)
        dao.upsertGroup(group.copy(importantLabelId = label.id))
        return label.id
    }

    // ---------------------------------------------------------------- pull

    /** Returns the changes it saw on cards this phone already knew, for [tellOthersChanges]. */
    private suspend fun pull(): List<SeenChange> {
        // Deck's ETags come from whole-second timestamps: anything changed in the second
        // we fetched may be invisible to them, so such boards are fetched again next time.
        val unsettledBefore = prefs.getBoolean(KEY_UNSETTLED, false) || refetchAll
        val fetched = client.boards(if (unsettledBefore) null else prefs.getString(KEY_BOARDS_ETAG, null)) ?: return emptyList()
        val fetchedAt = now() / 1000
        val boards = fetched.value.filter { it.deletedAt == 0L && !it.archived }
        var unsettled = false
        val seen = mutableListOf<SeenChange>()

        for (board in boards) {
            val existing = dao.groupByServerId(board.id)
            val isUnsettled = board.lastModified >= fetchedAt - 1
            unsettled = unsettled || isUnsettled
            val blank = GroupEntity(serverId = board.id, title = "", color = "", ownerUid = "", canEdit = false, sharedWith = "", importantLabelId = null, etag = "")
            // A rename or share still queued here wins until it has been pushed.
            val pending = existing != null && dao.pendingForGroup(existing.id) > 0
            val group = (existing ?: blank)
                .let {
                    if (pending) it else it.copy(
                        title = board.title,
                        color = board.color,
                        sharedWith = sharedWith(board),
                        members = Members.encode(members(board)),
                    )
                }
                .copy(
                    ownerUid = board.owner.uid,
                    canEdit = board.permissions.edit,
                    canManage = board.permissions.manage,
                    canShare = board.permissions.share,
                    importantLabelId = existing?.importantLabelId ?: board.labels.firstOrNull { it.title == IMPORTANT_LABEL }?.id,
                )
            val groupId = dao.upsertGroup(group).let { if (existing != null) existing.id else it }
            if (existing == null || existing.etag != board.etag || unsettledBefore) {
                // A board new to this phone is not news, nor is anything on a hidden one.
                pullBoard(groupId, board, seen.takeIf { existing != null && !existing.hidden })
            }
            dao.setGroupEtag(groupId, if (isUnsettled) "" else board.etag)
        }

        val live = boards.map { it.id }.toSet()
        dao.allGroups().filter { it.serverId != null && it.serverId !in live }.forEach { dao.deleteGroup(it.id) }

        prefs.edit()
            .putString(KEY_BOARDS_ETAG, fetched.etag)
            .putBoolean(KEY_UNSETTLED, unsettled)
            .apply()
        return seen
    }

    private suspend fun pullBoard(groupId: Long, board: Board, seen: MutableList<SeenChange>?) {
        val group = dao.group(groupId) ?: return
        val stacks = client.stacks(board.id).filter { it.deletedAt == 0L }
        val seenCards = mutableSetOf<Long>()

        for (stack in stacks) {
            val existingList = dao.listByServerId(stack.id)
            val listId = when {
                existingList == null -> dao.insertList(ListEntity(serverId = stack.id, groupId = groupId, title = stack.title, order = stack.order))
                // A rename, move or delete still queued wins until it has been pushed.
                dao.pendingForList(existingList.id) > 0 -> existingList.id
                else -> existingList.id.also {
                    dao.updateList(existingList.copy(groupId = groupId, serverGroupId = null, title = stack.title, order = stack.order))
                }
            }

            for (card in stack.cards.orEmpty().filter { !it.archived && it.deletedAt == 0L }) {
                seenCards += card.id
                val existing = dao.taskByServerId(card.id)
                // Local edits waiting to be pushed win for now; the push merges them with this card.
                if (existing != null && dao.pendingFor(existing.id) > 0) continue
                val state = card.toState(group.importantLabelId)
                val row = (existing ?: TaskEntity(serverId = card.id, listId = listId, title = "", order = 0))
                    .withState(state)
                    .copy(
                        listId = listId,
                        order = card.order,
                        ownerUid = card.ownerUid,
                        assignees = card.assignedUsers.orEmpty().joinToString(",") { it.participant.uid },
                        serverLastModified = card.lastModified,
                        base = encodeBase(state),
                    )
                val taskId = if (existing == null) dao.insertTask(row) else { dao.updateTask(row); existing.id }
                if (seen != null && existing?.serverLastModified != card.lastModified) {
                    Changes.kind(existing?.toState(), state, session.selfUid.value)?.let { kind ->
                        seen += SeenChange(taskId, card.id, kind, card.title, stack.title, Members.decode(group.members))
                    }
                }
            }
        }

        val liveStacks = stacks.map { it.id }.toSet()
        for (list in dao.listsOf(groupId)) {
            if (dao.pendingForList(list.id) > 0) continue
            if (list.serverId != null && list.serverId !in liveStacks) {
                dao.deleteList(list.id)
                continue
            }
            for (task in dao.tasksOf(list.id)) {
                val gone = task.serverId != null && task.serverId !in seenCards
                if (gone && dao.pendingFor(task.id) == 0) dao.deleteTask(task.id)
            }
        }
    }

    // ---------------------------------------------------------------- others' changes

    /**
     * Keeps the changes someone else made and hands them to [changes]. Deck cards do not say
     * who edited them last, so the activity stream since the previous round settles it.
     */
    private fun tellOthersChanges(seen: List<SeenChange>) {
        val last = prefs.getLong(KEY_LAST_ACTIVITY, -1)
        val actors = try {
            if (last < 0) {
                // First round on this phone: start the stream from here and tell nothing.
                prefs.edit().putLong(KEY_LAST_ACTIVITY, client.latestActivityId() ?: 0).apply()
                return
            }
            latestActors(last)
        } catch (e: DeckException) {
            if (e.status != 404) return
            null // no activity app: we cannot tell who, so every change counts
        } catch (e: java.io.IOException) {
            return
        }
        val me = notifyAs()
        val others = seen.mapNotNull { change ->
            val actor = if (actors == null) null else (actors[change.cardId]?.takeIf { it != me } ?: return@mapNotNull null)
            OthersChange(change.taskId, change.kind, change.title, change.listTitle, actor?.let { change.members[it] ?: it })
        }
        if (others.isNotEmpty()) changes.post(others)
    }

    /** Card id → who changed it last since activity [since], also moving the stored position along. */
    private fun latestActors(since: Long): Map<Long, String> {
        val me = notifyAs()
        val actors = mutableMapOf<Long, String>()
        var from = since
        for (page in 1..MAX_ACTIVITY_PAGES) {
            val activities = client.activities(from, ACTIVITY_PAGE) ?: break
            for (a in activities) {
                // Someone else's change outranks one of mine to the same card.
                if (a.objectType == "deck_card" && (a.user != me || a.objectId !in actors)) actors[a.objectId] = a.user
            }
            from = activities.maxOfOrNull { it.id } ?: break
            if (activities.size < ACTIVITY_PAGE) break
        }
        prefs.edit().putLong(KEY_LAST_ACTIVITY, from).apply()
        return actors
    }

    /** The board's owner and everyone it is shared with one by one (not via a group): who can be assigned. */
    private fun members(board: Board): Map<String, String> =
        (listOf(board.owner) + board.acl.filter { it.type == 0 }.map { it.participant }).associate { it.uid to it.displayname }

    private fun sharedWith(board: Board): String =
        board.acl.map { it.participant.displayname }.filter { it.isNotBlank() }.joinToString(", ")

    private companion object {
        private val handoffJson = Json { ignoreUnknownKeys = true }
        /** Renamed whenever a pull fills new group columns, so the first pull after the update cannot be a 304. */
        const val KEY_BOARDS_ETAG = "boardsEtag4"
        const val KEY_UNSETTLED = "unsettled"
        const val KEY_LAST_ACTIVITY = "lastActivity"
        const val ACTIVITY_PAGE = 100
        const val MAX_ACTIVITY_PAGES = 5
    }
}
