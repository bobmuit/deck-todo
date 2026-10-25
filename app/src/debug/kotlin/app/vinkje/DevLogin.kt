package app.vinkje

import android.content.Context
import kotlinx.serialization.json.Json
import app.vinkje.deck.LoginCredentials
import java.io.File

/**
 * Debug builds only: lets the emulator be logged in without a browser login.
 *
 *     adb push dev-credentials.json /data/local/tmp/
 *     adb shell run-as app.vinkje cp /data/local/tmp/dev-credentials.json files/
 *
 * The file is read once at start-up, moved into the Keystore-backed store and deleted.
 */
object DevLogin {
    fun take(context: Context): LoginCredentials? {
        val file = File(context.filesDir, "dev-credentials.json")
        if (!file.exists()) return null
        return try {
            Json { ignoreUnknownKeys = true }.decodeFromString<LoginCredentials>(file.readText())
        } finally {
            file.delete()
        }
    }
}

/**
 * Debug builds only: notify about your own changes as if someone else made them, to test
 * the change notifications with one account. Any uid that is not yours works:
 *
 *     adb shell "echo nobody | run-as app.vinkje sh -c 'cat > files/dev-notify-as'"
 */
object DevNotify {
    fun pretendUid(context: Context): String? =
        File(context.filesDir, "dev-notify-as").takeIf { it.exists() }?.readText()?.trim()?.takeIf { it.isNotEmpty() }
}
