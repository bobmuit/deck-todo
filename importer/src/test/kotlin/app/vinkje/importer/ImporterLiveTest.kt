package app.vinkje.importer

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import app.vinkje.deck.DeckClient
import app.vinkje.deck.ImportHandoff
import app.vinkje.domain.DeckConventions
import app.vinkje.domain.TaskBodyCodec
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A rehearsal import into the real server with the dev app password, from a fake To Do:
 *
 *     ./gradlew :importer:test -Plive
 *
 * Creates boards prefixed "zz-proef " and deletes them at the end.
 */
@Tag("live")
class ImporterLiveTest {
    private val creds = Json.parseToJsonElement(
        File(System.getProperty("user.home"), ".config/deck-todo/dev-credentials.json").readText()
    ).jsonObject.mapValues { it.value.jsonPrimitive.content }
    private val client = DeckClient(creds.getValue("server"), creds.getValue("loginName"), creds.getValue("appPassword"))

    private class FakeTodo : TodoSource {
        val tasks = mutableMapOf<String, MutableList<GraphTask>>()
        override fun lists() = emptyList<GraphList>()
        override fun tasks(listId: String) = tasks[listId].orEmpty()
    }

    private val utc = { s: String -> GraphDateTime(s, "UTC") }

    @Test
    fun rehearsal() {
        val todo = FakeTodo()
        todo.tasks["L1"] = mutableListOf(
            GraphTask("t1", "melk", importance = "high", dueDateTime = utc("2026-10-29T23:00:00.0000000"),
                body = GraphBody("Bij de Jumbo"), recurrence = GraphRecurrence(GraphPattern("weekly", 1, daysOfWeek = listOf("friday"))),
                checklistItems = listOf(GraphChecklistItem("halfvol"), GraphChecklistItem("lactosevrij", true)),
                isReminderOn = true, reminderDateTime = utc("2099-01-01T08:00:00.0000000"),
                createdDateTime = "2026-10-01T10:00:00Z", lastModifiedDateTime = "v1"),
            GraphTask("t2", "brood", status = "completed", completedDateTime = utc("2026-10-20T00:00:00.0000000"),
                createdDateTime = "2026-10-02T10:00:00Z", lastModifiedDateTime = "v1"),
            GraphTask("t3", "kaas", createdDateTime = "2026-10-03T10:00:00Z", lastModifiedDateTime = "v1"),
        )
        todo.tasks["L2"] = mutableListOf(GraphTask("t4", "bellen", lastModifiedDateTime = "v1"))
        val plan = Plan(
            groups = listOf(PlanGroup("Huis", "dev", shareWith = listOf("Anna"))),
            lists = listOf(
                PlanList("dev", "L1", "Boodschappen", group = "Huis"),
                PlanList("dev", "L2", "Taken"),
            ),
        )
        val state = ImportState()
        val log = mutableListOf<String>()
        fun run() = Importer(plan, state, save = {}, source = { todo }, deck = { client }, rehearsal = true,
            out = { log += it; println(it) }).run()

        try {
            val first = run()
            assertEquals(4, first.created)
            assertEquals(0, first.mismatches)
            assertTrue(log.any { "niet gedeeld" in it }, "a rehearsal shares with nobody")
            assertTrue(log.any { "1 herinnering" in it })

            val huis = state.boards.getValue("dev/zz-proef Huis")
            assertNotNull(state.boards["dev/zz-proef ${DeckConventions.LOOSE_BOARD}"])
            val board = client.board(huis)
            assertTrue(board.acl.isEmpty())
            val stack = client.stacks(huis).single()
            assertEquals("Boodschappen", stack.title)
            val cards = stack.cards.orEmpty().associateBy { it.title }
            // Newest open task on top, the ticked one last.
            assertEquals(listOf("kaas", "melk", "brood"), stack.cards.orEmpty().sortedBy { it.order }.map { it.title })

            val melk = cards.getValue("melk")
            assertTrue(melk.labels.orEmpty().any { it.title == DeckConventions.IMPORTANT_LABEL })
            assertEquals(Instant.parse("2026-10-30T22:59:00Z"), Instant.parse(melk.duedate!!.replace("+00:00", "Z")))
            val body = TaskBodyCodec.parse(melk.description)
            assertEquals("Bij de Jumbo", body.note)
            assertEquals(2, body.steps.size)
            assertEquals("FREQ=WEEKLY;BYDAY=FR", body.recurrence!!.format())
            assertNull(melk.done)
            assertNotNull(cards.getValue("brood").done)

            // Again, nothing changed: nothing happens.
            val second = run()
            assertEquals(0, second.created + second.updated + second.deleted)
            assertEquals(4, second.unchanged)

            // Changed in To Do, deleted in To Do, changed on both sides.
            val l1 = todo.tasks.getValue("L1")
            l1[0] = l1[0].copy(title = "halfvolle melk", importance = "normal", lastModifiedDateTime = "v2")
            l1.removeAt(2)
            l1[1] = l1[1].copy(title = "bruin brood", lastModifiedDateTime = "v2")
            val brood = cards.getValue("brood")
            client.updateCard(huis, stack.id, brood.id, "brood (Deck)", brood.ownerUid!!, brood.order, brood.description.orEmpty(), null, Instant.now())
            Thread.sleep(1100) // lastModified has whole seconds
            val third = run()
            assertEquals(1, third.updated)
            assertEquals(1, third.deleted)
            assertEquals(1, third.conflicts)

            val after = client.stacks(huis).single().cards.orEmpty().associateBy { it.id }
            assertEquals("halfvolle melk", after.getValue(melk.id).title)
            assertTrue(after.getValue(melk.id).labels.orEmpty().none { it.title == DeckConventions.IMPORTANT_LABEL }, "star removed")
            assertEquals("brood (Deck)", after.getValue(brood.id).title)
            assertNull(after[cards.getValue("kaas").id])

            // The reminder file round-trips through the user's own files.
            val uid = client.currentUser().uid
            val path = ".deck-todo/test-import.json"
            client.writeFile(uid, path, Json.encodeToString(ImportHandoff(listOf(ImportHandoff.Reminder(melk.id, 4070937600)))))
            assertEquals(melk.id, Json.decodeFromString<ImportHandoff>(client.readFile(uid, path)!!).reminders.single().card)
            client.deleteFile(uid, path)
            assertNull(client.readFile(uid, path))
        } finally {
            state.boards.values.forEach { runCatching { client.deleteBoard(it) } }
        }
    }

