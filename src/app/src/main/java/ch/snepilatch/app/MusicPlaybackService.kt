package ch.snepilatch.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.audiofx.AudioEffect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.os.Bundle
import androidx.core.app.NotificationCompat
import androidx.media.MediaBrowserServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URL

class MusicPlaybackService : MediaBrowserServiceCompat() {

    companion object {
        private const val TAG = "MusicService"
        private const val CHANNEL_ID = "music_playback"
        private const val NOTIFICATION_ID = 1
        var instance: MusicPlaybackService? = null
            private set
        // Shared PlayerConnect — survives Activity/ViewModel recreation
        var sharedPlayer: kotify.api.playerconnect.PlayerConnect? = null
        var sharedSession: kotify.session.Session? = null
    }

    private var mediaSession: MediaSessionCompat? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    lateinit var player: ExoPlayer
        private set

    private data class TrackMetadata(
        val title: String,
        val artist: String,
        val albumArtUrl: String?,
        var art: Bitmap? = null
    )

    private val metadataQueue = mutableListOf<TrackMetadata>()
    private var currentTitle = ""
    private var currentArtist = ""
    private var currentArt: Bitmap? = null
    private var currentAudioSessionId: Int = 0
    private var openAudioEffectSession = false

    // Callbacks for forwarding controls to Spotify
    var onPlay: (() -> Unit)? = null
    var onPause: (() -> Unit)? = null
    var onSkipNext: (() -> Unit)? = null
    var onSkipPrevious: (() -> Unit)? = null
    var onSeek: ((Long) -> Unit)? = null
    var onTrackTransition: (() -> Unit)? = null
    var onPlaybackError: ((String) -> Unit)? = null
    var onPlaybackEnded: (() -> Unit)? = null
    var onLikeToggle: (() -> Unit)? = null
    var onShuffleToggle: (() -> Unit)? = null
    var onRepeatToggle: (() -> Unit)? = null

    // Notification custom button state
    var isLiked: Boolean = false
    var isShuffling: Boolean = false
    var repeatMode: String = "off"  // "off", "context", "track"
    // Which extra buttons to show: "like", "shuffle", "repeat"
    var notificationLeftButton: String = "repeat"
    var notificationRightButton: String = "like"

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        val audioAttributes = AudioAttributes.Builder()
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .setUsage(C.USAGE_MEDIA)
            .build()

        val loadControl = DefaultLoadControl.Builder()
            .setBufferDurationsMs(
                30_000,   // min buffer: 30s
                120_000,  // max buffer: 2 min
                1_500,    // playback start buffer: 1.5s
                3_000     // rebuffer: 3s
            )
            .setPrioritizeTimeOverSizeThresholds(true)
            .build()

