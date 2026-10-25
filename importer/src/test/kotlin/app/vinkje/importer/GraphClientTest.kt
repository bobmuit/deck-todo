package app.vinkje.importer

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class GraphClientTest {
    private lateinit var server: MockWebServer
    private val tokenCalls = mutableListOf<Boolean>()
    private lateinit var client: GraphClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer().apply { start() }
        client = GraphClient({ renew -> tokenCalls += renew; if (renew) "fresh" else "old" }, base = server.url("/v1.0/"), sleep = {})
    }

    @AfterEach
    fun tearDown() = server.shutdown()

    @Test
    fun `follows nextLink pages`() {
        server.enqueue(MockResponse().setBody("""{"value":[{"id":"a","displayName":"Taken","wellknownListName":"defaultList"}],"@odata.nextLink":"${server.url("/v1.0/me/todo/lists?skip=1")}"}"""))
        server.enqueue(MockResponse().setBody("""{"value":[{"id":"b","displayName":"Boodschappen","isShared":true}]}"""))
        assertEquals(listOf("a", "b"), client.lists().map { it.id })
        assertEquals("/v1.0/me/todo/lists", server.takeRequest().path)
        assertEquals("/v1.0/me/todo/lists?skip=1", server.takeRequest().path)
    }

    @Test
    fun `tasks come with their steps, fetched separately when not expanded`() {
        server.enqueue(MockResponse().setBody("""{"value":[
            {"id":"t1","title":"melk","checklistItems":[{"displayName":"halfvol","isChecked":false}]},
            {"id":"t2","title":"brood"}]}"""))
        server.enqueue(MockResponse().setBody("""{"value":[{"displayName":"bruin","isChecked":true}]}"""))
        val tasks = client.tasks("AQMk=/x")
        assertEquals(listOf("halfvol"), tasks[0].checklistItems!!.map { it.displayName })
        assertEquals(listOf("bruin"), tasks[1].checklistItems!!.map { it.displayName })
        val first = server.takeRequest().path!!
        assertTrue(first.startsWith("/v1.0/me/todo/lists/AQMk=%2Fx/tasks?"), first)
        assertTrue("%24expand=checklistItems" in first || "\$expand=checklistItems" in first, first)
        assertEquals("/v1.0/me/todo/lists/AQMk=%2Fx/tasks/t2/checklistItems", server.takeRequest().path)
    }

    @Test
    fun `renews the token once on 401 and waits out throttling`() {
        server.enqueue(MockResponse().setResponseCode(401))
        server.enqueue(MockResponse().setResponseCode(429).setHeader("Retry-After", "0"))
        server.enqueue(MockResponse().setBody("""{"displayName":"Anna"}"""))
        assertEquals("Anna", client.me().displayName)
        assertEquals(listOf(false, true, false), tokenCalls)
        assertEquals("Bearer old", server.takeRequest().getHeader("Authorization"))
        assertEquals("Bearer fresh", server.takeRequest().getHeader("Authorization"))
    }

    @Test
    fun `device code login waits while the user is still signing in`() {
        val auth = GraphAuth("client", "consumers", authority = server.url("/").toString(), sleep = {})
        server.enqueue(MockResponse().setBody("""{"device_code":"dc","user_code":"ABCD-EFGH","verification_uri":"https://microsoft.com/devicelogin","interval":1,"expires_in":60}"""))
        server.enqueue(MockResponse().setResponseCode(400).setBody("""{"error":"authorization_pending"}"""))
        server.enqueue(MockResponse().setBody("""{"access_token":"at","refresh_token":"rt","expires_in":3600}"""))
        val code = auth.startDeviceCode()
        assertEquals("ABCD-EFGH", code.userCode)
        val tokens = auth.awaitDeviceCode(code)
        assertEquals("rt", tokens.refreshToken)
        val start = server.takeRequest()
        assertEquals("/consumers/oauth2/v2.0/devicecode", start.path)
        assertTrue("offline_access" in start.body.readUtf8())
        assertTrue("device_code=dc" in server.takeRequest().body.readUtf8())
    }
}
