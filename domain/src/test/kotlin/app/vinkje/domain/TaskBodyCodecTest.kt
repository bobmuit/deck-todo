package app.vinkje.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TaskBodyCodecTest {
    private val full = TaskBody(
        note = "Bij de Jumbo halen.\nNiet de light-versie.",
        steps = listOf(Step("melk"), Step("brood", done = true)),
        meta = mapOf("Herhaling" to "FREQ=WEEKLY;BYDAY=MO"),
    )

    @Test
    fun `formats note, steps and footer as separate Markdown blocks`() {
        assertEquals(
            """
            Bij de Jumbo halen.
            Niet de light-versie.

            - [ ] melk
            - [x] brood

            ---
            Herhaling: FREQ=WEEKLY;BYDAY=MO
            """.trimIndent(),
            TaskBodyCodec.format(full),
        )
    }

    @Test
    fun `round-trips`() {
        assertEquals(full, TaskBodyCodec.parse(TaskBodyCodec.format(full)))
        listOf(TaskBody(), TaskBody(note = "alleen tekst"), TaskBody(steps = listOf(Step("a"))))
            .forEach { assertEquals(it, TaskBodyCodec.parse(TaskBodyCodec.format(it))) }
    }

    @Test
    fun `empty and null descriptions are an empty body`() {
        assertEquals(TaskBody(), TaskBodyCodec.parse(null))
        assertEquals(TaskBody(), TaskBodyCodec.parse("  \n "))
    }

    @Test
    fun `accepts the checkbox variants Deck web and other editors write`() {
        val body = TaskBodyCodec.parse("* [X] een\r\n+ [ ] twee\n  - [x] ingesprongen")
        assertEquals(listOf(Step("een", true), Step("twee"), Step("ingesprongen", true)), body.steps)
        assertEquals("", body.note)
    }

    @Test
    fun `a note ending in a horizontal rule is not mistaken for a footer`() {
        val text = "Lijstje\n\n---\nNotitie: dit is gewone tekst"
        val body = TaskBodyCodec.parse(text)
        assertEquals(text, body.note)
        assertEquals(emptyMap(), body.meta)
    }

    @Test
    fun `only the last horizontal rule starts the footer`() {
        val body = TaskBodyCodec.parse("boven\n\n---\n\nonder\n\n---\nHerhaling: FREQ=DAILY")
        assertEquals("boven\n\n---\n\nonder", body.note)
        assertEquals(Recurrence(Recurrence.Freq.DAILY), body.recurrence)
    }

    @Test
    fun `withRecurrence adds and removes the footer field`() {
        val withRule = TaskBody(note = "x").withRecurrence(Recurrence.workdays())
        assertEquals("FREQ=WEEKLY;BYDAY=MO,TU,WE,TH,FR", withRule.meta["Herhaling"])
        assertNull(withRule.withRecurrence(null).meta["Herhaling"])
        assertEquals("x", TaskBodyCodec.format(withRule.withRecurrence(null)))
    }
}
