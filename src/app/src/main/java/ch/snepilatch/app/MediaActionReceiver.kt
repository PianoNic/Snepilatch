package ch.snepilatch.app

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MediaActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val svc = MusicPlaybackService.instance ?: return
        // Only forward to Spotify — ExoPlayer will sync via state callbacks
        when (intent.action) {
            "ch.snepilatch.app.PREV" -> svc.onSkipPrevious?.invoke()
            "ch.snepilatch.app.PLAY_PAUSE" -> {
                if (svc.player.isPlaying) {
                    svc.onPause?.invoke()
                } else {
                    svc.onPlay?.invoke()
                }
            }
            "ch.snepilatch.app.NEXT" -> svc.onSkipNext?.invoke()
        }
    }
}
