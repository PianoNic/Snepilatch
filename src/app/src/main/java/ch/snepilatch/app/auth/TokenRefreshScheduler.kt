package ch.snepilatch.app.auth

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import ch.snepilatch.app.util.LokiLogger

/**
 * Schedules a Doze-tolerant alarm that wakes the device to refresh the
 * Spotify access token. Inexact `setAndAllowWhileIdle` is fine because we
 * always schedule LEAD_MS=15min ahead of the actual expiry — even the
 * worst-case Doze fudging stays inside that window.
 *
 * No `SCHEDULE_EXACT_ALARM` permission required; this is deliberate so
 * users don't have to grant a privileged alarm permission for a comfort
 * feature.
 */
object TokenRefreshScheduler {

    private const val TAG = "TokenRefreshScheduler"
    private const val REQUEST_CODE = 0xC0FFEE

    /** Schedule LEAD_MS before the actual expiry — keeps us inside the
     *  Doze inexact-alarm fudging window. */
    private const val LEAD_MS = 15L * 60L * 1000L

    /** Minimum delay even if expiry is near; never schedule something
     *  that would fire in the past. */
    private const val MIN_DELAY_MS = 30L * 1000L

    fun enable(context: Context, expiresAtMs: Long) {
        val triggerAt = (expiresAtMs - LEAD_MS).coerceAtLeast(System.currentTimeMillis() + MIN_DELAY_MS)
        val pi = pendingIntent(context, create = true) ?: run {
            LokiLogger.w(TAG, "Could not build PendingIntent for token refresh alarm")
            return
        }
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: run {
            LokiLogger.w(TAG, "AlarmManager unavailable, cannot schedule background refresh")
            return
        }
        try {
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, triggerAt, pi)
            val mins = (triggerAt - System.currentTimeMillis()) / 60_000
            LokiLogger.i(TAG, "Background token refresh scheduled in ~${mins}m (expires at $expiresAtMs)")
        } catch (e: SecurityException) {
            // Some OEM ROMs restrict alarm scheduling — fall back to logging.
            LokiLogger.w(TAG, "setAndAllowWhileIdle rejected by platform: ${e.message?.take(120)}")
        }
    }

    fun disable(context: Context) {
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val pi = pendingIntent(context, create = false) ?: return
        am.cancel(pi)
        pi.cancel()
        LokiLogger.i(TAG, "Background token refresh cancelled")
    }

    private fun pendingIntent(context: Context, create: Boolean): PendingIntent? {
        val intent = Intent(context, TokenRefreshAlarmReceiver::class.java)
        val flags = PendingIntent.FLAG_IMMUTABLE or
            (if (create) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE)
        return PendingIntent.getBroadcast(context.applicationContext, REQUEST_CODE, intent, flags)
    }
}
