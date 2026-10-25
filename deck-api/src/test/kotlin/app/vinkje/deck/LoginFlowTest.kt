package app.vinkje.deck

import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LoginFlowTest {
    @Test
    fun `start, poll pending, poll done`() {
        val server = MockWebServer().apply { start() }
        val base = server.url("/").toString()
        server.enqueue(MockResponse().setBody("""{"poll":{"token":"t0k","endpoint":"${base}login/v2/poll"},"login":"${base}login/v2/flow/abc"}"""))
        server.enqueue(MockResponse().setResponseCode(404).setBody("[]"))
        server.enqueue(MockResponse().setBody("""{"server":"https://cloud.example","loginName":"tom","appPassword":"secret"}"""))

        val flow = LoginFlow()
        val started = flow.start(base)
        assertEquals("${base}login/v2/flow/abc", started.login)
        assertEquals("/index.php/login/v2", server.takeRequest().path)
        assertNull(flow.poll(started.poll))
        assertEquals("token=t0k", server.takeRequest().body.readUtf8())
        assertEquals(LoginCredentials("https://cloud.example", "tom", "secret"), flow.poll(started.poll))
        server.shutdown()
    }
}
