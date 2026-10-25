package app.vinkje.importer

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class PlanTest {
    private fun found(account: String, id: String, title: String, owner: Boolean = true, shared: Boolean = false, known: String = "none") =
        Scan.Found(account, GraphList(id, title, owner, shared, known), open = 2, done = 1)

    @Test
    fun `a new scan keeps the choices already made and skips what is not ours to import`() {
        val before = Plan(
            groups = listOf(PlanGroup("Huis", "anna", listOf("tom"))),
            lists = listOf(
                PlanList("anna", "a", "Boodschappen", group = "Huis"),
                PlanList("anna", "gone", "Oud", group = "Huis"),
                PlanList("tom", "b", "Klussen", group = "Huis"),
            ),
        )
        val after = Scan.merge(before, listOf(
            found("anna", "a", "Boodschappen!"),
            found("anna", "s", "Vakantie", owner = false, shared = true),
            found("anna", "f", "Gemarkeerde e-mail", known = "flaggedEmails"),
        ), scanned = setOf("anna"))

        val byId = after.lists.associateBy { it.graphId }
        assertEquals("Huis", byId.getValue("a").group)
        assertEquals("Boodschappen!", byId.getValue("a").title)
        assertTrue(byId.getValue("s").skip)
        assertTrue(byId.getValue("f").skip)
        assertTrue(byId.getValue("gone").skip)
        assertEquals("niet meer in To Do", byId.getValue("gone").info)
        // Tom was not scanned this time: his list is left alone.
        assertFalse(byId.getValue("b").skip)
        assertEquals(before.groups, after.groups)
    }

    @Test
    fun `validation names every gap before anything is written`() {
        val plan = Plan(
            groups = listOf(PlanGroup("Huis", "tom")),
            lists = listOf(
                PlanList("anna", "a", "Boodschappen", group = "Huis"),
                PlanList("anna", "b", "Werk", group = "Wrok"),
                PlanList("anna", "c", "Taken"),
                PlanList("Anna!", "d", "X", skip = true),
            ),
        )
        val problems = Importer.validate(plan, hasMicrosoft = { it == "anna" }, hasNextcloud = { false })
        assertEquals(3, problems.size, problems.joinToString("\n"))
        assertTrue(problems.any { "Wrok" in it })
        assertTrue(problems.any { "eigenaar 'tom'" in it })
        assertTrue(problems.any { "zonder groep" in it })
    }
}
