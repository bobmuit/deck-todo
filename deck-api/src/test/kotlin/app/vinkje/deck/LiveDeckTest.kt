package app.vinkje.deck

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Credentials
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import app.vinkje.domain.Recurrence
import app.vinkje.domain.Step
import app.vinkje.domain.TaskBody
import app.vinkje.domain.TaskBodyCodec
import java.io.File
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Runs against the real server with the dev app password from
 * ~/.config/deck-todo/dev-credentials.json (Login Flow v2 output; never committed).
 *
 *     ./gradlew :deck-api:test -Plive
 *
 * Creates throwaway boards titled "zz-test todo-app", deletes them at the end, and
 * writes what it observed to build/live-findings.txt.
 */
@Tag("live")
class LiveDeckTest {
    private val creds = Json.parseToJsonElement(
        File(System.getProperty("user.home"), ".config/deck-todo/dev-credentials.json").readText()
    ).jsonObject.mapValues { it.value.jsonPrimitive.content }
    private val client = DeckClient(creds.getValue("server"), creds.getValue("loginName"), creds.getValue("appPassword"))
    private val findings = mutableListOf<String>()

    private fun note(s: String) {
        findings += s
        println(s)
    }

    @Test
    fun spike() {
        val a = client.createBoard("zz-test todo-app", "0082c9")
        val b = client.createBoard("zz-test todo-app 2", "c9302c")
        try {
            val stack = client.createStack(a.id, "Boodschappen", 0)
            val body = TaskBody(
                note = "Bij de Jumbo",
                steps = listOf(Step("halfvol"), Step("lactosevrij", done = true)),
            ).withRecurrence(Recurrence.workdays())
            val description = TaskBodyCodec.format(body)
            val card = client.createCard(a.id, stack.id, "melk", description = description)

            // 1. Description survives the API verbatim.
            val read = client.card(a.id, stack.id, card.id)
            assertEquals(description, read.description)
            assertEquals(body, TaskBodyCodec.parse(read.description))
            note("description round-trip via API: OK")

            // 2. done via PUT, and back.
            val owner = read.ownerUid!!
            val doneAt = Instant.now()
            client.updateCard(a.id, stack.id, card.id, read.title, owner, read.order, description, null, doneAt)
            val afterDone = client.card(a.id, stack.id, card.id)
            assertNotNull(afterDone.done)
            note("done via PUT: OK (sent $doneAt, server stores ${afterDone.done}); archived=${afterDone.archived}")
            client.updateCard(a.id, stack.id, card.id, read.title, owner, read.order, description, null, null)
            assertNull(client.card(a.id, stack.id, card.id).done)
            note("undone via PUT done=null: OK")

            // 3. Done cards still show up in the stacks listing (or only in archived?).
            val listed = client.stacks(a.id).single { it.id == stack.id }.cards.orEmpty().map { it.id }
            note("open card listed in GET stacks: ${card.id in listed}")

            // 4. Conditional board list: 304 when unchanged, and a card edit moves the board ETag.
            val first = client.boards()!!
            note("GET boards with If-None-Match -> ${if (client.boards(first.etag) == null) "304" else "full body"}")
            val boardEtag = first.value.single { it.id == a.id }.etag
            Thread.sleep(2100) // Deck timestamps are whole seconds
            val brood = client.createCard(a.id, stack.id, "brood")
            note("after creating a card: conditional list -> ${if (client.boards(first.etag) == null) "304 (list ETag blind to cards)" else "200"}")
            val afterCreate = client.boards()!!.value.single { it.id == a.id }.etag
            note("after creating a card: board ETag in unconditional list moved=${afterCreate != boardEtag}")
            Thread.sleep(2100)
            client.updateCard(a.id, stack.id, brood.id, "brood", brood.ownerUid!!, brood.order, "", null, Instant.now())
            val etagAfterTick = client.boards()!!.value.single { it.id == a.id }.etag
            note("after ticking a card: board ETag moved=${etagAfterTick != afterCreate}; board(id) ETag matches list=${client.board(a.id).etag == etagAfterTick}")

            // 5. Moving a card to a stack on another board (list -> other group).
            val other = client.createStack(b.id, "Boodschappen", 0)
            val label = client.createLabel(a.id, "Belangrijk", "f1c40f")
            client.assignLabel(a.id, stack.id, card.id, label.id)
            // A card GET ignores the board and stack in its URL, so only stackId tells where it is.
            client.reorderCard(a.id, stack.id, card.id, 0, targetStackId = other.id)
            note("cross-board reorder: card moved=${client.card(a.id, stack.id, card.id).stackId == other.id}")
            val moved = client.moveCard(client.card(a.id, stack.id, card.id), b.id, other.id)
            note("cross-board moveCard: card moved=${moved.stackId == other.id}; labels after move: ${moved.labels?.map { "${it.title}@${it.id}" }}")

            // 6. Activity API: who changed what.
            note("activity: " + activitySample(a.id))
        } finally {
            client.deleteBoard(a.id)
            client.deleteBoard(b.id)
            note("cleanup: both test boards deleted")
            File("build/live-findings.txt").writeText(findings.joinToString("\n") + "\n")
        }
    }

    private fun activitySample(boardId: Long): String {
        val req = Request.Builder()
            .url(creds.getValue("server").trimEnd('/') + "/ocs/v2.php/apps/activity/api/v2/activity/deck?format=json&limit=10")
            .header("Authorization", Credentials.basic(creds.getValue("loginName"), creds.getValue("appPassword")))
            .header("OCS-APIRequest", "true")
            .build()
        OkHttpClient().newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) return "HTTP ${resp.code}"
            val data = Json.parseToJsonElement(text).jsonObject["ocs"]!!.jsonObject["data"]!!
            return (data as kotlinx.serialization.json.JsonArray).take(4).joinToString(" | ") { e ->
                val o = e.jsonObject
                listOf("type", "user", "object_type", "object_id", "subject")
                    .joinToString(",") { k -> "$k=${o[k]?.toString()?.take(60)}" }
            } + " (boardId=$boardId)"
        }
    }
}
