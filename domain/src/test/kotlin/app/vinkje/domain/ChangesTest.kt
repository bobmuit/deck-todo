package app.vinkje.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ChangesTest {
    private val open = TaskState("Melk")

    @Test
    fun `new, ticked and unticked tasks`() {
        assertEquals(ChangeKind.ADDED, Changes.kind(null, open, "tom"))
        assertEquals(ChangeKind.COMPLETED, Changes.kind(open, open.copy(doneEpochSecond = 1), "tom"))
        assertEquals(ChangeKind.REOPENED, Changes.kind(open.copy(doneEpochSecond = 1), open, "tom"))
    }

    @Test
    fun `only an assignment to me is news`() {
        assertEquals(ChangeKind.ASSIGNED_TO_ME, Changes.kind(open, open.copy(assignees = setOf("tom")), "tom"))
        assertNull(Changes.kind(open, open.copy(assignees = setOf("anna")), "tom"))
    }

    @Test
    fun `other edits and no edits`() {
        assertEquals(ChangeKind.EDITED, Changes.kind(open, open.copy(title = "Halfvolle melk"), "tom"))
        assertEquals(ChangeKind.EDITED, Changes.kind(open, open.copy(body = TaskBody(steps = listOf(Step("1 liter")))), "tom"))
        assertNull(Changes.kind(open, open, "tom"))
    }
}
