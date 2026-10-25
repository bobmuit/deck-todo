package app.vinkje.importer

import kotlinx.serialization.json.Json
import app.vinkje.deck.Board
import app.vinkje.deck.Card
import app.vinkje.deck.DeckClient
import app.vinkje.deck.ImportHandoff
import app.vinkje.domain.DeckConventions
import app.vinkje.domain.TaskBodyCodec
import java.time.Instant
import java.time.ZoneId

/**
 * Copies To Do lists into Deck as the plan says. Safe to run again: what is in [state]
 * is updated rather than duplicated, as long as nobody changed it in Deck meanwhile;
 * what someone deleted in Deck stays deleted.
 */
class Importer(
    private val plan: Plan,
    private val state: ImportState,
    private val save: (ImportState) -> Unit,
    private val source: (account: String) -> TodoSource,
    private val deck: (account: String) -> DeckClient,
    /** Hands the reminders of [account]'s tasks to its app; null when that account has no Nextcloud login here. */
    private val handoff: (account: String) -> DeckClient? = { null },
    /** A trial run: boards get [REHEARSAL_PREFIX], nobody is shared in and no reminders are handed over. */
    private val rehearsal: Boolean = false,
    private val dryRun: Boolean = false,
    private val out: (String) -> Unit = ::println,
    private val now: () -> Instant = Instant::now,
) {
    data class Totals(
        var created: Int = 0,
        var updated: Int = 0,
        var deleted: Int = 0,
        var unchanged: Int = 0,
        /** Changed in To Do and in Deck since the last run: Deck's version was kept. */
        var conflicts: Int = 0,
        /** In the state but no longer on the server: deleted or moved in Deck. */
        var gone: Int = 0,
        var mismatches: Int = 0,
        /** Groups skipped because a board of that title, not made by the import, already exists. */
        var collisions: Int = 0,
    )

    /** [merge]: an existing board of the same title may be added to (the loose-lists board, or a group that asks for it). */
    private data class Target(val owner: String, val title: String, val shareWith: List<String>, val merge: Boolean) {
        val key get() = "$owner/$title"
    }

    private val zone = ZoneId.of(plan.zone)
    private val uids = mutableMapOf<String, String>()
    private val totals = Totals()
    private val collided = mutableSetOf<String>()
    private val reminders = linkedMapOf<String, MutableList<ImportHandoff.Reminder>>()

    private fun uid(account: String) = uids.getOrPut(account) { deck(account).currentUser().uid }

    fun run(): Totals {
        val lists = plan.lists.filter { !it.skip }
        val targets = lists.associateWith(::targetOf)
        for (target in targets.values.distinct()) {
            val board = ensureBoard(target)
            if (target.key in collided) continue
            if (board == null && target.key in state.boards) {
                out("Groep '${target.title}' is in Deck verwijderd; de lijsten erin worden overgeslagen.")
                continue
            }
            if (board != null) share(target, board)
            for (list in lists.filter { targets[it] == target }) importList(list, target, board)
        }
        handOverReminders()
        return totals
    }

    // ------------------------------------------------------------- boards

    private fun targetOf(list: PlanList): Target {
        val prefix = if (rehearsal) REHEARSAL_PREFIX else ""
        val group = list.group ?: return Target(list.account, prefix + DeckConventions.LOOSE_BOARD, emptyList(), merge = true)
        val g = plan.groups.first { it.title == group }
        return Target(g.owner, prefix + g.title, g.shareWith, merge = g.mergeExisting)
    }

    /**
     * The board for [target]: from an earlier run, an existing one of the same name, or a new one. Null when it was deleted,
     * in a dry run, or when an existing board of that name may not be added to (then [target] is in [collided]).
     */
    private fun ensureBoard(target: Target): Board? {
        val client = deck(target.owner)
        state.boards[target.key]?.let { id ->
            return runCatching { client.board(id) }.getOrNull()?.takeIf { it.deletedAt == 0L && !it.archived }
        }
        val self = uid(target.owner)
        val existing = client.boards()!!.value.firstOrNull { it.title == target.title && it.owner.uid == self && it.deletedAt == 0L && !it.archived }
        if (existing != null && !target.merge) {
            out("Groep '${target.title}': er bestaat al een board met die naam dat niet van de import is; overgeslagen. " +
                "Hernoem de groep in plan.json, of zet \"mergeExisting\": true om de lijsten aan dat board toe te voegen.")
            totals.collisions++
            collided += target.key
            return null
        }
        if (existing != null) {
            out("Groep '${target.title}': bestaat al, wordt aangevuld.")
        } else {
            out("Groep '${target.title}': nieuw.")
            if (dryRun) return null
        }
        val id = existing?.id ?: client.createBoard(target.title, DeckConventions.GROUP_COLORS[state.boards.size % DeckConventions.GROUP_COLORS.size]).id
        state.boards[target.key] = id
        save(state)
        return client.board(id)
    }

    private fun share(target: Target, board: Board) {
        if (target.shareWith.isEmpty()) return
        if (rehearsal) {
            out("  (proef: niet gedeeld met ${target.shareWith.joinToString()})")
            return
        }
        val client = deck(target.owner)
        for (who in target.shareWith) {
            val uid = runCatching { uid(who) }.getOrNull() ?: findUser(client, who)
            if (uid == board.owner.uid || board.acl.any { it.participant.uid == uid }) continue
            out("  delen met $who")
            if (!dryRun) client.share(board.id, uid, edit = true, share = true, manage = true)
        }
    }

    private fun findUser(client: DeckClient, name: String): String {
        val hits = client.findUsers(name).filter { it.displayname.equals(name, ignoreCase = true) || it.uid == name }
        return hits.singleOrNull()?.uid
            ?: throw IllegalArgumentException("'$name' in shareWith: ${if (hits.isEmpty()) "geen" else "meer dan één"} Nextcloud-gebruiker met die naam")
    }

    // ------------------------------------------------------------- lists

    private fun importList(list: PlanList, target: Target, board: Board?) {
        val client = deck(target.owner)
        val tasks = source(list.account).tasks(list.graphId)
        val ref = state.lists[list.graphId]
        if (ref != null && board != null && ref.boardId != board.id) {
            out("Lijst '${list.title}' staat al in een andere groep; verplaats hem zo nodig in de app.")
        }

        val stack = when {
            ref != null -> client.stacks(ref.boardId).firstOrNull { it.id == ref.stackId }
                ?: run { out("Lijst '${list.title}' is in Deck verwijderd; overgeslagen."); return }
            board == null -> null // dry run, new board
            dryRun -> null
            else -> client.createStack(board.id, list.title, plan.lists.indexOf(list)).also {
                state.lists[list.graphId] = ListRef(board.id, it.id)
                save(state)
            }
        }
        out("Lijst '${list.title}'${if (stack == null) " (nieuw)" else ""}: ${tasks.size} taken")
        val boardId = ref?.boardId ?: board?.id
        val cards = stack?.cards.orEmpty().associateBy { it.id }
        val touched = mutableSetOf<String>()
        val importantLabel by lazy {
            val b = client.board(boardId!!)
            b.labels.firstOrNull { it.title == DeckConventions.IMPORTANT_LABEL }?.id
                ?: client.createLabel(b.id, DeckConventions.IMPORTANT_LABEL, DeckConventions.IMPORTANT_COLOR).id
        }

        // Newest first, as To Do adds tasks at the top; ticked ones after the open ones.
        val sorted = tasks.sortedWith(compareBy<GraphTask> { it.status == "completed" }.thenByDescending { it.createdDateTime ?: "" })
        for ((order, task) in sorted.withIndex()) {
            val content = Convert.card(task, zone)
            content.warnings.forEach { out("  '${content.title}': $it") }
            val prev = state.tasks[task.id]
            val cardId: Long = when {
                prev == null -> {
                    if (content.done != null && !plan.importCompleted) continue
                    totals.created++
                    if (stack == null || boardId == null) continue
                    create(client, boardId, stack.id, order, content) { importantLabel }.also { id ->
                        state.tasks[task.id] = TaskRef(boardId, stack.id, id, task.lastModifiedDateTime)
                        save(state)
                    }
                }
                else -> {
                    val card = cards[prev.cardId]
                    when {
                        card == null -> { totals.gone++; continue }
                        task.lastModifiedDateTime == prev.graphModified -> { totals.unchanged++; continue }
                        prev.deckModified != 0L && card.lastModified != prev.deckModified -> {
                            totals.conflicts++
                            out("  '${card.title}': ook in Deck gewijzigd; de versie in Deck blijft")
                            continue
                        }
                    }
                    totals.updated++
                    if (!dryRun) {
                        update(client, boardId!!, card!!, content) { importantLabel }
                        state.tasks[task.id] = prev.copy(graphModified = task.lastModifiedDateTime)
                        save(state)
                    }
                    card!!.id
                }
            }
            touched += task.id
            content.reminder?.takeIf { it > now() }?.let { reminders.getOrPut(list.account) { mutableListOf() } += ImportHandoff.Reminder(cardId, it.epochSecond) }
        }

        if (stack == null) return
        deleteRemoved(client, stack.id, cards, tasks.map { it.id }.toSet())
        if (dryRun) return

        // Remember what the cards look like now, to notice later edits in Deck.
        val fresh = client.stacks(boardId!!).first { it.id == stack.id }.cards.orEmpty()
        val byId = fresh.associateBy { it.id }
        for (id in touched) {
            val r = state.tasks[id] ?: continue
            byId[r.cardId]?.let { state.tasks[id] = r.copy(deckModified = it.lastModified) }
        }
        save(state)
        check(list, tasks, fresh)
    }

    private fun create(client: DeckClient, boardId: Long, stackId: Long, order: Int, c: CardContent, label: () -> Long): Long {
        var card = client.createCard(boardId, stackId, c.title, order, c.description, c.due)
        if (c.done != null) {
            card = client.updateCard(boardId, stackId, card.id, c.title, card.ownerUid ?: "", order, c.description, c.due, c.done)
        }
        if (c.important) client.assignLabel(boardId, stackId, card.id, label())
        return card.id
    }

    private fun update(client: DeckClient, boardId: Long, card: Card, c: CardContent, label: () -> Long) {
        client.updateCard(boardId, card.stackId, card.id, c.title, card.ownerUid ?: "", card.order, c.description, c.due, c.done)
        val starred = card.labels.orEmpty().firstOrNull { it.title == DeckConventions.IMPORTANT_LABEL }
        if (c.important && starred == null) client.assignLabel(boardId, card.stackId, card.id, label())
        if (!c.important && starred != null) client.removeLabel(boardId, card.stackId, card.id, starred.id)
    }

    /** Tasks deleted in To Do since the last run go from Deck too, unless someone changed them there. */
    private fun deleteRemoved(client: DeckClient, stackId: Long, cards: Map<Long, Card>, inTodo: Set<String>) {
        val removed = state.tasks.filter { (id, r) -> r.stackId == stackId && id !in inTodo }
        for ((id, r) in removed) {
            val card = cards[r.cardId]
            if (card != null && (r.deckModified == 0L || card.lastModified == r.deckModified)) {
                totals.deleted++
                if (dryRun) continue
                client.deleteCard(r.boardId, stackId, card.id)
            }
            if (!dryRun) state.tasks.remove(id)
        }
        if (!dryRun) save(state)
    }

    /** The plan's check: as many cards and steps in Deck as tasks and steps in To Do. */
    private fun check(list: PlanList, tasks: List<GraphTask>, cards: List<Card>) {
        val mine = tasks.filter { state.tasks[it.id] != null }
        val ids = mine.mapNotNull { state.tasks[it.id]?.cardId }.toSet()
        val inDeck = cards.filter { it.id in ids }
        val todoSteps = mine.sumOf { t -> t.checklistItems.orEmpty().count { it.displayName.isNotBlank() } }
        val deckSteps = inDeck.sumOf { TaskBodyCodec.parse(it.description).steps.size }
        val ok = mine.size == inDeck.size && todoSteps == deckSteps
        if (!ok) totals.mismatches++
        out("  ${if (ok) "✓" else "≠"} To Do ${mine.size} taken / $todoSteps stappen, Deck ${inDeck.size} kaarten / $deckSteps stappen")
    }

    // ------------------------------------------------------------- reminders

    private fun handOverReminders() {
        for ((account, list) in reminders) {
            if (rehearsal || dryRun) {
                out("${list.size} herinnering(en) van $account${if (rehearsal) " (proef: niet overgedragen)" else ""}")
                continue
            }
            val client = handoff(account)
            if (client == null) {
                out("${list.size} herinnering(en) van $account niet overgedragen: geen Nextcloud-login voor $account.")
                continue
            }
            val uid = uid(account)
            val json = Json { ignoreUnknownKeys = true }
            val earlier = client.readFile(uid, ImportHandoff.PATH)?.let { runCatching { json.decodeFromString<ImportHandoff>(it) }.getOrNull() }
            val merged = (earlier?.reminders.orEmpty().filter { r -> list.none { it.card == r.card } } + list)
            client.writeFile(uid, ImportHandoff.PATH, json.encodeToString(ImportHandoff(merged)))
            out("${list.size} herinnering(en) klaargezet voor de app van $account.")
        }
    }

    companion object {
        const val REHEARSAL_PREFIX = "zz-proef "

        /** Problems in the plan that would stop a run halfway, as messages. */
        fun validate(plan: Plan, hasMicrosoft: (String) -> Boolean, hasNextcloud: (String) -> Boolean): List<String> = buildList {
            val names = Regex("[a-z0-9-]+")
            val groups = plan.groups.map { it.title }
            plan.groups.groupBy { it.title }.filter { it.value.size > 1 }.keys.forEach { add("groep '$it' staat er twee keer in") }
            for (g in plan.groups) {
                if (!hasNextcloud(g.owner)) add("groep '${g.title}': geen Nextcloud-login voor eigenaar '${g.owner}'")
            }
            for (l in plan.lists.filter { !it.skip }) {
                if (!names.matches(l.account)) add("lijst '${l.title}': accountnaam '${l.account}' mag alleen a-z, 0-9 en - bevatten")
                if (!hasMicrosoft(l.account)) add("lijst '${l.title}': geen Microsoft-login voor '${l.account}'")
                when (val g = l.group) {
                    null -> if (!hasNextcloud(l.account)) add("lijst '${l.title}' (zonder groep): geen Nextcloud-login voor '${l.account}'")
                    !in groups -> add("lijst '${l.title}': groep '$g' staat niet onder groups")
                }
            }
        }
    }
}

