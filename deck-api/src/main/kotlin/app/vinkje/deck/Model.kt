package app.vinkje.deck

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/*
 * Wire models for the Deck REST API v1.0. Only the fields the app uses are
 * declared; everything else is ignored. User ids on our instance are OIDC
 * hashes, so the app compares on [User.uid] and shows [User.displayname].
 */

@Serializable
data class User(
    val uid: String,
    val displayname: String = uid,
)

@Serializable
data class Label(
    val id: Long,
    val title: String,
    val color: String = "",
)

@Serializable
data class Permissions(
    @SerialName("PERMISSION_READ") val read: Boolean = false,
    @SerialName("PERMISSION_EDIT") val edit: Boolean = false,
    @SerialName("PERMISSION_MANAGE") val manage: Boolean = false,
    @SerialName("PERMISSION_SHARE") val share: Boolean = false,
)

@Serializable
data class Acl(
    val id: Long,
    val participant: User,
    /** 0 = user, 1 = group, 7 = circle. */
    val type: Int = 0,
    val permissionEdit: Boolean = false,
    val permissionShare: Boolean = false,
    val permissionManage: Boolean = false,
)

@Serializable
data class Board(
    val id: Long,
    val title: String,
    val color: String = "",
    val archived: Boolean = false,
    val owner: User,
    val labels: List<Label> = emptyList(),
    val acl: List<Acl> = emptyList(),
    val permissions: Permissions = Permissions(),
    val deletedAt: Long = 0,
    val lastModified: Long = 0,
    @SerialName("ETag") val etag: String = "",
)

@Serializable
data class Stack(
    val id: Long,
    val title: String,
    val boardId: Long,
    val order: Int = 0,
    val deletedAt: Long = 0,
    val lastModified: Long = 0,
    val cards: List<Card>? = null,
    @SerialName("ETag") val etag: String = "",
)

@Serializable
data class Assignment(
    val participant: User,
)

@Serializable
data class Card(
    val id: Long,
    val title: String,
    val stackId: Long,
    val type: String = "plain",
    val order: Int = 0,
    val archived: Boolean = false,
    /** A uid string in most responses, a [User] object in some; see [ownerUid]. */
    val owner: JsonElement? = null,
    val description: String? = null,
    /** ISO-8601 timestamps, null when unset. */
    val duedate: String? = null,
    val done: String? = null,
    val labels: List<Label>? = null,
    val assignedUsers: List<Assignment>? = null,
    val deletedAt: Long = 0,
    val lastModified: Long = 0,
    val createdAt: Long = 0,
    @SerialName("ETag") val etag: String = "",
) {
    val ownerUid: String?
        get() = when (val o = owner) {
            is JsonPrimitive -> o.contentOrNull
            is JsonObject -> o["uid"]?.jsonPrimitive?.contentOrNull
            else -> null
        }

    val isDone: Boolean get() = done != null
}

/** One entry of the Nextcloud activity stream; [user] is who made the change. */
@Serializable
data class Activity(
    @SerialName("activity_id") val id: Long,
    val user: String = "",
    @SerialName("object_type") val objectType: String = "",
    @SerialName("object_id") val objectId: Long = 0,
)

@Serializable
internal data class OcsEnvelope<T>(val ocs: OcsBody<T>)

@Serializable
internal data class OcsBody<T>(val data: T)

@Serializable
internal data class OcsUser(val id: String, val displayname: String = id)

@Serializable
internal data class OcsAutocomplete(val id: String, val label: String = id, val source: String = "")
