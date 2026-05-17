package ch.snepilatch.app.auth

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import ch.snepilatch.app.playback.SessionHolder
import ch.snepilatch.app.util.LokiLogger
import java.util.concurrent.TimeUnit

/**
 * Periodic background token refresh. WorkManager survives process death and
 * reboots; the OS batches periodic work into Doze maintenance windows so we
 * don't need a wakelock or exact-alarm permission. Aggressive OEM battery
 * managers (Samsung Deep Sleep after a few days unused, Xiaomi MIUI, etc.)
 * will still suppress this — accepted tradeoff for a comfort feature.
 *
 * If a live in-memory session exists we use it; otherwise the worker is a
 * no-op for this tick and returns success so WorkManager keeps the chain
 * alive for the next interval.
 */
class TokenRefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {

    override suspend fun doWork(): Result {
        val session = SessionHolder.session
        if (session == null) {
            LokiLogger.i(TAG, "Worker fired but no live session; skipping this tick")
            return Result.success()
        }
        return try {
            session.baseClient.refreshAccessToken()
            LokiLogger.i(TAG, "Background token refresh succeeded")
            Result.success()
        } catch (e: Exception) {
            LokiLogger.w(TAG, "Background token refresh failed: ${e.message?.take(120)}")
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "TokenRefreshWorker"
        const val WORK_NAME = "token_refresh_periodic"

        private const val REPEAT_MINUTES = 30L
        private const val FLEX_MINUTES = 10L

        fun enable(context: Context) {
            val request = PeriodicWorkRequestBuilder<TokenRefreshWorker>(
                REPEAT_MINUTES, TimeUnit.MINUTES,
                FLEX_MINUTES, TimeUnit.MINUTES,
            ).build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
            LokiLogger.i(TAG, "Background token refresh enqueued (every ~${REPEAT_MINUTES}m)")
        }

        fun disable(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
            LokiLogger.i(TAG, "Background token refresh cancelled")
        }
    }
}