/** Folds what To Do has now into the plan, keeping the choices already made in it. */
object Scan {
    data class Found(val account: String, val list: GraphList, val open: Int, val done: Int)

    fun merge(plan: Plan?, found: List<Found>, scanned: Set<String>): Plan {
        val old = plan ?: Plan()
        val known = old.lists.associateBy { it.account to it.graphId }
        val lists = found.map { f ->
            val info = buildList {
                add("${f.open} open, ${f.done} voltooid")
                if (f.list.isShared) add(if (f.list.isOwner) "gedeeld door jou" else "gedeeld met jou: importeer bij de eigenaar")
                if (f.list.wellknownListName == "flaggedEmails") add("gemarkeerde e-mail uit Outlook")
                if (f.list.wellknownListName == "defaultList") add("de standaardlijst 'Taken'")
            }.joinToString("; ")
            known[f.account to f.list.id]?.copy(title = f.list.displayName, info = info)
                ?: PlanList(
                    account = f.account, graphId = f.list.id, title = f.list.displayName, info = info,
                    skip = !f.list.isOwner || f.list.wellknownListName == "flaggedEmails",
                )
        }
        // Lists of accounts not scanned this time stay; lists gone from To Do stay too, marked, so their choices are not lost.
        val foundKeys = found.map { it.account to it.list.id }.toSet()
        val rest = old.lists.filter { (it.account to it.graphId) !in foundKeys }
            .map { if (it.account in scanned) it.copy(skip = true, info = "niet meer in To Do") else it }
        return old.copy(lists = lists + rest)
    }
}
