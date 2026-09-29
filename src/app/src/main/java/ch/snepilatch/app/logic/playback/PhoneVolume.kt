package ch.snepilatch.app.logic.playback

import android.content.Context
import android.database.ContentObserver
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import ch.snepilatch.app.logic.shared.SessionHolder
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * The phone's media volume as Connect sees it. Registration reports it (so other clients do not show
 * full volume and jump the phone on their first slider move), and changes made on the phone itself are
 * reported as they happen, as the web player does for its own slider.
 */
object PhoneVolume {

    private const val FULL = 65535

    /** The media volume as 0.0 to 1.0. */
    fun fraction(context: Context): Double {
        val am = context.getSystemService(AudioManager::class.java) ?: return 1.0
        val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: return 1.0
        return am.getStreamVolume(AudioManager.STREAM_MUSIC).toDouble() / max
    }

    /**
     * Reports volume key and slider changes until [unregister]. Settings.System changes for every volume
     * stream, so only a media volume that differs from what the phone last told the others is sent; that
     * also drops the echo of a volume another client just set.
     */
    fun observe(context: Context, scope: CoroutineScope): ContentObserver {
        val app = context.applicationContext
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                val pc = SessionHolder.player ?: return
                val am = app.getSystemService(AudioManager::class.java) ?: return
                val max = am.getStreamMaxVolume(AudioManager.STREAM_MUSIC).takeIf { it > 0 } ?: return
                val step = am.getStreamVolume(AudioManager.STREAM_MUSIC)
                // Another client's volume lands on the step PlaybackViewModel.setVolume picked for it.
                if ((pc.deviceVolume.toDouble() / FULL * max).toInt() == step) return
                scope.launch { runCatching { pc.reportVolume(step.toDouble() / max) } }
            }
        }
        app.contentResolver.registerContentObserver(Settings.System.CONTENT_URI, true, observer)
        return observer
    }

    fun unregister(context: Context, observer: ContentObserver) {
        context.applicationContext.contentResolver.unregisterContentObserver(observer)
    }
}
