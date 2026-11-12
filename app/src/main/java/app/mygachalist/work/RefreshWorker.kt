package app.mygachalist.work

import android.content.Context
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import app.mygachalist.MyGachaListApp
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit

/**
 * Every ~6 hours: try to refresh the cached data, then raise release-day / pre-registration alerts.
 * No network constraint on purpose: a release-day alert must still fire from cached data while offline.
 */
class RefreshWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val repo = (applicationContext as MyGachaListApp).repository
        return try {
            repo.seedIfEmpty()
            repo.refresh()
            Notifier.checkAndNotify(applicationContext, repo, repo.prefs.settings.first())
            Result.success()
        } catch (_: Exception) {
            Result.success() // Never retry-storm on a low-end phone; the next period will try again.
        }
    }

    companion object {
        private const val NAME = "refresh"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<RefreshWorker>(6, TimeUnit.HOURS, 1, TimeUnit.HOURS)
                .setConstraints(Constraints.Builder().setRequiresBatteryNotLow(true).build())
                .build()
            WorkManager.getInstance(context)
                .enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
