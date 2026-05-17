package ch.snepilatch.app.auth

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import ch.snepilatch.app.playback.SessionHolder
import ch.snepilatch.app.util.LokiLogger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * Wakes the process during Doze to refresh the Spotify access token.
 *
 * Triggered by [TokenRefreshScheduler] via `setAndAllowWhileIdle`. We use
 * `goAsync()` so the receiver stays alive while the HTTP refresh runs;
 * the platform grants ~10 seconds of wake time which is plenty for a
 * single token-refresh request. After the refresh attempt (success or
 * failure) we reschedule against the new expiry timestamp so the chain
 * keeps going as long as the user has the toggle on.
 */
class TokenRefreshAlarmReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent?) {
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val session = SessionHolder.session
                if (session == null) {
                    LokiLogger.w(TAG, "Alarm fired but session is null; not rescheduling")
                    return@launch
                }
                try {
                    session.baseClient.refreshAccessToken()
                    LokiLogger.i(TAG, "Background token refresh succeeded")
                } catch (e: Exception) {
                    LokiLogger.w(TAG, "Background token refresh failed: ${e.message?.take(120)}")
                    // Reschedule anyway — transient network failure shouldn't
                    // break the chain. If sp_dc is dead the next attempt will
                    // also fail; user will be prompted to re-login by the
                    // normal flow.
                }
                val nextExpiresAt = session.baseClient.accessTokenExpirationTimestampMs
                if (nextExpiresAt != null) {
                    TokenRefreshScheduler.enable(appContext, nextExpiresAt)
                } else {
                    LokiLogger.w(TAG, "No expiry timestamp after refresh; not rescheduling")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    companion object {
        private const val TAG = "TokenRefreshAlarm"
    }
}
