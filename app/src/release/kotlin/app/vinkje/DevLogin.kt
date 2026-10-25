package app.vinkje

import android.content.Context
import app.vinkje.deck.LoginCredentials

/** Release builds: no shortcut, only the real Login Flow. */
object DevLogin {
    @Suppress("UNUSED_PARAMETER")
    fun take(context: Context): LoginCredentials? = null
}

/** Release builds: your own changes are never news. */
object DevNotify {
    @Suppress("UNUSED_PARAMETER")
    fun pretendUid(context: Context): String? = null
}
