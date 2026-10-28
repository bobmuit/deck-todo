package app.vinkje.sync

import app.vinkje.data.OutboxEntity
import app.vinkje.data.Session
import app.vinkje.data.TaskEntity
import app.vinkje.deck.DeckClient
import app.vinkje.deck.DeckException
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A tiny Deck: board 1 with stack 24 "Boodschappen", and the endpoints a sync round calls. */
class FakeDeck : Dispatcher() {
    var createStatus = 200
    var cardGetStatus = 200
    var stacks = mutableMapOf(24L to "Boodschappen")
    val cards = mutableMapOf<Long, Pair<Long, String>>() // id -> stack, title
    var nextCard = 900L
    var nextStack = 50L
    var stamp = 1000L
    private fun api(path: String) = path.removePrefix("/index.php/apps/deck/api/v1.0/")

    override fun dispatch(request: RecordedRequest): MockResponse {
        val path = request.path!!.substringBefore('?')
        if (path.startsWith("/ocs/") || path.startsWith("/remote.php/")) return MockResponse().setResponseCode(404)
        val p = api(path)
        val m = request.method
        fun card(id: Long) = cards[id]!!.let { (s, t) -> """{"id":$id,"title":"$t","stackId":$s,"order":0,"lastModified":$stamp}""" }
        return when {
            m == "GET" && p == "boards" ->
                MockResponse().setBody("""[{"id":1,"title":"Huis","owner":{"uid":"tom"},"permissions":{"PERMISSION_EDIT":true,"PERMISSION_MANAGE":true},"lastModified":1,"ETag":"e$stamp"}]""")
            m == "GET" && p == "boards/1/stacks" ->
                MockResponse().setBody(stacks.entries.joinToString(",", "[", "]") { (id, title) ->
                    """{"id":$id,"title":"$title","boardId":1,"cards":[${cards.filter { it.value.first == id }.keys.joinToString(",") { card(it) }}]}"""
                })
            m == "POST" && p == "boards/1/stacks" -> {
                val id = nextStack++; stacks[id] = "Boodschappen"; stamp++
                MockResponse().setBody("""{"id":$id,"title":"Boodschappen","boardId":1}""")
            }
            m == "POST" && Regex("boards/1/stacks/(\\d+)/cards").matches(p) -> {
                if (createStatus != 200) return MockResponse().setResponseCode(createStatus).setBody("""{"status":$createStatus,"message":"nope"}""")
                val stack = p.split('/')[3].toLong()
                val title = Regex("\"title\":\"([^\"]*)\"").find(request.body.readUtf8())!!.groupValues[1]
                val id = nextCard++; cards[id] = stack to title; stamp++
                MockResponse().setBody(card(id))
            }
            m == "GET" && Regex("boards/1/stacks/\\d+/cards/(\\d+)").matches(p) ->
                if (cardGetStatus != 200) MockResponse().setResponseCode(cardGetStatus) else MockResponse().setBody(card(p.substringAfterLast('/').toLong()))
            else -> MockResponse().setResponseCode(500).setBody("unexpected $m $p")
        }
    }
}

private const val RETRY = 30 * 60 * 1000L

/**
 * Tasks typed offline, against a fake Deck. All but the first case used to lose them: a
 * refused create deleted them, and a list whose stack went took them along.
 */
class SyncEngineTest {
    private val deck = FakeDeck()
    private val server = MockWebServer().apply { dispatcher = deck; start() }
    private val dao = FakeDao()
    private val prefs = FakePrefs().apply { map["selfUid"] = "tom" }
    private val logged = mutableListOf<String>()
    private var clock = 1_000_000L

    private fun engine() = SyncEngine(dao, DeckClient(server.url("/").toString(), "tom", "pw"), prefs, Session(prefs), now = { clock }, log = { logged += it })

    @AfterEach
    fun tearDown() = server.shutdown()

    /** The phone as it is after a first sync: group, Boodschappen, nothing in it. */
    private fun synced(): Long = runBlocking {
        engine().run()
        dao.lists.single().id
    }