    @Test
    fun leavesOtherBoardsAlone() {
        val todo = FakeTodo()
        todo.tasks["A"] = mutableListOf(GraphTask("a1", "appels", lastModifiedDateTime = "v1"))
        todo.tasks["B"] = mutableListOf(GraphTask("b1", "bellen", lastModifiedDateTime = "v1"))
        // Two boards that were there before the import, as Tom's own Deck boards are.
        val other = client.createBoard("${Importer.REHEARSAL_PREFIX}Bestaand", "0082c9")
        val joined = client.createBoard("${Importer.REHEARSAL_PREFIX}Erbij", "00a375")
        val state = ImportState()
        try {
            val own = client.createStack(other.id, "Eigen", 0)
            client.createCard(other.id, own.id, "eigen kaart")
            client.createStack(joined.id, "Eigen", 0)
            val plan = Plan(
                groups = listOf(PlanGroup("Bestaand", "dev"), PlanGroup("Erbij", "dev", mergeExisting = true)),
                lists = listOf(PlanList("dev", "A", "Boodschappen", group = "Bestaand"), PlanList("dev", "B", "Taken", group = "Erbij")),
            )
            val log = mutableListOf<String>()
            fun run(dryRun: Boolean) = Importer(plan, state, save = {}, source = { todo }, deck = { client }, rehearsal = true,
                dryRun = dryRun, out = { log += it; println(it) }).run()

            val dry = run(dryRun = true)
            assertEquals(1, dry.collisions, "a dry run already names the collision")
            val real = run(dryRun = false)
            assertEquals(1, real.collisions)
            assertEquals(1, real.created, "only the list of the group that asked to merge")
            assertTrue(log.any { "Bestaand" in it && "overgeslagen" in it })

            assertEquals(listOf("Eigen"), client.stacks(other.id).map { it.title }, "the other board is untouched")
            assertEquals(listOf("eigen kaart"), client.stacks(other.id).single().cards.orEmpty().map { it.title })
            assertNull(state.boards["dev/${Importer.REHEARSAL_PREFIX}Bestaand"])
            assertEquals(setOf("Eigen", "Taken"), client.stacks(joined.id).map { it.title }.toSet())
        } finally {
            (state.boards.values + other.id + joined.id).distinct().forEach { runCatching { client.deleteBoard(it) } }
        }
    }
}
