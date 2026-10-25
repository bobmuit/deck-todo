package app.vinkje.deck

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.Credentials
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/** A response plus the ETag to send as If-None-Match next time. */
data class Fetched<T>(val value: T, val etag: String?)

class DeckException(val status: Int, message: String) : IOException("Deck API $status: $message")

/**
 * Blocking client for the Deck REST API v1.0. Callers run it off the main thread
 * (the app from a WorkManager worker, the importer from a CLI).
 *
 * Authenticates with a Nextcloud app password from Login Flow v2.
 */
class DeckClient(
    server: String,
    loginName: String,
    appPassword: String,
    private val http: OkHttpClient = OkHttpClient(),
) {
    private val root: HttpUrl = server.trimEnd('/').toHttpUrl()
    private val base: HttpUrl = root.newBuilder().addPathSegments("index.php/apps/deck/api/v1.0").build()
    private val auth = Credentials.basic(loginName, appPassword)

    internal val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    // --- boards ---

    /**
     * All boards, or null when nothing changed on any board since [ifNoneMatch] (HTTP 304).
     *
     * This is the cheap sync check: Deck ignores If-Modified-Since but honours an ETag
     * on the board list. Each [Board.etag] also changes whenever a stack or card on it
     * changes, so only boards whose ETag moved need their stacks fetched.
     *
     * Caveat (measured 2026-10-25): the ETags derive from whole-second timestamps, so a
     * change made in the same second as the previous change is invisible to the ETag.
     * A board whose lastModified is within a second of the fetch must be re-fetched
     * unconditionally on the next sync.
     */
    fun boards(ifNoneMatch: String? = null): Fetched<List<Board>>? =
        get("boards", ifNoneMatch)?.let { (body, etag) -> Fetched(json.decodeFromString(body), etag) }

    fun board(boardId: Long): Board = json.decodeFromString(get("boards/$boardId")!!.first)

    fun createBoard(title: String, color: String): Board =
        json.decodeFromString(send("POST", "boards", buildJsonObject { put("title", title); put("color", color) }))

    fun updateBoard(boardId: Long, title: String, color: String, archived: Boolean = false): Board =
        json.decodeFromString(send("PUT", "boards/$boardId", buildJsonObject {
            put("title", title); put("color", color); put("archived", archived)
        }))

    fun deleteBoard(boardId: Long) {
        send("DELETE", "boards/$boardId")
    }

    /** Shares a board with one user. type 0 = user. */
    fun share(boardId: Long, uid: String, edit: Boolean = true, share: Boolean = false, manage: Boolean = false): Acl =
        json.decodeFromString(send("POST", "boards/$boardId/acl", buildJsonObject {
            put("type", 0); put("participant", uid)
            put("permissionEdit", edit); put("permissionShare", share); put("permissionManage", manage)
        }))

    fun unshare(boardId: Long, aclId: Long) {
        send("DELETE", "boards/$boardId/acl/$aclId")
    }

    // --- stacks (= lists) ---

    /** Stacks with their non-archived cards, done ones included. This endpoint sends no ETag. */
    fun stacks(boardId: Long): List<Stack> = json.decodeFromString(get("boards/$boardId/stacks")!!.first)

    fun createStack(boardId: Long, title: String, order: Int): Stack =
        json.decodeFromString(send("POST", "boards/$boardId/stacks", buildJsonObject {
            put("title", title); put("order", order)
        }))

    fun updateStack(boardId: Long, stackId: Long, title: String, order: Int): Stack =
        json.decodeFromString(send("PUT", "boards/$boardId/stacks/$stackId", buildJsonObject {
            put("title", title); put("order", order)
        }))

    fun deleteStack(boardId: Long, stackId: Long) {
        send("DELETE", "boards/$boardId/stacks/$stackId")
    }

    /** Stacks holding archived cards, with only those cards. */
    fun archivedStacks(boardId: Long): List<Stack> = json.decodeFromString(get("boards/$boardId/stacks/archived")!!.first)

    // --- cards (= tasks) ---

    fun card(boardId: Long, stackId: Long, cardId: Long): Card =
        json.decodeFromString(get("boards/$boardId/stacks/$stackId/cards/$cardId")!!.first)

    fun createCard(boardId: Long, stackId: Long, title: String, order: Int = 0, description: String = "", duedate: Instant? = null): Card =
        json.decodeFromString(send("POST", "boards/$boardId/stacks/$stackId/cards", buildJsonObject {
            put("title", title); put("type", "plain"); put("order", order)
            put("description", description)
            put("duedate", duedate.toJson())
        }))

    /**
     * Deck's PUT replaces the whole card, so every field is sent. [done] and
     * [archived] are separate in Deck: To Do's "completed" maps to [done] only.
     * The server keeps [done] to whole seconds.
     */
    fun updateCard(
        boardId: Long,
        stackId: Long,
        cardId: Long,
        title: String,
        ownerUid: String,
        order: Int,
        description: String,
        duedate: Instant?,
        done: Instant?,
        archived: Boolean = false,
    ): Card = json.decodeFromString(send("PUT", "boards/$boardId/stacks/$stackId/cards/$cardId", buildJsonObject {
        put("title", title); put("type", "plain"); put("owner", ownerUid); put("order", order)
        put("description", description); put("duedate", duedate.toJson())
        put("done", done.toJson()); put("archived", archived)
    }))

    /**
     * Moves a card within its stack, or to [targetStackId] on the same board. Deck ignores
     * a stack on another board here; use [moveCard] for that.
     */
    fun reorderCard(boardId: Long, stackId: Long, cardId: Long, order: Int, targetStackId: Long = stackId) {
        send("PUT", "boards/$boardId/stacks/$stackId/cards/$cardId/reorder", buildJsonObject {
            put("order", order); put("stackId", targetStackId)
        })
    }

    /**
     * Moves [card] to [stackId], which may be on another board: a card PUT goes to the
     * stack in its URL (the board in it is ignored). Deck carries labels over by title,
     * creating them on the target board when missing, and keeps the assignees
     * (verified on our instance 2026-10-25).
     */
    fun moveCard(card: Card, boardId: Long, stackId: Long): Card =
        json.decodeFromString(send("PUT", "boards/$boardId/stacks/$stackId/cards/${card.id}", buildJsonObject {
            put("title", card.title); put("type", card.type); put("owner", card.ownerUid ?: ""); put("order", card.order)
            put("description", card.description ?: "")
            put("duedate", card.duedate?.let(::JsonPrimitive) ?: JsonNull)
            put("done", card.done?.let(::JsonPrimitive) ?: JsonNull)
            put("archived", card.archived)
        }))

    fun deleteCard(boardId: Long, stackId: Long, cardId: Long) {
        send("DELETE", "boards/$boardId/stacks/$stackId/cards/$cardId")
    }

    fun createLabel(boardId: Long, title: String, color: String): Label =
        json.decodeFromString(send("POST", "boards/$boardId/labels", buildJsonObject {
            put("title", title); put("color", color)
        }))

    fun assignLabel(boardId: Long, stackId: Long, cardId: Long, labelId: Long) {
        send("PUT", "boards/$boardId/stacks/$stackId/cards/$cardId/assignLabel", buildJsonObject { put("labelId", labelId) })
    }

    fun removeLabel(boardId: Long, stackId: Long, cardId: Long, labelId: Long) {
        send("PUT", "boards/$boardId/stacks/$stackId/cards/$cardId/removeLabel", buildJsonObject { put("labelId", labelId) })
    }

    fun assignUser(boardId: Long, stackId: Long, cardId: Long, uid: String) {
        send("PUT", "boards/$boardId/stacks/$stackId/cards/$cardId/assignUser", buildJsonObject { put("userId", uid) })
    }

    fun unassignUser(boardId: Long, stackId: Long, cardId: Long, uid: String) {
        send("PUT", "boards/$boardId/stacks/$stackId/cards/$cardId/unassignUser", buildJsonObject { put("userId", uid) })
    }

    // --- Nextcloud OCS: who am I, who changed what ---

    fun currentUser(): User = ocs<OcsUser>(ocsUrl("cloud/user"))!!.let { User(it.id, it.displayname) }

    /** Nextcloud users matching [search] by name, as the share dialogs find them. */
    fun findUsers(search: String, limit: Int = 10): List<User> =
        ocs<List<OcsAutocomplete>>(ocsUrl("core/autocomplete/get") {
            addQueryParameter("search", search).addQueryParameter("itemType", "").addQueryParameter("itemId", "")
                .addQueryParameter("shareTypes[]", "0").addQueryParameter("limit", "$limit")
        }).orEmpty().filter { it.source == "users" }.map { User(it.id, it.label) }

    /**
     * Deck activities newer than [since], oldest first, or null when there are none (HTTP 304).
     * Deck cards carry no "last edited by", so this is the only place that says who did it.
     */
    fun activities(since: Long, limit: Int = 100): List<Activity>? =
        ocs(ocsUrl("apps/activity/api/v2/activity/deck") { addQueryParameter("since", "$since").addQueryParameter("sort", "asc").addQueryParameter("limit", "$limit") })

    /** The id of the newest Deck activity, to start [activities] from; null when there are none. */
    fun latestActivityId(): Long? =
        ocs<List<Activity>>(ocsUrl("apps/activity/api/v2/activity/deck") { addQueryParameter("limit", "1") })?.firstOrNull()?.id

    /** Revokes the app password this client logs in with, as Settings → Security does. */
    fun revokeAppPassword() {
        http.newCall(request(ocsUrl("core/apppassword")).delete().build()).execute().use { resp ->
            if (!resp.isSuccessful) throw DeckException(resp.code, resp.body?.string().orEmpty().take(300))
        }
    }

    // --- WebDAV: small files in the user's own Nextcloud files ---

    /** The text of [path] (relative to the files root of [uid]), or null when there is no such file. */
    fun readFile(uid: String, path: String): String? =
        http.newCall(request(davUrl(uid, path)).get().build()).execute().use { resp ->
            if (resp.code == 404) return null
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw DeckException(resp.code, body.take(300))
            body
        }

    /** Writes [text] to [path], creating its folder first. */
    fun writeFile(uid: String, path: String, text: String) {
        val folder = path.substringBeforeLast('/', "")
        if (folder.isNotEmpty()) {
            http.newCall(request(davUrl(uid, folder)).method("MKCOL", null).build()).execute().use { resp ->
                // 405: the folder is already there.
                if (!resp.isSuccessful && resp.code != 405) throw DeckException(resp.code, resp.body?.string().orEmpty().take(300))
            }
        }
        http.newCall(request(davUrl(uid, path)).put(text.toRequestBody(JSON)).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw DeckException(resp.code, resp.body?.string().orEmpty().take(300))
        }
    }

    /** Deletes [path]; a file that is already gone is fine. */
    fun deleteFile(uid: String, path: String) {
        http.newCall(request(davUrl(uid, path)).delete().build()).execute().use { resp ->
            if (!resp.isSuccessful && resp.code != 404) throw DeckException(resp.code, resp.body?.string().orEmpty().take(300))
        }
    }

    private fun davUrl(uid: String, path: String): HttpUrl =
        root.newBuilder().addPathSegments("remote.php/dav/files").addPathSegment(uid).addPathSegments(path).build()

    private fun ocsUrl(path: String, query: HttpUrl.Builder.() -> Unit = {}): HttpUrl =
        root.newBuilder().addPathSegments("ocs/v2.php").addPathSegments(path).addQueryParameter("format", "json").apply(query).build()

    private inline fun <reified T> ocs(url: HttpUrl): T? =
        get(url)?.let { (body, _) -> json.decodeFromString<OcsEnvelope<T>>(body).ocs.data }

    // --- plumbing ---

    private fun request(path: String) = request(base.newBuilder().addPathSegments(path).build())

    private fun request(url: HttpUrl) = Request.Builder()
        .url(url)
        .header("Authorization", auth)
        .header("OCS-APIRequest", "true")
        .header("Accept", "application/json")

    /** Body and ETag, or null on 304. */
    private fun get(path: String, ifNoneMatch: String? = null): Pair<String, String?>? =
        get(base.newBuilder().addPathSegments(path).build(), ifNoneMatch)

    private fun get(url: HttpUrl, ifNoneMatch: String? = null): Pair<String, String?>? {
        val req = request(url).apply { if (ifNoneMatch != null) header("If-None-Match", ifNoneMatch) }.get().build()
        http.newCall(req).execute().use { resp ->
            if (resp.code == 304) return null
            val body = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw DeckException(resp.code, body.take(300))
            return body to resp.header("ETag")
        }
    }

    private fun send(method: String, path: String, payload: JsonObject? = null): String {
        val body = (payload ?: JsonObject(emptyMap())).toString().toRequestBody(JSON)
        val req = request(path).method(method, body).build()
        http.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw DeckException(resp.code, text.take(300))
            return text
        }
    }

    private fun Instant?.toJson(): JsonElement = this?.let { JsonPrimitive(ISO.format(it)) } ?: JsonNull

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val ISO: DateTimeFormatter = DateTimeFormatter.ISO_OFFSET_DATE_TIME.withZone(ZoneOffset.UTC)
    }
}