    private suspend fun addOffline(listId: Long, title: String) {
        val id = dao.insertTask(TaskEntity(listId = listId, title = title, order = 0))
        dao.enqueue(OutboxEntity(kind = OutboxEntity.Kind.CREATE, rowId = id))
    }

    @Test
    fun `items added offline reach Deck`() = runBlocking {
        val list = synced()
        addOffline(list, "melk"); addOffline(list, "kaas")
        engine().run()
        assertEquals(setOf("melk", "kaas"), deck.cards.values.map { it.second }.toSet())
        assertEquals(setOf("melk", "kaas"), dao.tasks.map { it.title }.toSet())
        assertTrue(dao.ops.isEmpty())
    }

    @Test
    fun `a refused create keeps the items and tries again later`() = runBlocking {
        val list = synced()
        addOffline(list, "melk"); addOffline(list, "kaas")
        deck.createStatus = 404
        val delay = engine().run()
        assertEquals(setOf("melk", "kaas"), dao.tasks.map { it.title }.toSet(), "items kept on the phone")
        assertEquals(2, dao.ops.size)
        assertEquals(RETRY, delay)
        assertTrue(logged.any { "melk" in it && "POSTPONE" in it }, logged.toString())

        deck.createStatus = 200
        engine().run()
        assertEquals(2, dao.ops.size, "not before their time")
        clock += RETRY
        engine().run()
        assertEquals(setOf("melk", "kaas"), deck.cards.values.map { it.second }.toSet())
        assertTrue(dao.ops.isEmpty())
    }

    @Test
    fun `a list whose stack went is made again with the items typed here`() = runBlocking {
        val list = synced()
        // Someone else's card, then the stack goes while this phone is offline.
        deck.cards[800] = 24L to "brood"; deck.stamp++
        engine().run()
        addOffline(list, "melk")
        deck.stacks.clear(); deck.cards.clear(); deck.stamp++
        deck.createStatus = 404
        val delay = engine().run()
        assertEquals(0L, delay, "pushes the remade list straight away")
        assertEquals(listOf("melk"), dao.tasks.map { it.title }, "brood went with the stack, melk stays")
        assertEquals(null, dao.lists.single().serverId)

        deck.createStatus = 200
        engine().run()
        val stack = deck.stacks.keys.single()
        assertEquals(listOf(stack to "melk"), deck.cards.values.toList())
        assertEquals(stack, dao.lists.single().serverId)
        assertTrue(dao.ops.isEmpty(), dao.ops.toString())
    }

    @Test
    fun `items pushed into a stack that was just removed are made again`() = runBlocking {
        val list = synced()
        addOffline(list, "melk")
        // Deck accepts the card into the removed stack, which the pull no longer lists.
        val removed = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse {
                val r = deck.dispatch(request)
                if (request.method == "POST" && request.path!!.endsWith("/cards")) deck.stacks.remove(24L)
                return r
            }
        }
        server.dispatcher = removed
        engine().run()
        server.dispatcher = deck
        engine().run()
        val live = deck.cards.values.filter { it.first in deck.stacks }
        assertEquals(listOf("melk"), live.map { it.second })
        assertEquals(listOf("melk"), dao.tasks.map { it.title })
        assertEquals(live.single().first, dao.lists.single().serverId)
    }

    @Test
    fun `a create cut short after the card was made does not make it twice`() = runBlocking {
        val list = synced()
        addOffline(list, "melk")
        deck.cardGetStatus = 500
        assertThrows<DeckException> { runBlocking { engine().run() } }
        deck.cardGetStatus = 200
        engine().run()
        assertEquals(listOf("melk"), deck.cards.values.map { it.second })
        assertEquals(deck.cards.keys.single(), dao.tasks.single().serverId)
        assertTrue(dao.ops.isEmpty(), dao.ops.toString())

        // Refused instead of failing: the round goes on, and the card is still made once.
        addOffline(list, "kaas")
        deck.cardGetStatus = 403
        engine().run()
        deck.cardGetStatus = 200
        engine().run()
        assertEquals(listOf("melk", "kaas"), deck.cards.values.map { it.second })
        assertTrue(dao.ops.isEmpty(), dao.ops.toString())
    }
}
