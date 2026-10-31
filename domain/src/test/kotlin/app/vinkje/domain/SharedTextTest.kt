package app.vinkje.domain

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SharedTextTest {
    @Test
    fun `subject becomes the title and the link the note, as Chrome shares a page`() {
        assertEquals(
            SharedTask("Leuk recept", "https://example.org/recept"),
            SharedText.toTask("Leuk recept", "https://example.org/recept"),
        )
    }

    @Test
    fun `without a subject the first line is the title and the rest the note`() {
        assertEquals(
            SharedTask("Boodschappen", "melk\nbrood"),
            SharedText.toTask(null, "\n  Boodschappen  \nmelk\nbrood\n"),
        )
    }

    @Test
    fun `a bare link is the title`() {
        assertEquals(SharedTask("https://example.org", ""), SharedText.toTask("", "https://example.org"))
    }

    @Test
    fun `text equal to the subject is not repeated in the note`() {
        assertEquals(SharedTask("Bellen", ""), SharedText.toTask("Bellen", "Bellen"))
    }

    @Test
    fun `nothing to add`() {
        assertNull(SharedText.toTask(null, null))
        assertNull(SharedText.toTask("  ", " \n\t "))
    }

    @Test
    fun `line breaks in a subject become spaces`() {
        assertEquals("Twee regels", SharedText.toTask("Twee\nregels", null)!!.title)
    }

    @Test
    fun `a title too long for Deck is cut and kept whole in the note`() {
        val long = List(80) { "woord" }.joinToString(" ")
        val task = SharedText.toTask(null, "$long\nhttps://example.org")!!
        assertTrue(task.title.length <= SharedText.MAX_TITLE)
        assertTrue(task.title.endsWith("woord…"))
        assertEquals("$long\n\nhttps://example.org", task.note)
    }
}
