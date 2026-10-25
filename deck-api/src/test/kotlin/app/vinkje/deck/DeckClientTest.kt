package app.vinkje.deck

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DeckClientTest {
    private lateinit var server: MockWebServer
    private lateinit var client: DeckClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = DeckClient(server.url("/").toString(), "tom", "app-pass")
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    @Test
    fun `sends auth and OCS headers to the v1 API`() {
        server.enqueue(MockResponse().setBody("[]"))
        client.boards()
        val req = server.takeRequest()
        assertEquals("/index.php/apps/deck/api/v1.0/boards", req.path)
        assertEquals("true", req.getHeader("OCS-APIRequest"))
        assertTrue(req.getHeader("Authorization")!!.startsWith("Basic "))
    }

    @Test
    fun `board list is conditional on its ETag`() {
        server.enqueue(MockResponse().setBody("[]").setHeader("ETag", "\"abc\""))
        assertEquals("\"abc\"", client.boards()!!.etag)
        server.enqueue(MockResponse().setResponseCode(304))
        assertNull(client.boards(ifNoneMatch = "\"abc\""))
        server.takeRequest()
        assertEquals("\"abc\"", server.takeRequest().getHeader("If-None-Match"))
    }

    @Test
    fun `parses cards as our instance returns them`() {
        // Shape copied from a real Deck server on 2026-10-25 (trimmed).
        server.enqueue(MockResponse().setBody("""
            [{"id":24,"title":"Boodschappen","boardId":2,"order":2,"deletedAt":0,"lastModified":1792889336,
              "ETag":"21a1","cards":[
                {"id":933,"title":"melk","stackId":24,"type":"plain","order":4,"archived":false,
                 "owner":"u2","description":"- [ ] halfvol","duedate":"2026-10-31T11:00:00+00:00",
                 "done":null,"lastModified":1792888729,"createdAt":1792271697,"labels":null,
                 "assignedUsers":[{"id":1,"participant":{"primaryKey":"m1","uid":"m1","displayname":"Anna"},"cardId":933,"type":0}],
                 "attachments":[],"commentsUnread":0,"overdue":0,"ETag":"67ae"},
                {"id":934,"title":"brood","stackId":24,"order":5,"owner":{"uid":"u2","displayname":"Tom"},
                 "done":"2026-10-25T08:00:00+00:00"}]}]
        """.trimIndent()))
        val stack = client.stacks(2).single()
        val (melk, brood) = stack.cards!!
        assertEquals("u2", melk.ownerUid)
        assertEquals("u2", brood.ownerUid)
        assertEquals("Anna", melk.assignedUsers!!.single().participant.displayname)
        assertEquals(false, melk.isDone)
        assertEquals(true, brood.isDone)
    }

    @Test
    fun `updateCard sends the full card with explicit nulls`() {
        server.enqueue(MockResponse().setBody("""{"id":5,"title":"t","stackId":3}"""))
        client.updateCard(1, 3, 5, "t", "tom", 2, "", duedate = null, done = Instant.parse("2026-10-25T10:15:30Z"))
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/index.php/apps/deck/api/v1.0/boards/1/stacks/3/cards/5", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("2026-10-25T10:15:30Z", body["done"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["duedate"])
        assertEquals("plain", body["type"]!!.jsonPrimitive.content)
    }

    @Test
    fun `errors carry the status`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("""{"message":"Permission denied"}"""))
        val e = assertThrows<DeckException> { client.board(9) }
        assertEquals(403, e.status)
    }

    @Test
    fun `reads Deck activities newer than an id, and 304 as none`() {
        server.enqueue(MockResponse().setBody(
            """{"ocs":{"meta":{"status":"ok"},"data":[{"activity_id":7,"app":"deck","user":"anna","object_type":"deck_card","object_id":42,"subject":"..."}]}}"""
        ))
        server.enqueue(MockResponse().setResponseCode(304))
        assertEquals(listOf(Activity(7, "anna", "deck_card", 42)), client.activities(since = 5))
        val req = server.takeRequest()
        assertEquals("/ocs/v2.php/apps/activity/api/v2/activity/deck", req.requestUrl!!.encodedPath)
        assertEquals("5", req.requestUrl!!.queryParameter("since"))
        assertEquals("asc", req.requestUrl!!.queryParameter("sort"))
        assertNull(client.activities(since = 7))
    }

    @Test
    fun `moveCard puts the card on the target stack with its fields untouched`() {
        server.enqueue(MockResponse().setBody("""{"id":5,"title":"kaas","stackId":70}"""))
        val card = Card(id = 5, title = "kaas", stackId = 69, order = 3, owner = kotlinx.serialization.json.JsonPrimitive("tom"),
            description = "- [ ] jong", duedate = "2026-10-31T22:59:00+00:00", done = null)
        assertEquals(70, client.moveCard(card, boardId = 24, stackId = 70).stackId)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertEquals("/index.php/apps/deck/api/v1.0/boards/24/stacks/70/cards/5", req.path)
        val body = Json.parseToJsonElement(req.body.readUtf8()).jsonObject
        assertEquals("2026-10-31T22:59:00+00:00", body["duedate"]!!.jsonPrimitive.content)
        assertEquals(JsonNull, body["done"])
        assertEquals("- [ ] jong", body["description"]!!.jsonPrimitive.content)
        assertEquals("tom", body["owner"]!!.jsonPrimitive.content)
    }

    @Test
    fun `finds users by name and leaves out groups`() {
        server.enqueue(MockResponse().setBody(
            """{"ocs":{"meta":{"status":"ok"},"data":[{"id":"0042","label":"Anna","source":"users"},{"id":"gezin","label":"Gezin","source":"groups"}]}}"""
        ))
        assertEquals(listOf(User("0042", "Anna")), client.findUsers("ann"))
        val url = server.takeRequest().requestUrl!!
        assertEquals("/ocs/v2.php/core/autocomplete/get", url.encodedPath)
        assertEquals("ann", url.queryParameter("search"))
        assertEquals("0", url.queryParameter("shareTypes[]"))
    }

    @Test
    fun `files live under the user's DAV root, a missing one reads as null`() {
        server.enqueue(MockResponse().setResponseCode(404))
        assertNull(client.readFile("u1", ImportHandoff.PATH))
        assertEquals("/remote.php/dav/files/u1/.deck-todo/import.json", server.takeRequest().path)

        server.enqueue(MockResponse().setResponseCode(405)) // folder exists
        server.enqueue(MockResponse().setResponseCode(201))
        client.writeFile("u1", ImportHandoff.PATH, "{}")
        server.takeRequest().let { assertEquals("MKCOL", it.method); assertEquals("/remote.php/dav/files/u1/.deck-todo", it.path) }
        server.takeRequest().let { assertEquals("PUT", it.method); assertEquals("{}", it.body.readUtf8()) }

        server.enqueue(MockResponse().setResponseCode(404))
        client.deleteFile("u1", ImportHandoff.PATH)
        assertEquals("DELETE", server.takeRequest().method)
    }
}
