package app.vinkje.sync

import app.vinkje.domain.ChangeKind

/** A change the pull saw on a card; whether someone else made it is settled afterwards. */
data class SeenChange(val taskId: Long, val cardId: Long, val kind: ChangeKind, val title: String, val listTitle: String, val members: Map<String, String>)

/** A change made by someone else, ready to be shown. [actor] is null when the server cannot say who. */
data class OthersChange(val taskId: Long, val kind: ChangeKind, val title: String, val listTitle: String, val actor: String?)

fun interface ChangeSink {
    fun post(changes: List<OthersChange>)
}