        player = ExoPlayer.Builder(this)
            .setAudioAttributes(audioAttributes, true)
            .setLoadControl(loadControl)
            .build()

        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                updateNotification()
            }

            override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                LokiLogger.e(TAG, "Playback error: ${error.errorCodeName} - ${error.message}", error)
                onPlaybackError?.invoke(error.errorCodeName ?: "unknown")
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                updateNotification()
                if (playbackState == Player.STATE_ENDED) {
                    LokiLogger.i(TAG, "Playback ended (STATE_ENDED)")
                    onPlaybackEnded?.invoke()
                }
            }

            override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
                // Follow Auxio convention: open/close audio effect session on play/pause
                currentAudioSessionId = player.audioSessionId
                if (playWhenReady) {
                    if (!openAudioEffectSession) {
                        LokiLogger.i(TAG, "Opening audio effect session (audioSessionId=$currentAudioSessionId)")
                        broadcastAudioEffectAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                        openAudioEffectSession = true
                    }
                } else if (openAudioEffectSession) {
                    LokiLogger.i(TAG, "Closing audio effect session")
                    broadcastAudioEffectAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                    openAudioEffectSession = false
                }
            }

            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
                if (reason == Player.MEDIA_ITEM_TRANSITION_REASON_AUTO && metadataQueue.size > 1) {
                    // ExoPlayer auto-advanced to next track — swap metadata
                    metadataQueue.removeAt(0)
                    val next = metadataQueue.firstOrNull()
                    if (next != null) {
                        currentTitle = next.title
                        currentArtist = next.artist
                        currentArt = next.art
                        updateMediaSessionMetadata()
                        updateNotification()
                        LokiLogger.i(TAG, "Auto-transition to: ${next.title} by ${next.artist}")
                    }
                    onTrackTransition?.invoke()
                }
            }

            override fun onAudioSessionIdChanged(audioSessionId: Int) {
                LokiLogger.i(TAG, "Audio session ID changed: $currentAudioSessionId -> $audioSessionId, playWhenReady=${player.playWhenReady}, openSession=$openAudioEffectSession")
                val oldId = currentAudioSessionId
                currentAudioSessionId = audioSessionId
                if (audioSessionId != 0 && player.playWhenReady) {
                    if (openAudioEffectSession && oldId != audioSessionId) {
                        // Session changed mid-play, close old and open new
                        broadcastAudioEffectAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
                    }
                    broadcastAudioEffectAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION)
                    openAudioEffectSession = true
                }
            }
        })

        // MediaSession
        val sessionIntent = packageManager.getLaunchIntentForPackage(packageName)?.apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this, 0, sessionIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        mediaSession = MediaSessionCompat(this, "KotifyMedia").apply {
            setSessionActivity(pendingIntent)
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    // Only tell Spotify — ExoPlayer will sync via onPlay callback
                    onPlay?.invoke()
                }

                override fun onPause() {
                    // Only tell Spotify — ExoPlayer will sync via onPause callback
                    onPause?.invoke()
                }

                override fun onSkipToNext() {
                    onSkipNext?.invoke()
                }

                override fun onSkipToPrevious() {
                    onSkipPrevious?.invoke()
                }

                override fun onSeekTo(pos: Long) {
                    // Tell Spotify, and also seek ExoPlayer immediately for responsiveness
                    player.seekTo(pos)
                    onSeek?.invoke(pos)
                    updateNotification()
                }

                override fun onStop() {
                    player.stop()
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }

                override fun onCustomAction(action: String?, extras: Bundle?) {
                    val buttonType = when (action) {
                        "LEFT_ACTION" -> notificationLeftButton
                        "RIGHT_ACTION" -> notificationRightButton
                        else -> return
                    }
                    when (buttonType) {
                        "like" -> onLikeToggle?.invoke()
                        "shuffle" -> onShuffleToggle?.invoke()
                        "repeat" -> onRepeatToggle?.invoke()
                    }
                }
            })
            isActive = true
        }

        // Register session token with the system so Wavelet/other apps can discover it
        sessionToken = mediaSession!!.sessionToken

        instance = this

        // Start foreground immediately with a placeholder notification
        startForeground(NOTIFICATION_ID, buildNotification())
        LokiLogger.i(TAG, "Service created with MediaBrowserServiceCompat")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    var onReady: (() -> Unit)? = null

    fun playUrl(url: String, title: String, artist: String, albumArtUrl: String?, startPlaying: Boolean = true) {
        LokiLogger.i(TAG, "Loading: $title by $artist -> ${url.take(80)} (play=$startPlaying)")
        val meta = TrackMetadata(title, artist, albumArtUrl)
        metadataQueue.clear()
        metadataQueue.add(meta)
        currentTitle = title
        currentArtist = artist

        // Start audio IMMEDIATELY — don't wait for art
        mainHandler.post {
            player.playWhenReady = false
            player.setMediaItem(buildMediaItem(url))

            if (startPlaying) {
                // Register listener BEFORE prepare() so we catch STATE_READY
                player.addListener(object : Player.Listener {
                    override fun onPlaybackStateChanged(playbackState: Int) {
                        if (playbackState == Player.STATE_READY) {
                            player.removeListener(this)
                            player.playWhenReady = true
                            updateNotification()
                            LokiLogger.i(TAG, "Stream ready, playback started")
                            onReady?.invoke()
                        }
                    }
                })
            }

            player.prepare()
            updateMediaSessionMetadata()
            updateNotification()
        }

        // Load art in background, update notification when ready
        serviceScope.launch {
            val bitmap = albumArtUrl?.let { loadBitmap(it) }
            meta.art = bitmap
            currentArt = bitmap
            mainHandler.post {
                updateMediaSessionMetadata()
                updateNotification()
            }
        }
    }

    fun syncPlay(positionMs: Long) {
        mainHandler.post {
            if (player.mediaItemCount > 0) {
                player.seekTo(positionMs)
                player.play()
                updateNotification()
            }
        }
    }

    fun syncPause() {
        mainHandler.post {
            if (player.mediaItemCount > 0) {
                player.pause()
                updateNotification()
            }
        }
    }

    fun getCurrentPosition(): Long = player.currentPosition
    fun isPlaying(): Boolean = player.isPlaying

    fun syncSeek(positionMs: Long) {
        mainHandler.post {
            if (player.mediaItemCount > 0) {
                player.seekTo(positionMs)
                updateNotification()
            }
        }
    }

    fun stop() {
        metadataQueue.clear()
        mainHandler.post {
            player.stop()
            player.clearMediaItems()
        }
    }

    fun setNextUrl(url: String, title: String, artist: String, albumArtUrl: String?) {
        LokiLogger.i(TAG, "Next: $title by $artist")
        val meta = TrackMetadata(title, artist, albumArtUrl)

        // Keep only the current track's metadata, replace any queued next
        while (metadataQueue.size > 1) {
            metadataQueue.removeAt(metadataQueue.lastIndex)
        }
        metadataQueue.add(meta)

        // Prefetch album art in background
        serviceScope.launch {
            meta.art = albumArtUrl?.let { loadBitmap(it) }
        }

        mainHandler.post {
            if (player.mediaItemCount > 1) {
                player.removeMediaItems(1, player.mediaItemCount)
            }
            player.addMediaItem(buildMediaItem(url))
        }
    }

    private fun buildMediaItem(url: String): MediaItem {
        return MediaItem.Builder()
            .setMediaId(url)
            .setUri(url)
            .build()
    }

    fun buildDrmMediaItem(url: String, licenseUrl: String, licenseHeaders: Map<String, String>): MediaItem {
        return MediaItem.Builder()
            .setMediaId(url)
            .setUri(url)
            .setDrmConfiguration(
                MediaItem.DrmConfiguration.Builder(C.WIDEVINE_UUID)
                    .setLicenseUri(licenseUrl)
                    .setLicenseRequestHeaders(licenseHeaders)
                    .build()
            )
            .build()
    }

    fun playDrmUrl(url: String, licenseUrl: String, licenseHeaders: Map<String, String>,
                   title: String, artist: String, albumArtUrl: String?,
                   startPlaying: Boolean = true,
                   startPositionMs: Long = 0L) {
        LokiLogger.i(TAG, "Loading DRM: $title by $artist -> ${url.take(80)} (play=$startPlaying, pos=${startPositionMs}ms)")
        val meta = TrackMetadata(title, artist, albumArtUrl)
        metadataQueue.clear()
        metadataQueue.add(meta)
        currentTitle = title
        currentArtist = artist

        // Start audio IMMEDIATELY — don't wait for art
        mainHandler.post {
            // Seek-on-load: setMediaItem with a startPositionMs makes ExoPlayer prepare,
            // seek, and reach STATE_READY at that exact position. No post-prepare seek
            // dance — eliminates the race where the post-ready seek fails to actually
            // produce audio (the bug in the cold-start sync).
            player.setMediaItem(buildDrmMediaItem(url, licenseUrl, licenseHeaders), startPositionMs)
            player.playWhenReady = startPlaying
            // Register listener BEFORE prepare() so we catch STATE_READY
            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY) {
                        player.removeListener(this)
                        LokiLogger.i(TAG, "DRM stream ready (playWhenReady=$startPlaying, pos=${player.currentPosition}ms)")
                        onReady?.invoke()
                    }
                }
            })
            player.prepare()
            updateMediaSessionMetadata()
            updateNotification()
        }

        // Load art in background
        serviceScope.launch {
            val bitmap = albumArtUrl?.let { loadBitmap(it) }
            meta.art = bitmap
            currentArt = bitmap
            mainHandler.post {
                updateMediaSessionMetadata()
                updateNotification()
            }
        }
    }

    fun updateMetadata(title: String, artist: String, albumArtUrl: String?) {
        currentTitle = title
        currentArtist = artist
        serviceScope.launch {
            currentArt = albumArtUrl?.let { loadBitmap(it) }
            mainHandler.post {
                updateMediaSessionMetadata()
                updateNotification()
            }
        }
    }

    private fun updateMediaSessionMetadata() {
        val duration = if (player.duration > 0) player.duration else 0L
        val metadata = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, currentTitle)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, currentArtist)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, duration)
            .apply { currentArt?.let { putBitmap(MediaMetadataCompat.METADATA_KEY_ALBUM_ART, it) } }
            .build()
        mediaSession?.setMetadata(metadata)
    }

    private fun updatePlaybackState() {
        val state = when {
            player.isPlaying -> PlaybackStateCompat.STATE_PLAYING
            player.playbackState == Player.STATE_BUFFERING -> PlaybackStateCompat.STATE_BUFFERING
            player.playWhenReady -> PlaybackStateCompat.STATE_PAUSED
            else -> PlaybackStateCompat.STATE_PAUSED
        }
        val builder = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                PlaybackStateCompat.ACTION_PAUSE or
                PlaybackStateCompat.ACTION_PLAY_PAUSE or
                PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                PlaybackStateCompat.ACTION_SEEK_TO or
                PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, player.currentPosition, 1f)

        // Add custom actions for left and right buttons
        fun addButtonAction(type: String, actionName: String) {
            when (type) {
                "like" -> {
                    val icon = if (isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
                    builder.addCustomAction(actionName, if (isLiked) "Unlike" else "Like", icon)
                }
                "shuffle" -> {
                    val icon = if (isShuffling) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle_off
                    builder.addCustomAction(actionName, "Shuffle", icon)
                }
                "repeat" -> {
                    val icon = when (repeatMode) {
                        "track" -> R.drawable.ic_repeat_one
                        "context" -> R.drawable.ic_repeat_on
                        else -> R.drawable.ic_repeat_off
                    }
                    builder.addCustomAction(actionName, "Repeat", icon)
                }
            }
        }
        addButtonAction(notificationLeftButton, "LEFT_ACTION")
        addButtonAction(notificationRightButton, "RIGHT_ACTION")

        mediaSession?.setPlaybackState(builder.build())
    }

    fun updateNotification() {
        updatePlaybackState()
        updateMediaSessionMetadata()
        val nm = getSystemService(NotificationManager::class.java)
        nm.notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val isPlaying = player.isPlaying

        // Actions
        val prevIntent = PendingIntent.getBroadcast(
            this, 0,
            Intent("ch.snepilatch.app.PREV"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val playPauseIntent = PendingIntent.getBroadcast(
            this, 1,
            Intent("ch.snepilatch.app.PLAY_PAUSE"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val nextIntent = PendingIntent.getBroadcast(
            this, 2,
            Intent("ch.snepilatch.app.NEXT"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val leftIntent = PendingIntent.getBroadcast(
            this, 3,
            Intent("ch.snepilatch.app.LEFT_ACTION"),
            PendingIntent.FLAG_IMMUTABLE
        )
        val rightIntent = PendingIntent.getBroadcast(
            this, 4,
            Intent("ch.snepilatch.app.RIGHT_ACTION"),
            PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseIcon = if (isPlaying)
            android.R.drawable.ic_media_pause
        else
            android.R.drawable.ic_media_play

        fun buttonIcon(type: String) = when (type) {
            "like" -> if (isLiked) R.drawable.ic_heart_filled else R.drawable.ic_heart_outline
            "shuffle" -> if (isShuffling) R.drawable.ic_shuffle_on else R.drawable.ic_shuffle_off
            "repeat" -> when (repeatMode) {
                "track" -> R.drawable.ic_repeat_one
                "context" -> R.drawable.ic_repeat_on
                else -> R.drawable.ic_repeat_off
            }
            else -> R.drawable.ic_heart_outline
        }
        fun buttonLabel(type: String) = when (type) {
            "like" -> if (isLiked) "Unlike" else "Like"
            "shuffle" -> "Shuffle"
            "repeat" -> "Repeat"
            else -> "Like"
        }

        val sessionToken = mediaSession?.sessionToken

        // Layout: [left] [prev] [play/pause] [next] [right]
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(currentTitle.ifEmpty { "Snepilatch" })
            .setContentText(currentArtist)
            .setLargeIcon(currentArt)
            .setOngoing(isPlaying)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .addAction(buttonIcon(notificationLeftButton), buttonLabel(notificationLeftButton), leftIntent)
            .addAction(android.R.drawable.ic_media_previous, "Previous", prevIntent)
            .addAction(playPauseIcon, if (isPlaying) "Pause" else "Play", playPauseIntent)
            .addAction(android.R.drawable.ic_media_next, "Next", nextIntent)
            .addAction(buttonIcon(notificationRightButton), buttonLabel(notificationRightButton), rightIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(sessionToken)
                    .setShowActionsInCompactView(1, 2, 3)
            )
            .setContentIntent(mediaSession?.controller?.sessionActivity)
            .build()
    }

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Music Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = "Shows current playing track"
            setShowBadge(false)
        }
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(channel)
    }

    private suspend fun loadBitmap(url: String): Bitmap? = withContext(Dispatchers.IO) {
        try {
            URL(url).openStream().use { BitmapFactory.decodeStream(it) }
        } catch (e: Exception) {
            LokiLogger.e(TAG, "Failed to load art: $url", e)
            null
        }
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        // App swiped from recents — kill everything
        player.stop()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun broadcastAudioEffectAction(action: String) {
        if (currentAudioSessionId == 0) return
        val pkg = packageName ?: "ch.snepilatch.app"
        LokiLogger.i(TAG, "Broadcasting $action: pkg=$pkg, sessionId=$currentAudioSessionId")
        sendBroadcast(
            Intent(action)
                .putExtra(AudioEffect.EXTRA_PACKAGE_NAME, pkg)
                .putExtra(AudioEffect.EXTRA_AUDIO_SESSION, currentAudioSessionId)
                .putExtra(AudioEffect.EXTRA_CONTENT_TYPE, AudioEffect.CONTENT_TYPE_MUSIC)
        )
    }

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot? {
        return BrowserRoot("empty_root", null)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<android.support.v4.media.MediaBrowserCompat.MediaItem>>) {
        result.sendResult(mutableListOf())
    }

    override fun onDestroy() {
        if (openAudioEffectSession) {
            broadcastAudioEffectAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            openAudioEffectSession = false
        }
        mediaSession?.run {
            isActive = false
            release()
        }
        player.release()
        // Clean up shared references
        sharedPlayer = null
        sharedSession = null
        instance = null
        super.onDestroy()
    }
}
