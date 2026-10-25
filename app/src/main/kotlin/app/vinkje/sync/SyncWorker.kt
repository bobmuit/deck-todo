package app.vinkje.sync

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.vinkje.TodoApp
import app.vinkje.deck.DeckException
import java.io.IOException
import java.util.concurrent.TimeUnit

class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val app = applicationContext as TodoApp
        val engine = app.syncEngine() ?: return Result.success() // not logged in
        return try {
            engine.run()?.let { app.scheduler.kick(delayMs = it) }
            Result.success()
        } catch (e: DeckException) {
            if (e.status != 401) return Result.retry()
            // Revoked app password: retrying cannot help; the home screen asks to log in again.
            app.loginExpired()
            Result.failure()
        } catch (e: IOException) {
            Result.retry() // offline or server unreachable; the outbox keeps everything
        }
    }
}

/**
 * Sync runs after every local change (debounced), when the app opens, and every
 * 15 minutes; WorkManager holds each run until there is a network.
 */
class SyncScheduler(context: Context) {
    private val work = WorkManager.getInstance(context)
    private val online = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    /** REPLACE debounces a burst of edits into one run; SyncEngine makes each push step cancellation-safe. */
    fun kick(delayMs: Long = 1500) {
        work.enqueueUniqueWork(
            "sync",
            ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<SyncWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .setConstraints(online)
                .build(),
        )
    }

    fun schedulePeriodic() {
        work.enqueueUniquePeriodicWork(
            "sync-periodic",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<SyncWorker>(15, TimeUnit.MINUTES).setConstraints(online).build(),
        )
    }

    fun stopPeriodic() = work.cancelUniqueWork("sync-periodic")

    fun cancelAll() = work.cancelAllWork()
}
