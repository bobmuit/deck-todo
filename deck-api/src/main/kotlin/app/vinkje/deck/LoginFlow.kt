package app.vinkje.deck

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

@Serializable
data class LoginCredentials(val server: String, val loginName: String, val appPassword: String)

/**
 * Nextcloud Login Flow v2: [start] returns a URL to open in the browser (where
 * Authentik does the real login), then [poll] until the user has granted access.
 * The result is an app password that shows up, and can be revoked, under
 * Settings → Security in Nextcloud.
 */
class LoginFlow(private val http: OkHttpClient = OkHttpClient(), private val userAgent: String = "deck-todo") {
    @Serializable
    data class Started(val login: String, val poll: Poll)

    @Serializable
    data class Poll(val token: String, val endpoint: String)

    private val json = Json { ignoreUnknownKeys = true }

    fun start(server: String): Started {
        val url = server.trimEnd('/').toHttpUrl().newBuilder().addPathSegments("index.php/login/v2").build()
        val req = Request.Builder().url(url).header("User-Agent", userAgent).post(ByteArray(0).toRequestBody()).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw DeckException(resp.code, body.take(300))
            return json.decodeFromString(body)
        }
    }

    /** Null while the user has not finished logging in (the server answers 404 until then). */
    fun poll(poll: Poll): LoginCredentials? {
        val req = Request.Builder().url(poll.endpoint)
            .post(FormBody.Builder().add("token", poll.token).build()).build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 404) return null
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw DeckException(resp.code, body.take(300))
            return json.decodeFromString(body)
        }
    }
}
