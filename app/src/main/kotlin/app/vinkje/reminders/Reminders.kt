package app.vinkje.reminders

import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import app.vinkje.MainActivity
import app.vinkje.R
import app.vinkje.TodoApp
import app.vinkje.data.TodoDao

/**
 * To Do's "Herinner mij": a local alarm per task. Reminders are personal and never
 * reach Deck. Nothing is cancelled when a task is ticked or deleted; the receiver
 * checks the task is still open and still wants this exact time before it notifies.
 */
class Reminders(private val context: Context) {
    private val alarms = context.getSystemService(AlarmManager::class.java)

    fun schedule(taskId: Long, atEpochSecond: Long?) {
        val intent = Intent(context, ReminderReceiver::class.java)
            .putExtra(EXTRA_TASK, taskId)
            .putExtra(EXTRA_AT, atEpochSecond ?: 0)
        val pending = PendingIntent.getBroadcast(context, taskId.toInt(), intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        if (atEpochSecond == null) {
            alarms.cancel(pending)
            return
        }
        val at = atEpochSecond * 1000
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S || alarms.canScheduleExactAlarms()) {
            alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        } else {
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending)
        }
    }

    /** Alarms do not survive a reboot or an app update. Reminders already past are not repeated. */
    suspend fun rescheduleAll(dao: TodoDao) {
        val now = System.currentTimeMillis() / 1000
        dao.tasksWithReminder().filter { it.reminderEpochSecond!! > now }.forEach { schedule(it.id, it.reminderEpochSecond) }
    }

    fun createChannel() {
        val channel = NotificationChannel(CHANNEL, "Herinneringen", NotificationManager.IMPORTANCE_HIGH)
        context.getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    fun show(taskId: Long, title: String, listTitle: String?) {
        val open = PendingIntent.getActivity(
            context, taskId.toInt(),
            Intent(context, MainActivity::class.java).putExtra(EXTRA_TASK, taskId).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val complete = PendingIntent.getBroadcast(
            context, taskId.toInt(),
            Intent(context, CompleteReceiver::class.java).putExtra(EXTRA_TASK, taskId),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(title)
            .setContentText(listTitle)
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_REMINDER)
            .addAction(0, "Voltooid", complete)
            .build()
        runCatching { NotificationManagerCompat.from(context).notify(taskId.toInt(), notification) } // SecurityException without permission
    }

    fun dismiss(taskId: Long) = NotificationManagerCompat.from(context).cancel(taskId.toInt())

    companion object {
        const val CHANNEL = "reminders"
        const val EXTRA_TASK = "taskId"
        const val EXTRA_AT = "at"

        /** Receivers finish their database work on this scope (inside goAsync's window). */
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TodoApp
        val taskId = intent.getLongExtra(Reminders.EXTRA_TASK, -1)
        val at = intent.getLongExtra(Reminders.EXTRA_AT, 0)
        val result = goAsync()
        Reminders.scope.launch {
            try {
                val task = app.db.dao().task(taskId)
                if (task != null && !task.deleted && task.doneEpochSecond == null && task.reminderEpochSecond == at) {
                    app.reminders.show(task.id, task.title, app.db.dao().listNow(task.listId)?.title)
                }
            } finally {
                result.finish()
            }
        }
    }
}

/** The "Voltooid" button on a reminder: ticks the task off (spawning the next one if it repeats). */
class CompleteReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TodoApp
        val taskId = intent.getLongExtra(Reminders.EXTRA_TASK, -1)
        val result = goAsync()
        Reminders.scope.launch {
            try {
                app.repository.complete(taskId)
                app.reminders.dismiss(taskId)
            } finally {
                result.finish()
            }
        }
    }
}

/** Puts the alarms back after a reboot or an app update. */
class RescheduleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val app = context.applicationContext as TodoApp
        val result = goAsync()
        Reminders.scope.launch {
            try {
                app.reminders.rescheduleAll(app.db.dao())
            } finally {
                result.finish()
            }
        }
    }
}
