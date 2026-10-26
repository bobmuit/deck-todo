package app.vinkje.domain

/**
 * The order of the home screen as "Volgorde aanpassen" edits it: the pinned lists, then
 * lists outside any group and groups mixed as one level, each group with its own lists.
 * Ids are the phone's row ids. A pinned list also keeps its own place.
 */
data class HomeLayout(val pinned: List<Long>, val top: List<Top>) {
    sealed interface Top {
        data class Loose(val listId: Long) : Top
        /** [canReorder]: Deck lets only those who manage a board reorder its stacks. */
        data class Group(val groupId: Long, val lists: List<Long>, val canReorder: Boolean) : Top
    }

    /** One row in arrange mode, top to bottom; [key] is also its LazyColumn key. */
    sealed interface Row {
        val key: String

        data class Pinned(val listId: Long) : Row { override val key get() = "p$listId" }
        data class Loose(val listId: Long) : Row { override val key get() = "l$listId" }
        data class Group(val groupId: Long) : Row { override val key get() = "g$groupId" }
        data class GroupList(val groupId: Long, val listId: Long, val canReorder: Boolean) : Row { override val key get() = "l$listId" }
    }

    /** The rows, leaving out the lists of [folded] (a group being dragged moves as one row). */
    fun rows(folded: Long? = null): List<Row> = buildList {
        pinned.forEach { add(Row.Pinned(it)) }
        for (item in top) when (item) {
            is Top.Loose -> add(Row.Loose(item.listId))
            is Top.Group -> {
                add(Row.Group(item.groupId))
                if (item.groupId != folded) item.lists.forEach { add(Row.GroupList(item.groupId, it, item.canReorder)) }
            }
        }
    }

    /**
     * The row with key [from] moved to where [to] is, or null when that crosses a level: pinned
     * lists among themselves, a group's lists within the group, and lists outside a group and
     * groups among each other. A top-level row passes a whole group at once, never landing in
     * the middle of it: going down when it reaches the group's last row, going up when it
     * reaches the heading. Then it lands where it is dragged, so it does not jump back.
     */
    fun move(from: String, to: String): HomeLayout? {
        val rows = rows()
        val a = rows.firstOrNull { it.key == from } ?: return null
        val b = rows.firstOrNull { it.key == to } ?: return null
        return when (a) {
            is Row.Pinned -> if (b is Row.Pinned) copy(pinned = pinned.moved(pinned.indexOf(a.listId), pinned.indexOf(b.listId))) else null
            is Row.GroupList -> {
                if (b !is Row.GroupList || b.groupId != a.groupId || !a.canReorder) return null
                copy(top = top.map { item ->
                    if (item is Top.Group && item.groupId == a.groupId) item.copy(lists = item.lists.moved(item.lists.indexOf(a.listId), item.lists.indexOf(b.listId))) else item
                })
            }
            is Row.Loose, is Row.Group -> {
                val source = when (a) {
                    is Row.Loose -> topIndex { it is Top.Loose && it.listId == a.listId }
                    else -> topIndex { it is Top.Group && it.groupId == (a as Row.Group).groupId }
                }
                val target = when (b) {
                    is Row.Pinned -> return null
                    is Row.Loose -> topIndex { it is Top.Loose && it.listId == b.listId }
                    is Row.Group, is Row.GroupList -> {
                        val groupId = if (b is Row.Group) b.groupId else (b as Row.GroupList).groupId
                        val index = topIndex { it is Top.Group && it.groupId == groupId }
                        val lists = (top[index] as Top.Group).lists
                        val reached = if (source < index) b.key == (lists.lastOrNull()?.let { "l$it" } ?: "g$groupId") else b is Row.Group
                        if (!reached) return null
                        index
                    }
                }
                if (source == target) null else copy(top = top.moved(source, target))
            }
        }
    }

    fun isPinned(listId: Long) = listId in pinned

    /** Pinned at the bottom of the pinned lists, or taken off them. */
    fun pin(listId: Long, pinned: Boolean) = copy(pinned = if (pinned) (this.pinned - listId) + listId else this.pinned - listId)

    private fun topIndex(match: (Top) -> Boolean) = top.indexOfFirst(match)

    companion object {
        /**
         * Rows with a place first, by that place; the rest after them in the order given. That
         * order is the one from before any arranging, so nothing moves until the user does.
         */
        fun <T> byPosition(items: List<T>, position: (T) -> Int?): List<T> = items.sortedWith(compareBy(nullsLast()) { position(it) })
    }
}

fun <T> List<T>.moved(from: Int, to: Int): List<T> = toMutableList().apply { add(to, removeAt(from)) }
