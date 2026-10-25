package app.vinkje.domain

/**
 * Three-way merge of a task body, for when both phones edited the same card while
 * one of them was offline. [base] is the version both sides last agreed on.
 *
 * Steps merge item by item, so two people ticking off different steps never lose
 * each other's work. The note and each footer field take the side that changed;
 * if both changed, [preferLocal] decides.
 */
object Merge {
    fun body(base: TaskBody, local: TaskBody, remote: TaskBody, preferLocal: Boolean = true) = TaskBody(
        note = value(base.note, local.note, remote.note, preferLocal),
        steps = steps(base.steps, local.steps, remote.steps),
        meta = meta(base.meta, local.meta, remote.meta, preferLocal),
    )

    fun <T> value(base: T, local: T, remote: T, preferLocal: Boolean = true): T = when {
        local == remote -> local
        local == base -> remote
        remote == base -> local
        else -> if (preferLocal) local else remote
    }

    fun steps(base: List<Step>, local: List<Step>, remote: List<Step>): List<Step> {
        val b = keyed(base).toMap()
        val l = keyed(local)
        val lMap = l.toMap()
        val r = keyed(remote)
        val rKeys = r.map { it.first }.toSet()

        val result = mutableListOf<Pair<Key, Step>>()
        for ((key, remoteStep) in r) {
            val baseStep = b[key]
            val localStep = lMap[key]
            if (baseStep != null && localStep == null) continue // deleted here
            val done = if (localStep != null && (baseStep == null || localStep.done != baseStep.done)) {
                localStep.done
            } else {
                remoteStep.done
            }
            result += key to remoteStep.copy(done = done)
        }

        // Steps added here: place each after the step that preceded it locally.
        for ((index, entry) in l.withIndex()) {
            val (key, step) = entry
            if (key in b || key in rKeys) continue
            val predecessor = l.subList(0, index).lastOrNull { (k, _) -> result.any { it.first == k } }?.first
            val at = if (predecessor == null) 0 else result.indexOfFirst { it.first == predecessor } + 1
            result.add(at, key to step)
        }
        return result.map { it.second }
    }

    /** A set merges per member: additions and removals from either side both count. */
    fun <T> set(base: Set<T>, local: Set<T>, remote: Set<T>): Set<T> = remote + (local - base) - (base - local)

    private fun meta(base: Map<String, String>, local: Map<String, String>, remote: Map<String, String>, preferLocal: Boolean): Map<String, String> {
        val out = linkedMapOf<String, String>()
        for (key in (remote.keys + local.keys)) {
            value(base[key], local[key], remote[key], preferLocal)?.let { out[key] = it }
        }
        return out
    }

    /** Steps have no ids, so they are identified by text plus occurrence number for duplicates. */
    private data class Key(val text: String, val nth: Int)

    private fun keyed(steps: List<Step>): List<Pair<Key, Step>> {
        val seen = mutableMapOf<String, Int>()
        return steps.map { s ->
            val n = seen.merge(s.text, 1, Int::plus)!!
            Key(s.text, n) to s
        }
    }
}

/**
 * Everything about a task that both phones can edit. Timestamps are kept to whole
 * seconds because that is what Deck stores; comparing finer values would make
 * every round trip look like a change.
 */
data class TaskState(
    val title: String,
    val body: TaskBody = TaskBody(),
    val dueEpochSecond: Long? = null,
    val doneEpochSecond: Long? = null,
    val important: Boolean = false,
    /** Uids of the people the card is assigned to. */
    val assignees: Set<String> = emptySet(),
) {
    val done: Boolean get() = doneEpochSecond != null
}

fun Merge.task(base: TaskState, local: TaskState, remote: TaskState, preferLocal: Boolean = true) = TaskState(
    title = value(base.title, local.title, remote.title, preferLocal),
    body = body(base.body, local.body, remote.body, preferLocal),
    dueEpochSecond = value(base.dueEpochSecond, local.dueEpochSecond, remote.dueEpochSecond, preferLocal),
    // Done is merged as a yes/no: if both ticked the task, keep the earlier time.
    doneEpochSecond = when (value(base.done, local.done, remote.done, preferLocal)) {
        false -> null
        true -> listOfNotNull(local.doneEpochSecond, remote.doneEpochSecond).min()
    },
    important = value(base.important, local.important, remote.important, preferLocal),
    assignees = set(base.assignees, local.assignees, remote.assignees),
)
