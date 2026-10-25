package app.vinkje

import android.app.Application
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import app.vinkje.changes.ChangeNotifications
import app.vinkje.data.AppDatabase
import app.vinkje.data.Session
import app.vinkje.data.TodoRepository
import app.vinkje.deck.DeckClient
import app.vinkje.deck.LoginCredentials
import app.vinkje.reminders.Reminders
import app.vinkje.sync.CredentialStore
import app.vinkje.sync.SyncEngine
import app.vinkje.sync.SyncScheduler
import app.vinkje.sync.syncLock

/** Hand-wired dependencies; the app is small enough not to need a DI framework. */
class TodoApp : Application() {
    val db by lazy { AppDatabase.create(this) }
    val scheduler by lazy { SyncScheduler(this) }
    val reminders by lazy { Reminders(this) }
    val session by lazy { Session(getSharedPreferences("sync", Context.MODE_PRIVATE)) }
    val changes by lazy { ChangeNotifications(this) { foreground } }
    val repository by lazy { TodoRepository(db.dao(), scheduler, reminders, session, getSharedPreferences("ui", Context.MODE_PRIVATE), deck = ::client) }

    /** Set by MainActivity while it is on screen. */
    @Volatile var foreground = false
    private val credentialStore by lazy { CredentialStore(this) }

    private val _credentials = MutableStateFlow<LoginCredentials?>(null)
    val credentials: StateFlow<LoginCredentials?> get() = _credentials

    /** Nextcloud refused the app password; the lists keep working here until the next login. */
    val loginExpired: StateFlow<Boolean> get() = session.loginExpired

    override fun onCreate() {
        super.onCreate()
        reminders.createChannel()
        changes.createChannel()
        // Force-stopping the app also clears its alarms; putting them back is cheap.
        Reminders.scope.launch { reminders.rescheduleAll(db.dao()) }
        DevLogin.take(this)?.let { credentialStore.save(it); session.setLoginExpired(false) }
        _credentials.value = credentialStore.load()?.also { c ->
            // Installs from before 0.1.1 did not record whose lists these are.
            if (session.account == null) session.account = accountOf(c)
            if (!session.loginExpired.value) scheduler.schedulePeriodic()
        }
    }

    /**
     * Logging in again as the same person keeps the lists and the changes that have not
     * reached Nextcloud yet; anyone else starts from what is in Nextcloud.
     */
    suspend fun logIn(credentials: LoginCredentials) {
        if (session.account != accountOf(credentials)) wipeLocal()
        session.account = accountOf(credentials)
        credentialStore.save(credentials)
        session.setLoginExpired(false)
        _credentials.value = credentials
        scheduler.schedulePeriodic()
        scheduler.kick(delayMs = 0)
    }

    /** Called by the sync worker on a 401. */
    fun loginExpired() {
        session.setLoginExpired(true)
        scheduler.stopPeriodic()
    }

    /**
     * Back to the login screen with nothing of this account left on the phone. The app
     * password is revoked too when Nextcloud is reachable; otherwise it can still be revoked
     * under Nextcloud → Instellingen → Beveiliging.
     */
    fun logOut() {
        val client = client().takeUnless { session.loginExpired.value }
        scheduler.cancelAll()
        session.account = null
        credentialStore.clear()
        _credentials.value = null
        Reminders.scope.launch {
            wipeLocal()
            withTimeoutOrNull(10_000) { runCatching { runInterruptible { client?.revokeAppPassword() } } }
        }
    }

    /** Waits for a sync round still running, so it cannot write the old account's data back. */
    private suspend fun wipeLocal() = syncLock.withLock {
        withContext(Dispatchers.IO) {
            db.dao().tasksWithReminder().forEach { reminders.schedule(it.id, null); reminders.dismiss(it.id) }
            db.clearAllTables()
            getSharedPreferences("sync", Context.MODE_PRIVATE).edit().clear().commit()
            session.reset()
            // A list id from before would point at someone else's list after the wipe.
            getSharedPreferences("ui", Context.MODE_PRIVATE).edit().remove("defaultList").commit()
            changes.clear()
        }
    }

    private fun accountOf(c: LoginCredentials) = "${c.loginName}@${c.server.trimEnd('/')}"

    fun client(): DeckClient? = _credentials.value?.let { DeckClient(it.server, it.loginName, it.appPassword) }

    fun syncEngine(): SyncEngine? {
        if (session.loginExpired.value) return null
        return SyncEngine(
            dao = db.dao(),
            client = client() ?: return null,
            prefs = getSharedPreferences("sync", Context.MODE_PRIVATE),
            session = session,
            changes = changes,
            notifyAs = { DevNotify.pretendUid(this) ?: session.selfUid.value },
            adoptReminder = repository::setReminder,
        )
    }
}
