package app.vinkje.importer

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.FormBody
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException

/*
 * Microsoft To Do through Microsoft Graph, read-only. Only the fields the import
 * uses are declared. Graph has no list groups and no "My Day": groups come from the
 * plan file, and My Day starts empty every day anyway.
 */

@Serializable
data class GraphUser(val displayName: String = "", val userPrincipalName: String = "")

@Serializable
data class GraphList(
    val id: String,
    val displayName: String,
    val isOwner: Boolean = true,
    val isShared: Boolean = false,
    /** "defaultList" (Taken), "flaggedEmails" (Outlook's flagged mail) or "none". */
    val wellknownListName: String = "none",
)

/** A local time in [timeZone], which is usually "UTC" but may be a Windows zone name. */
@Serializable
data class GraphDateTime(val dateTime: String, val timeZone: String = "UTC")

@Serializable
data class GraphBody(val content: String = "", val contentType: String = "text")

@Serializable
data class GraphChecklistItem(val displayName: String = "", val isChecked: Boolean = false)

@Serializable
data class GraphPattern(
    /** daily, weekly, absoluteMonthly, relativeMonthly, absoluteYearly, relativeYearly */
    val type: String,
    val interval: Int = 1,
    val month: Int = 0,
    val dayOfMonth: Int = 0,
    val daysOfWeek: List<String> = emptyList(),
)

@Serializable
data class GraphRecurrence(val pattern: GraphPattern)

@Serializable
data class GraphTask(
    val id: String,
    val title: String = "",
    /** notStarted, inProgress, completed, waitingOnOthers, deferred */
    val status: String = "notStarted",
    /** low, normal, high; To Do's star is "high". */
    val importance: String = "normal",
    val isReminderOn: Boolean = false,
    val reminderDateTime: GraphDateTime? = null,
    val dueDateTime: GraphDateTime? = null,
    val completedDateTime: GraphDateTime? = null,
    val createdDateTime: String? = null,
    val lastModifiedDateTime: String? = null,
    val body: GraphBody? = null,
    val recurrence: GraphRecurrence? = null,
    val checklistItems: List<GraphChecklistItem>? = null,
    val hasAttachments: Boolean = false,
)

@Serializable
private data class Page<T>(val value: List<T>, @SerialName("@odata.nextLink") val next: String? = null)

class GraphException(val status: Int, message: String) : IOException("Microsoft $status: $message")

internal val graphJson = Json { ignoreUnknownKeys = true; explicitNulls = false }

/**
 * Microsoft's OAuth device-code flow: the user enters a short code at
 * microsoft.com/devicelogin on any device, so nothing needs a redirect URL here.
 */
class GraphAuth(
    private val clientId: String,
    tenant: String = "common",
    private val http: OkHttpClient = OkHttpClient(),
    authority: String = "https://login.microsoftonline.com",
    private val sleep: (Long) -> Unit = Thread::sleep,
) {
    @Serializable
    data class DeviceCode(
        @SerialName("device_code") val deviceCode: String,
        @SerialName("user_code") val userCode: String,
        @SerialName("verification_uri") val verificationUri: String,
        val interval: Int = 5,
        @SerialName("expires_in") val expiresIn: Int = 900,
    )

    @Serializable
    data class Tokens(
        @SerialName("access_token") val accessToken: String,
        /** Microsoft rotates it: store the new one after every refresh. */
        @SerialName("refresh_token") val refreshToken: String? = null,
        @SerialName("expires_in") val expiresIn: Int = 3600,
    )

    @Serializable
    private data class OAuthError(val error: String = "", @SerialName("error_description") val description: String = "")

    private val endpoint = authority.trimEnd('/').toHttpUrl().newBuilder().addPathSegment(tenant).addPathSegments("oauth2/v2.0").build()

    fun startDeviceCode(): DeviceCode =
        graphJson.decodeFromString(post("devicecode", FormBody.Builder().add("client_id", clientId).add("scope", SCOPE)).second)

    /** Blocks until the user has signed in at [DeviceCode.verificationUri]. */
    fun awaitDeviceCode(code: DeviceCode): Tokens {
        var interval = code.interval.coerceAtLeast(1)
        val deadline = System.currentTimeMillis() + code.expiresIn * 1000L
        while (System.currentTimeMillis() < deadline) {
            sleep(interval * 1000L)
            val (status, body) = post("token", FormBody.Builder()
                .add("grant_type", "urn:ietf:params:oauth:grant-type:device_code")
                .add("client_id", clientId).add("device_code", code.deviceCode), allowError = true)
            if (status in 200..299) return graphJson.decodeFromString(body)
            val error = runCatching { graphJson.decodeFromString<OAuthError>(body) }.getOrDefault(OAuthError())
            when (error.error) {
                "authorization_pending" -> Unit
                "slow_down" -> interval += 5
                else -> throw GraphException(status, error.error.ifEmpty { body.take(200) })
            }
        }
        throw GraphException(400, "expired_token")
    }

    fun refresh(refreshToken: String): Tokens =
        graphJson.decodeFromString(post("token", FormBody.Builder()
            .add("grant_type", "refresh_token").add("client_id", clientId)
            .add("refresh_token", refreshToken).add("scope", SCOPE)).second)

    private fun post(path: String, form: FormBody.Builder, allowError: Boolean = false): Pair<Int, String> {
        val req = Request.Builder().url(endpoint.newBuilder().addPathSegment(path).build()).post(form.build()).build()
        http.newCall(req).execute().use { resp ->
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful && !allowError) {
                val error = runCatching { graphJson.decodeFromString<OAuthError>(body).error }.getOrNull()
                throw GraphException(resp.code, error?.ifEmpty { null } ?: body.take(200))
            }
            return resp.code to body
        }
    }

    companion object {
        /**
         * The public client of Microsoft Graph PowerShell: it accepts personal accounts and
         * the device-code flow, so no app registration of our own is needed.
         */
        const val DEFAULT_CLIENT_ID = "14d82eec-204b-4c2f-b7e8-296a70dab67e"
        const val SCOPE = "Tasks.Read User.Read offline_access"
    }
}

