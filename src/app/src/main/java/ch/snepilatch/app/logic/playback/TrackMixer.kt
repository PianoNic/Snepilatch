package ch.snepilatch.app.logic.playback

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.PlayerMessage
import ch.snepilatch.app.logic.shared.AppSettings
import ch.snepilatch.app.logic.shared.LokiLogger
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Crossfade between songs played from files (#1075); 0 seconds is off, the reference client's default.
 * Its own store rather than [AppSettings], which holds enough already.
 */
object CrossfadeSetting {
    private const val PREF = "crossfade_seconds"
    private val _seconds = MutableStateFlow(0)
    val seconds: StateFlow<Int> = _seconds.asStateFlow()

    fun load(context: Context) {
        _seconds.value = context.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE).getInt(PREF, 0)
    }

    fun set(seconds: Int, context: Context) {
        _seconds.value = seconds
        context.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE).edit().putInt(PREF, seconds).apply()
    }
}

/**
 * The mix (#1075). The next track plays on a second player from the start of the overlap, and the two swap
 * roles there: the service's player is always the current track, so nothing else has to know, and the old
 * song goes on untouched as the tail, with no gap at the swap. Only one of them holds audio focus.
 */
class TrackMixer(private val handler: Handler, private val looper: Looper, private val host: Host) {

    /** What the next track shows while it plays. */
    class Next(val title: String, val artist: String, val art: String?)

    interface Host {
        val current: ExoPlayer
        fun mediaItem(url: String): MediaItem

        /** The player that is not playing, built on first use. */
        fun spare(): ExoPlayer

        /** Makes [incoming] the current player, and returns the one it replaced, which keeps playing. */
        fun swapTo(incoming: ExoPlayer): ExoPlayer

        /** The new track is now current: its title, art and the playback callbacks. */
        fun nowPlaying(next: Next)
    }

    private var tail: ExoPlayer? = null
    private var messages: List<PlayerMessage> = emptyList()
    private var ticker: Runnable? = null
    private var tailMs = 0L

    /**
     * Mix into [url] for the last [overlapMs] of the current track of [trackMs]. [onStart] runs when the
     * overlap starts. A next track not ready by then means no mix: the song ends as usual and the normal
     * advance takes over.
     */
    fun arm(url: String, next: Next, trackMs: Long, overlapMs: Long, onStart: () -> Unit) {
        handler.post {
            cancelMessages()
            val startAt = trackMs - overlapMs
            if (startAt <= 0 || overlapMs <= 0) return@post
            val spare = host.spare()
            // The spare is the old song's player until its tail is over, so it is not loaded before that.
            val tailEndsAt = if (tail != null) tailMs else 0L
            messages = listOf(
                host.current.createMessage { _, _ ->
                    // Loaded silent: the spare may be the last mix's old song, still set to play.
                    spare.playWhenReady = false
                    spare.setMediaItem(host.mediaItem(url))
                    spare.prepare()
                }.setLooper(looper).setPosition(minOf(maxOf(startAt - PRELOAD_MS, tailEndsAt), startAt)).send(),
                host.current.createMessage { _, _ ->
                    start(spare, next, overlapMs, onStart)
                }.setLooper(looper).setPosition(startAt).send(),
            )
        }
    }

    /** No mix ahead, but one that is running plays out: nothing comes after the song now starting. */
    fun disarm() {
        handler.post(::cancelMessages)
    }

    /** No mix ahead and none running: a skip, or Connect taking over. */
    fun cancel() {
        handler.post {
            cancelMessages()
            endTail()
        }
    }

    /** Pause and resume reach the outgoing song too. */
    fun playWhenReadyChanged(playWhenReady: Boolean) {
        tail?.playWhenReady = playWhenReady
    }

    fun release() {
        ticker?.let(handler::removeCallbacks)
        tail = null
    }

    private fun start(incoming: ExoPlayer, next: Next, overlapMs: Long, onStart: () -> Unit) {
        if (incoming.playbackState != Player.STATE_READY) {
            LokiLogger.w(TAG, "Next track not ready for the mix, the song ends as usual")
            return
        }
        LokiLogger.i(TAG, "Mixing into ${next.title} over ${overlapMs}ms")
        val outgoing = host.swapTo(incoming)
        tail = outgoing
        tailMs = overlapMs
        incoming.volume = MixCurve.FADE_IN.at(0f)
        incoming.playWhenReady = true
        onStart()
        host.nowPlaying(next)
        val tick = object : Runnable {
            override fun run() {
                val x = incoming.currentPosition.toFloat() / overlapMs
                if (x >= 1f) {
                    endTail()
                    return
                }
                incoming.volume = MixCurve.FADE_IN.at(x)
                outgoing.volume = MixCurve.FADE_OUT.at(x)
                handler.postDelayed(this, TICK_MS)
            }
        }
        ticker = tick
        handler.post(tick)
    }

    private fun endTail() {
        ticker?.let(handler::removeCallbacks)
        ticker = null
        tail?.apply {
            playWhenReady = false
            stop()
            clearMediaItems()
            volume = 1f
        }
        tail = null
        host.current.volume = 1f
    }

    private fun cancelMessages() {
        messages.forEach { it.cancel() }
        messages = emptyList()
        host.spare().takeIf { it !== tail }?.apply {
            stop()
            clearMediaItems()
        }
    }

    private companion object {
        const val TAG = "TrackMixer"

        /** The next file is opened this long before the overlap, so it is ready when it starts. */
        const val PRELOAD_MS = 10_000L
        const val TICK_MS = 30L
    }
}
