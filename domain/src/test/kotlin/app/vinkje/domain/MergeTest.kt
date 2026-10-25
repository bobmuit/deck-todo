package app.vinkje.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals

class MergeTest {
    private fun steps(vararg s: String) = s.map { if (it.startsWith("x:")) Step(it.drop(2), true) else Step(it) }

    @Test
    fun `both sides tick off different steps`() {
        val base = steps("melk", "brood", "kaas")
        val local = steps("x:melk", "brood", "kaas")
        val remote = steps("melk", "brood", "x:kaas")
        assertEquals(steps("x:melk", "brood", "x:kaas"), Merge.steps(base, local, remote))
    }

    @Test
    fun `additions on both sides are kept in place`() {
        val base = steps("a", "c")
        val local = steps("a", "b", "c")
        val remote = steps("a", "c", "d")
        assertEquals(steps("a", "b", "c", "d"), Merge.steps(base, local, remote))
    }

    @Test
    fun `a local addition at the top stays at the top`() {
        assertEquals(steps("nieuw", "a"), Merge.steps(steps("a"), steps("nieuw", "a"), steps("a")))
    }

    @Test
    fun `deletions on either side win over an untouched step`() {
        val base = steps("a", "b", "c")
        assertEquals(steps("a", "c"), Merge.steps(base, steps("a", "c"), base))
        assertEquals(steps("b", "c"), Merge.steps(base, base, steps("b", "c")))
    }

    @Test
    fun `remote reordering is respected`() {
        val base = steps("a", "b", "c")
        assertEquals(steps("c", "x:a", "b"), Merge.steps(base, steps("x:a", "b", "c"), steps("c", "a", "b")))
    }

    @Test
    fun `duplicate step texts are tracked separately`() {
        val base = steps("eieren", "eieren")
        val local = steps("eieren", "x:eieren")
        assertEquals(local, Merge.steps(base, local, base))
    }

    @Test
    fun `note takes the changed side, local wins a real conflict`() {
        assertEquals("nieuw", Merge.value("oud", "oud", "nieuw"))
        assertEquals("lokaal", Merge.value("oud", "lokaal", "server"))
        assertEquals("server", Merge.value("oud", "lokaal", "server", preferLocal = false))
    }

    @Test
    fun `footer fields merge per key, including removal`() {
        val base = TaskBody(meta = mapOf("Herhaling" to "FREQ=DAILY"))
        val local = TaskBody(meta = emptyMap())
        val remote = TaskBody(note = "nieuwe notitie", meta = base.meta)
        assertEquals(TaskBody(note = "nieuwe notitie"), Merge.body(base, local, remote))
    }
}

class TaskMergeTest {
    private val base = TaskState("melk", TaskBody(steps = listOf(Step("halfvol"), Step("vol"))))

    @Test
    fun `one ticks the task, the other renames it`() {
        val local = base.copy(doneEpochSecond = 100)
        val remote = base.copy(title = "melk (2 pakken)")
        assertEquals(TaskState("melk (2 pakken)", base.body, doneEpochSecond = 100), Merge.task(base, local, remote))
    }

    @Test
    fun `both tick the task, the earliest time is kept`() {
        val merged = Merge.task(base, base.copy(doneEpochSecond = 200), base.copy(doneEpochSecond = 150))
        assertEquals(150L, merged.doneEpochSecond)
    }

    @Test
    fun `untick on one side wins over an unrelated change on the other`() {
        val doneBase = base.copy(doneEpochSecond = 100)
        val merged = Merge.task(doneBase, doneBase.copy(doneEpochSecond = null), doneBase.copy(important = true))
        assertEquals(base.copy(important = true), merged)
    }

    @Test
    fun `steps ticked on both phones are all kept`() {
        val local = base.copy(body = TaskBody(steps = listOf(Step("halfvol", true), Step("vol"))))
        val remote = base.copy(body = TaskBody(steps = listOf(Step("halfvol"), Step("vol", true))))
        assertEquals(listOf(Step("halfvol", true), Step("vol", true)), Merge.task(base, local, remote).body.steps)
    }

    @Test
    fun `assigning on one phone and unassigning on the other both count`() {
        val withTom = base.copy(assignees = setOf("tom"))
        val merged = Merge.task(withTom, withTom.copy(assignees = emptySet()), withTom.copy(assignees = setOf("tom", "anna")))
        assertEquals(setOf("anna"), merged.assignees)
    }
}