/** The To Do lists and tasks of one account. The importer depends on this, not on HTTP. */
interface TodoSource {
    fun lists(): List<GraphList>
    fun tasks(listId: String): List<GraphTask>
}

class GraphClient(
    /** A valid access token; `true` asks for a fresh one after a 401. */
    private val accessToken: (refresh: Boolean) -> String,
    private val http: OkHttpClient = OkHttpClient(),
    private val base: HttpUrl = "https://graph.microsoft.com/v1.0/".toHttpUrl(),
    private val sleep: (Long) -> Unit = Thread::sleep,
) : TodoSource {
    fun me(): GraphUser = graphJson.decodeFromString(get(url("me")))

    override fun lists(): List<GraphList> = paged(url("me/todo/lists"))

    override fun tasks(listId: String): List<GraphTask> {
        val tasks = paged<GraphTask>(url("me/todo/lists") {
            addPathSegment(listId).addPathSegment("tasks")
                .addQueryParameter("\$top", "100").addQueryParameter("\$expand", "checklistItems")
        })
        // Should $expand ever be ignored, fetch the steps per task.
        return tasks.map { t -> if (t.checklistItems != null) t else t.copy(checklistItems = checklist(listId, t.id)) }
    }

    private fun checklist(listId: String, taskId: String): List<GraphChecklistItem> =
        paged(url("me/todo/lists") { addPathSegment(listId).addPathSegment("tasks").addPathSegment(taskId).addPathSegment("checklistItems") })

    private fun url(path: String, more: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        base.newBuilder().addPathSegments(path).apply(more).build()

    private inline fun <reified T> paged(first: HttpUrl): List<T> {
        val all = mutableListOf<T>()
        var next: HttpUrl? = first
        while (next != null) {
            val page = graphJson.decodeFromString<Page<T>>(get(next))
            all += page.value
            next = page.next?.toHttpUrl()
        }
        return all
    }

    /** Retries throttling (429) and brief outages, and renews the token once on a 401. */
    private fun get(url: HttpUrl): String {
        var renew = false
        var renewed = false
        repeat(MAX_TRIES) { attempt ->
            val token = accessToken(renew).also { renew = false }
            val req = Request.Builder().url(url).header("Authorization", "Bearer $token").get().build()
            http.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                when {
                    resp.isSuccessful -> return body
                    resp.code == 401 && !renewed -> { renew = true; renewed = true }
                    (resp.code == 429 || resp.code >= 500) && attempt < MAX_TRIES - 1 ->
                        sleep((resp.header("Retry-After")?.toLongOrNull() ?: (2L shl attempt)) * 1000)
                    else -> throw GraphException(resp.code, body.take(200))
                }
            }
        }
        throw GraphException(429, "still throttled after $MAX_TRIES tries")
    }

    private companion object {
        const val MAX_TRIES = 6
    }
}
