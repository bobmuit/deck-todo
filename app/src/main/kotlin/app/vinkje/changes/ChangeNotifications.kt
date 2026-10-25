package app.vinkje.changes

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import app.vinkje.MainActivity
import app.vinkje.R
import app.vinkje.domain.ChangeKind
import app.vinkje.reminders.Reminders
import app.vinkje.sync.ChangeSink
import app.vinkje.sync.OthersChange

/**
 * One notification that collects what the other person changed since you last looked:
 * "Anna · Melk toegevoegd aan Boodschappen". Opening the app clears it; while the app
 * is on screen nothing is posted, because the lists update in front of you anyway.
 */
class ChangeNotifications(private val context: Context, private val inForeground: () -> Boolean) : ChangeSink {
    private val prefs = context.getSharedPreferences("changes", Context.MODE_PRIVATE)

    @Serializable
    private data class Line(val taskId: Long, val text: String, val actor: String?)

    override fun post(changes: List<OthersChange>) {
        if (inForeground()) return
        // A repeating task ticked off elsewhere comes back as a new one: telling both is noise.
        val completed = changes.filter { it.kind == ChangeKind.COMPLETED }.map { it.title to it.listTitle }.toSet()
        val fresh = changes.filterNot { it.kind == ChangeKind.ADDED && (it.title to it.listTitle) in completed }
            .map { Line(it.taskId, describe(it), it.actor) }
        val lines = (stored() + fresh).takeLast(MAX_LINES)
        prefs.edit().putString(KEY_LINES, json.encodeToString(lines)).apply()
        show(lines)
    }

    /** Called when the app opens: everything is in view now. */
    fun clear() {
        prefs.edit().remove(KEY_LINES).apply()
        NotificationManagerCompat.from(context).cancel(TAG, 0)
    }

    fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "Wijzigingen door anderen", NotificationManager.IMPORTANCE_DEFAULT)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    private fun show(lines: List<Line>) {
        val actors = lines.map { it.actor }.distinct()
        val single = actors.singleOrNull()
        val title = single ?: if (actors.all { it != null }) actors.joinToString(" en ") else "Wijzigingen"
        val one = lines.singleOrNull()
        val open = Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (one != null) open.putExtra(Reminders.EXTRA_TASK, one.taskId)
        val inbox = NotificationCompat.InboxStyle()
        lines.takeLast(INBOX_LINES).forEach { inbox.addLine(if (single != null || it.actor == null) it.text else "${it.actor}: ${it.text}") }
        if (lines.size > INBOX_LINES) inbox.setSummaryText("en nog ${lines.size - INBOX_LINES}")

        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(one?.text ?: "${lines.size} wijzigingen")
            .setStyle(if (one == null) inbox else NotificationCompat.BigTextStyle().bigText(one.text))
            .setNumber(lines.size)
            .setContentIntent(PendingIntent.getActivity(context, REQUEST, open, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
            .setDeleteIntent(PendingIntent.getBroadcast(context, REQUEST, Intent(context, ChangesDismissedReceiver::class.java), PendingIntent.FLAG_IMMUTABLE))
            .setAutoCancel(true)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(TAG, 0, notification) } // SecurityException without permission
    }

    private fun stored(): List<Line> =
        prefs.getString(KEY_LINES, null)?.let { runCatching { json.decodeFromString<List<Line>>(it) }.getOrNull() }.orEmpty()

    internal fun forget() = prefs.edit().remove(KEY_LINES).apply()

    companion object {
        const val CHANNEL = "changes"
        private const val TAG = "changes"
        private const val KEY_LINES = "lines"
        private const val MAX_LINES = 30
        private const val INBOX_LINES = 6
        private const val REQUEST = -1
        private val json = Json { ignoreUnknownKeys = true }

        fun describe(change: OthersChange): String = when (change.kind) {
            ChangeKind.ADDED -> "${change.title} toegevoegd aan ${change.listTitle}"
            ChangeKind.COMPLETED -> "${change.title} afgevinkt"
            ChangeKind.REOPENED -> "${change.title} weer open gezet"
            ChangeKind.ASSIGNED_TO_ME -> "${change.title} aan jou toegewezen"
            ChangeKind.EDITED -> "${change.title} gewijzigd"
        }
    }
}

/** Swiping the notification away: start the next one afresh. */
class ChangesDismissedReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        ChangeNotifications(context) { false }.forget()
    }
}
