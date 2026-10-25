package app.vinkje.data

import android.content.SharedPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Who is logged in, as Deck knows them: the uid that assignments and activities use. */
class Session(private val prefs: SharedPreferences) {
    private val _selfUid = MutableStateFlow(prefs.getString(KEY_SELF, null))
    val selfUid: StateFlow<String?> get() = _selfUid

    private val _loginExpired = MutableStateFlow(prefs.getBoolean(KEY_EXPIRED, false))

    /** Nextcloud refused the app password (revoked, or the account was closed); local changes wait in the outbox. */
    val loginExpired: StateFlow<Boolean> get() = _loginExpired

    /**
     * Whose lists are on this phone, as "loginName@server". Kept apart from the encrypted
     * credentials, so a lost Keystore key does not make the same person look like someone else.
     */
    var account: String?
        get() = prefs.getString(KEY_ACCOUNT, null)
        set(value) = prefs.edit().putString(KEY_ACCOUNT, value).apply()

    fun setSelf(uid: String) {
        if (uid == _selfUid.value) return
        prefs.edit().putString(KEY_SELF, uid).apply()
        _selfUid.value = uid
    }

    fun setLoginExpired(expired: Boolean) {
        prefs.edit().putBoolean(KEY_EXPIRED, expired).apply()
        _loginExpired.value = expired
    }

    /** After the prefs file was cleared, for logging out. */
    fun reset() {
        _selfUid.value = null
        _loginExpired.value = false
    }

    private companion object {
        const val KEY_SELF = "selfUid"
        const val KEY_EXPIRED = "loginExpired"
        const val KEY_ACCOUNT = "account"
    }
}
