package ch.snepilatch.app.playback

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.view.KeyEvent
import ch.snepilatch.app.MainActivity
import ch.snepilatch.app.util.LokiLogger
import ch.snepilatch.app.util.loadCookies

/**
 * Receives hardware media-button intents (Bluetooth headphones, wired headset
 * button, car infotainment) when the app process is cold.
 *
 * When the system routes `ACTION_MEDIA_BUTTON` here with a KEYCODE_MEDIA_PLAY
 * (or PLAY_PAUSE / HEADSETHOOK), we launch MainActivity with an autoplay flag.
 * The activity drives the normal ViewModel initialization path, then triggers
 * the same cold-start play protocol a user tap would. That flashes the UI
 * briefly on headphone press — the same thing the official Spotify app does.
 *
 * We only do this when the user has saved cookies, i.e. they're logged in.
 * Otherwise there's nothing to resume and launching the activity would just
 * dump them on the login screen, which is worse than doing nothing.
 */
class MediaButtonReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_MEDIA_BUTTON) return
        val keyEvent: KeyEvent? = @Suppress("DEPRECATION")
            intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT)
        if (keyEvent == null || keyEvent.action != KeyEvent.ACTION_DOWN) return
        val code = keyEvent.keyCode
        val isPlayKey = code == KeyEvent.KEYCODE_MEDIA_PLAY ||
                code == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE ||
                code == KeyEvent.KEYCODE_HEADSETHOOK
        if (!isPlayKey) return

        // If the service is already running, let the existing MediaSession path
        // handle it — we only exist to wake the process from cold.
        if (MusicPlaybackService.instance != null) {
            LokiLogger.d(TAG, "Service alive, skipping cold-launch path")
            return
        }

        // Need saved cookies to bootstrap — otherwise we'd just land the user
        // on the login screen, which isn't what they wanted.
        val cookies = try { loadCookies(context) } catch (_: Exception) { null }
        if (cookies == null) {
            LokiLogger.d(TAG, "No saved cookies — ignoring cold media-button press")
            return
        }

        LokiLogger.i(TAG, "Cold media-button press (keyCode=$code), launching MainActivity with autoplay")
        val launch = Intent(context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(EXTRA_AUTO_PLAY, true)
        }
        context.startActivity(launch)
    }

    companion object {
        private const val TAG = "MediaButtonReceiver"
        const val EXTRA_AUTO_PLAY = "ch.snepilatch.app.EXTRA_AUTO_PLAY"
    }
}
