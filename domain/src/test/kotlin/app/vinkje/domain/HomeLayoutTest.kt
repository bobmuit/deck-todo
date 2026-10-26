package app.vinkje.domain

import app.vinkje.domain.HomeLayout.Top
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class HomeLayoutTest {
    // Inbox (1) loose; group Huis (10) with Boodschappen (2) and Klussen (3); Werk (20), not managed, with 4 and 5.
    private val layout = HomeLayout(
        pinned = listOf(1, 2),
        top = listOf(
            Top.Loose(1),
            Top.Group(10, listOf(2, 3), canReorder = true),
            Top.Group(20, listOf(4, 5), canReorder = false),
        ),
    )

    private fun HomeLayout.keys(folded: Long? = null) = rows(folded).map { it.key }

    @Test
    fun `a pinned list is on top and in its own place`() {
        assertEquals(listOf("p1", "p2", "l1", "g10", "l2", "l3", "g20", "l4", "l5"), layout.keys())
    }

    @Test
    fun `a dragged group leaves its lists out`() {
        assertEquals(listOf("p1", "p2", "l1", "g10", "g20", "l4", "l5"), layout.keys(folded = 10))
    }

    @Test
    fun `pinned lists move among themselves only`() {
        assertEquals(listOf(2L, 1L), layout.move("p1", "p2")!!.pinned)
        assertNull(layout.move("p1", "l1"))
        assertNull(layout.move("l1", "p1"))
    }

    @Test
    fun `a group's lists move within that group`() {
        val moved = layout.move("l3", "l2")!!
        assertEquals(listOf(3L, 2L), (moved.top[1] as Top.Group).lists)
        assertNull(layout.move("l3", "l4"))
        assertNull(layout.move("l3", "g20"))
    }

    @Test
    fun `lists in a group that may not be reordered stay put`() {
        assertNull(layout.move("l4", "l5"))
    }

    @Test
    fun `a loose list moves down past a group when it reaches the group's last list`() {
        assertNull(layout.move("l1", "g10"))
        assertNull(layout.move("l1", "l2"))
        val moved = layout.move("l1", "l3")!!
        assertEquals(listOf("p1", "p2", "g10", "l2", "l3", "l1", "g20", "l4", "l5"), moved.keys())
        // Now just below Klussen, it does not jump back over Huis.
        assertNull(moved.move("l1", "l3"))
        assertNull(moved.move("l1", "l2"))
    }

    @Test
    fun `a group moves with its lists`() {
        val moved = layout.move("g20", "g10")!!
        assertEquals(listOf("p1", "p2", "l1", "g20", "l4", "l5", "g10", "l2", "l3"), moved.keys())
    }

    @Test
    fun `a top-level row moves up past a group when it reaches the heading`() {
        // Dragging Werk up, it first meets Klussen and Boodschappen, the lists of Huis.
        assertNull(layout.move("g20", "l3"))
        assertNull(layout.move("g20", "l2"))
        val moved = layout.move("g20", "g10")!!
        assertEquals(listOf("p1", "p2", "l1", "g20", "l4", "l5", "g10", "l2", "l3"), moved.keys())
        // Now just above Huis, it does not jump back down.
        assertNull(moved.move("g20", "g10"))
    }

    @Test
    fun `a group without lists is passed at its heading`() {
        val empty = HomeLayout(emptyList(), listOf(Top.Loose(1), Top.Group(10, emptyList(), canReorder = true)))
        assertEquals(listOf("g10", "l1"), empty.move("l1", "g10")!!.keys())
    }

    @Test
    fun `pinning adds at the bottom, unpinning removes`() {
        assertEquals(listOf(1L, 2L, 3L), layout.pin(3, true).pinned)
        assertEquals(listOf(2L, 1L), layout.pin(1, true).pinned)
        assertEquals(listOf(2L), layout.pin(1, false).pinned)
    }

    @Test
    fun `rows without a place keep their order after those with one`() {
        val rows = listOf("Inbox" to null, "Huis" to 1, "Werk" to null, "Boodschappen" to 0)
        assertEquals(listOf("Boodschappen", "Huis", "Inbox", "Werk"), HomeLayout.byPosition(rows) { it.second }.map { it.first })
    }
}
