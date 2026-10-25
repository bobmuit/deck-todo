package app.vinkje.importer

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import app.vinkje.deck.LoginCredentials
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermissions

/**
 * What goes where, edited by hand between `scan` and `run`. Graph knows no list
 * groups, so every list says which group (board) it lands in.
 */
@Serializable
data class Plan(
    /** The zone To Do's due days are read in. */
    val zone: String = "Europe/Amsterdam",
    /** Ticked tasks come along, under "Voltooid", as in To Do. */
    val importCompleted: Boolean = true,
    val groups: List<PlanGroup> = emptyList(),
    val lists: List<PlanList> = emptyList(),
)

@Serializable
data class PlanGroup(
    val title: String,
    /** The account whose Nextcloud login creates (and owns) the board. */
    val owner: String,
    /** Accounts, or Nextcloud display names, who get full access to the board. */
    val shareWith: List<String> = emptyList(),
    /** Add the lists to an existing board of the same title that the import did not make; off, so other Deck boards are left alone. */
    val mergeExisting: Boolean = false,
)

@Serializable
data class PlanList(
    /** The To Do account the list is read from. */
    val account: String,
    val graphId: String,
    val title: String,
    /** A title from [Plan.groups], or null for a list outside any group (on the account's own "Lijsten" board). */
    val group: String? = null,
    val skip: Boolean = false,
    /** Written by `scan`, for the person editing the plan; ignored by `run`. */
    val info: String = "",
)

/** Graph id → Deck id, so a second run adds and updates instead of duplicating. */
@Serializable
data class ImportState(
    /** "account/board title" → board id */
    val boards: MutableMap<String, Long> = mutableMapOf(),
    /** Graph list id → stack */
    val lists: MutableMap<String, ListRef> = mutableMapOf(),
    /** Graph task id → card */
    val tasks: MutableMap<String, TaskRef> = mutableMapOf(),
)

@Serializable
data class ListRef(val boardId: Long, val stackId: Long)

@Serializable
data class TaskRef(
    val boardId: Long,
    val stackId: Long,
    val cardId: Long,
    /** The task's lastModifiedDateTime when it was last written to Deck. */
    val graphModified: String?,
    /** The card's lastModified right after that write, to tell whether someone changed it in Deck since; 0 = unknown. */
    val deckModified: Long = 0,
)

/** The refresh token of one Microsoft account. */
@Serializable
data class MicrosoftLogin(val clientId: String, val tenant: String, val refreshToken: String, val who: String = "")

/** Plan, state and logins in one directory; logins are readable by the owner only. */
class Store(val dir: Path) {
    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true; encodeDefaults = true }

    val planFile: Path = dir.resolve("plan.json")
    fun stateFile(rehearsal: Boolean): Path = dir.resolve(if (rehearsal) "state-rehearsal.json" else "state.json")
    private fun microsoftFile(account: String) = dir.resolve("$account.microsoft.json")
    private fun nextcloudFile(account: String) = dir.resolve("$account.nextcloud.json")

    fun plan(): Plan? = read(planFile)
    fun savePlan(plan: Plan) = write(planFile, json.encodeToString(plan), secret = false)

    fun state(rehearsal: Boolean): ImportState = read(stateFile(rehearsal)) ?: ImportState()
    fun saveState(rehearsal: Boolean, state: ImportState) = write(stateFile(rehearsal), json.encodeToString(state), secret = false)

    fun microsoft(account: String): MicrosoftLogin? = read(microsoftFile(account))
    fun saveMicrosoft(account: String, login: MicrosoftLogin) = write(microsoftFile(account), json.encodeToString(login), secret = true)

    fun nextcloud(account: String): LoginCredentials? = read(nextcloudFile(account))
    fun saveNextcloud(account: String, login: LoginCredentials) = write(nextcloudFile(account), json.encodeToString(login), secret = true)

    fun forget(account: String) {
        Files.deleteIfExists(microsoftFile(account))
        Files.deleteIfExists(nextcloudFile(account))
    }

    /** Accounts that have signed in to Microsoft. */
    fun microsoftAccounts(): List<String> = accounts(".microsoft.json")

    private fun accounts(suffix: String): List<String> =
        if (!Files.isDirectory(dir)) emptyList()
        else Files.list(dir).use { s -> s.map { it.fileName.toString() }.filter { it.endsWith(suffix) }.map { it.removeSuffix(suffix) }.sorted().toList() }

    private inline fun <reified T> read(file: Path): T? =
        if (Files.exists(file)) json.decodeFromString<T>(Files.readString(file)) else null

    /** Written next to the target and renamed over it, so a crash never leaves half a file. */
    private fun write(file: Path, text: String, secret: Boolean) {
        Files.createDirectories(dir, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")))
        val tmp = file.resolveSibling(file.fileName.toString() + ".tmp")
        Files.deleteIfExists(tmp)
        Files.createFile(tmp, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString(if (secret) "rw-------" else "rw-r-----")))
        Files.writeString(tmp, text)
        Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
