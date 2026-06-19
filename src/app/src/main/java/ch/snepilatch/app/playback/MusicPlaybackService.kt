package ch.snepilatch.app.playback

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.audiofx.AudioEffect
import android.net.ConnectivityManager
import android.net.Network
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.support.v4.media.MediaBrowserCompat
import android.support.v4.media.MediaDescriptionCompat
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import android.os.Bundle
import androidx.annotation.OptIn
import androidx.core.app.NotificationCompat
import ch.snepilatch.app.R
import ch.snepilatch.app.util.LokiLogger
import androidx.media.MediaBrowserServiceCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.DefaultHttpDataSource
import androidx.media3.datasource.RawResourceDataSource
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.MediaSource
import androidx.media3.exoplayer.source.ProgressiveMediaSource
import kotify.api.album.Album
import kotify.api.playlist.Playlist
import kotify.api.song.Song
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

        // Android Auto browse tree
        private const val BROWSE_ROOT = "root"
        private const val LIKED_SONGS_URI = "spotify:collection:tracks"

        // Top-level category nodes — Android Auto renders root-level browsable items as tabs.
        private const val CAT_PLAYLISTS = "cat:playlists"
        private const val CAT_ALBUMS = "cat:albums"
        private const val CAT_LIKED = "cat:liked"
        private const val CAT_RECENT = "cat:recent"

        // mediaId prefixes so onPlayFromMediaId knows how to act on a tapped item.
        // PLAY_PREFIX + contextUri plays a whole context; TRACK_PREFIX + "trackUri|contextUri"
        // plays a single track within its context so the queue continues.
        private const val PLAY_PREFIX = "play:"
        private const val TRACK_PREFIX = "track:"

        // Android Auto content-style hints (androidx.media doesn't expose these as constants).
        private const val CONTENT_STYLE_SUPPORTED = "android.media.browse.CONTENT_STYLE_SUPPORTED"
        private const val CONTENT_STYLE_BROWSABLE_HINT = "android.media.browse.CONTENT_STYLE_BROWSABLE_HINT"
        private const val CONTENT_STYLE_PLAYABLE_HINT = "android.media.browse.CONTENT_STYLE_PLAYABLE_HINT"
        private const val CONTENT_STYLE_LIST = 1
        private const val CONTENT_STYLE_GRID = 2
        private const val NOTIFICATION_ID = 1
        var instance: MusicPlaybackService? = null
            private set
    }

    private var mediaSession: MediaSessionCompat? = null
    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /** Loopback proxy that decrypts Blowfish-encrypted Deezer streams on the fly. */
    private val deezerProxy = DeezerDecryptProxy()
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
    private var currentDurationMs: Long = 0L

    /**
     * The album-art URL most recently requested by setIdleMetadata. Used to
     * discard stale background loads when the track changes before art arrives.
     */
    private var idleArtUrl: String? = null
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

    /** True while playback is paused because another app holds transient audio focus.
     *  Used to auto-resume Spotify when focus returns. */
    private var wasSuppressedByFocus = false

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        deezerProxy.start()
        registerNetworkCallback()

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

                // Full (permanent) audio focus loss — e.g. phone call answered.
                // ExoPlayer flips playWhenReady to false. Mirror to Spotify side.
                if (!playWhenReady && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS) {
                    LokiLogger.i(TAG, "Pausing Spotify side due to audio focus loss")
                    onPause?.invoke()
                }
            }

            override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
                // Transient focus loss (e.g. Instagram video starts) — ExoPlayer keeps
                // playWhenReady = true but suppresses playback. Spotify's cloud position
                // keeps advancing though, so we must mirror the pause to the Spotify side
                // — otherwise the muted track ends and the next one auto-plays over the
                // focus-stealing app. Resume both sides when focus returns.
                when (playbackSuppressionReason) {
                    Player.PLAYBACK_SUPPRESSION_REASON_TRANSIENT_AUDIO_FOCUS_LOSS -> {
                        LokiLogger.i(TAG, "Pausing Spotify side due to transient focus loss")
                        wasSuppressedByFocus = true
                        onPause?.invoke()
                    }
                    Player.PLAYBACK_SUPPRESSION_REASON_NONE -> {
                        if (wasSuppressedByFocus) {
                            wasSuppressedByFocus = false
                            LokiLogger.i(TAG, "Resuming Spotify side — transient focus restored")
                            onPlay?.invoke()
                        }
                    }
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

                override fun onPlayFromMediaId(mediaId: String?, extras: Bundle?) {
                    if (mediaId != null) playFromMediaId(mediaId)
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

    fun playUrl(
        url: String,
        title: String,
        artist: String,
        albumArtUrl: String?,
        startPlaying: Boolean = true,
        headers: Map<String, String> = emptyMap(),
        startPositionMs: Long = 0L
    ) {
        LokiLogger.i(TAG, "Loading: $title by $artist -> ${url.take(80)} (play=$startPlaying, headers=${headers.keys}, pos=${startPositionMs}ms)")
        val meta = TrackMetadata(title, artist, albumArtUrl)
        metadataQueue.clear()
        metadataQueue.add(meta)
        currentTitle = title
        currentArtist = artist

        // Start audio IMMEDIATELY — don't wait for art
        mainHandler.post {
            player.playWhenReady = false
            // Default sources (squid direct URL, Spotify CDN) play from a plain
            // MediaItem. Sources that gate their stream behind a request header
            // (the anandserver Qobuz mirror) need those headers on the HTTP data
            // source, so they go through a dedicated header-injecting MediaSource.
            // Both accept a start position so resume-from-idle seeks on load.
            if (headers.isEmpty()) {
                player.setMediaItem(buildMediaItem(url), startPositionMs)
            } else {
                player.setMediaSource(buildHeaderedSource(url, headers), startPositionMs)
            }

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

    /**
     * Play the bundled silent clip while an ad is being skipped. This is the native equivalent of
     * uBlock's 1s-silent-mp4 substitution: KotifyClient signals ads via `onAd` (no ad audio is ever
     * fetched) and clocks them out in ~1s, advancing to the next real track on its own. Loading the
     * silent clip here keeps the MediaSession/notification "playing" (no idle gap) and lets the UI
     * show a "Skipping ad…" placeholder. The next real track's `setMediaItem` replaces this clip.
     */
    @OptIn(UnstableApi::class)
    fun playSilentAd() {
        LokiLogger.i(TAG, "Ad — playing local silent clip (skipping)")
        val meta = TrackMetadata("Skipping ad…", "", null)
        metadataQueue.clear()
        metadataQueue.add(meta)
        currentTitle = "Skipping ad…"
        currentArtist = ""
        currentArt = null
        val uri = RawResourceDataSource.buildRawResourceUri(ch.snepilatch.app.R.raw.silent_ad)
        mainHandler.post {
            val source = ProgressiveMediaSource.Factory(DefaultDataSource.Factory(this))
                .createMediaSource(MediaItem.fromUri(uri))
            player.setMediaSource(source)
            player.playWhenReady = true
            player.prepare()
            updateMediaSessionMetadata()
            updateNotification()
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
        currentDurationMs = 0L
        idleArtUrl = null
        mainHandler.post {
            player.stop()
            player.clearMediaItems()
        }
    }

    /**
     * Push idle metadata to the notification + MediaSession without loading
     * anything into ExoPlayer. Used right after init to surface whatever
     * track Spotify Connect's cluster reports as "current", so the system
     * media notification shows the correct song before the user has pressed
     * play. Pressing play / next / pause from the notification then runs the
     * normal cold-start protocol.
     *
     * Skipped if a track is already loaded (we don't want to overwrite live
     * playback metadata).
     */
    fun setIdleMetadata(title: String, artist: String, albumArtUrl: String?, durationMs: Long) {
        if (player.mediaItemCount > 0) return
        currentTitle = title
        currentArtist = artist
        currentDurationMs = durationMs
        // Track the most recent idle art URL so we can ignore stale callbacks
        // and only render the *current* track's art.
        val expectedUrl = albumArtUrl
        idleArtUrl = expectedUrl
        if (expectedUrl == null) {
            currentArt = null
            mainHandler.post {
                updateMediaSessionMetadata()
                updateNotification()
            }
            return
        }
        // Update text + duration immediately; load art async and apply only if
        // it's still the current idle URL when it returns.
        mainHandler.post {
            updateMediaSessionMetadata()
            updateNotification()
        }
        serviceScope.launch {
            val bitmap = loadBitmap(expectedUrl)
            if (idleArtUrl == expectedUrl) {
                currentArt = bitmap
                mainHandler.post {
                    updateMediaSessionMetadata()
                    updateNotification()
                }
            }
        }
    }

    @OptIn(UnstableApi::class)
    fun setNextUrl(
        url: String,
        title: String,
        artist: String,
        albumArtUrl: String?,
        headers: Map<String, String> = emptyMap()
    ) {
        LokiLogger.i(TAG, "Next: $title by $artist (headers=${headers.keys})")
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
            // Header-gated sources (anandserver Qobuz) must be enqueued as a
            // header-injecting MediaSource, or the gapless advance hits a 401.
            if (headers.isEmpty()) {
                player.addMediaItem(buildMediaItem(url))
            } else {
                player.addMediaSource(buildHeaderedSource(url, headers))
            }
        }
    }

    private fun buildMediaItem(url: String): MediaItem {
        return MediaItem.Builder()
            .setMediaId(url)
            .setUri(url)
            .build()
    }

    /**
     * Play an encrypted Deezer stream. The bytes are Blowfish-encrypted, so we
     * register them with the loopback [deezerProxy] and hand ExoPlayer the
     * resulting cleartext localhost URL — no custom DataSource, no DRM config.
     */
    fun playDeezer(
        streamUrl: String,
        decryptionKey: String,
        headers: Map<String, String>,
        title: String,
        artist: String,
        albumArtUrl: String?,
        startPositionMs: Long = 0L
    ) {
        val localUrl = deezerProxy.register(streamUrl, decryptionKey, headers)
        playUrl(localUrl, title, artist, albumArtUrl, startPlaying = true, startPositionMs = startPositionMs)
    }

    /** Register an encrypted Deezer stream with the proxy; returns a playable local URL. */
    fun proxyUrlForDeezer(streamUrl: String, decryptionKey: String, headers: Map<String, String>): String =
        deezerProxy.register(streamUrl, decryptionKey, headers)

    /**
     * Progressive media source whose HTTP data source sends [headers] on every
     * request. Used only for stream URLs that are gated behind a request header
     * (the anandserver Qobuz mirror's X-API-Key) — the default [buildMediaItem]
     * path is left untouched for header-less sources. Timeouts are generous
     * because the relay can be slow to first byte.
     */
    @OptIn(UnstableApi::class)
    private fun buildHeaderedSource(url: String, headers: Map<String, String>): MediaSource {
        val httpFactory = DefaultHttpDataSource.Factory()
            .setDefaultRequestProperties(headers)
            .setAllowCrossProtocolRedirects(true)
            .setConnectTimeoutMs(30_000)
            .setReadTimeoutMs(30_000)
        return ProgressiveMediaSource.Factory(httpFactory)
            .createMediaSource(buildMediaItem(url))
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

    /**
     * Progressive media source that injects a Widevine PSSH (base64 from Spotify's seektable) into
     * the DRM session. Needed because many Spotify files are cenc-encrypted but embed no Widevine
     * pssh box, so ExoPlayer's default in-file DRM throws MissingSchemeDataException and plays silent.
     */
    @OptIn(UnstableApi::class)
    private fun buildPsshDrmSource(url: String, licenseUrl: String, licenseHeaders: Map<String, String>, psshBase64: String): MediaSource {
        val callback = androidx.media3.exoplayer.drm.HttpMediaDrmCallback(licenseUrl, DefaultHttpDataSource.Factory())
        licenseHeaders.forEach { (k, v) -> callback.setKeyRequestProperty(k, v) }
        val base = androidx.media3.exoplayer.drm.DefaultDrmSessionManager.Builder()
            .setUuidAndExoMediaDrmProvider(C.WIDEVINE_UUID, androidx.media3.exoplayer.drm.FrameworkMediaDrm.DEFAULT_PROVIDER)
            .setMultiSession(true)
            .build(callback)
        val psshBytes = android.util.Base64.decode(psshBase64, android.util.Base64.DEFAULT)
        val injecting = ch.snepilatch.app.playback.engine.PsshInjectingDrmSessionManager(base, psshBytes)
        return ProgressiveMediaSource.Factory(DefaultDataSource.Factory(this))
            .setDrmSessionManagerProvider { injecting }
            .createMediaSource(MediaItem.fromUri(url))
    }

    @OptIn(UnstableApi::class)
    @Suppress("LongParameterList")
    fun playDrmUrl(url: String, licenseUrl: String, licenseHeaders: Map<String, String>,
                   title: String, artist: String, albumArtUrl: String?,
                   startPlaying: Boolean = true,
                   startPositionMs: Long = 0L,
                   pssh: String? = null) {
        LokiLogger.i(TAG, "Loading DRM: $title by $artist -> ${url.take(80)} (play=$startPlaying, pos=${startPositionMs}ms, pssh=${pssh != null})")
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
            if (pssh != null) {
                player.setMediaSource(buildPsshDrmSource(url, licenseUrl, licenseHeaders, pssh), startPositionMs)
            } else {
                player.setMediaItem(buildDrmMediaItem(url, licenseUrl, licenseHeaders), startPositionMs)
            }
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
        // Prefer ExoPlayer's reported duration when a media item is loaded;
        // fall back to currentDurationMs which is set by setIdleMetadata so
        // the system shows the right duration before the song actually loads.
        val duration = when {
            player.duration > 0 -> player.duration
            currentDurationMs > 0 -> currentDurationMs
            else -> 0L
        }
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

        val playPauseIcon = if (isPlaying) R.drawable.ic_pause_rounded else R.drawable.ic_play_arrow_rounded

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
            .addAction(R.drawable.ic_skip_previous_rounded, "Previous", prevIntent)
            .addAction(playPauseIcon, if (isPlaying) "Pause" else "Play", playPauseIntent)
            .addAction(R.drawable.ic_skip_next_rounded, "Next", nextIntent)
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
            // Spotify's cluster API returns art as `spotify:image:<id>` URIs.
            // Rewrite to the i.scdn.co CDN URL before handing to URL().
            val resolved = if (url.startsWith("spotify:image:")) {
                "https://i.scdn.co/image/" + url.removePrefix("spotify:image:")
            } else url
            URL(resolved).openStream().use { BitmapFactory.decodeStream(it) }
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

    override fun onGetRoot(clientPackageName: String, clientUid: Int, rootHints: Bundle?): BrowserRoot {
        // The content is the signed-in user's own library, so we grant any caller (Android Auto,
        // Assistant, the app itself). Transport still runs through the validated MediaSession.
        // Advertise content-style support so Auto honours the per-node grid/list hints below.
        val extras = Bundle().apply {
            putBoolean(CONTENT_STYLE_SUPPORTED, true)
            putInt(CONTENT_STYLE_BROWSABLE_HINT, CONTENT_STYLE_GRID)
            putInt(CONTENT_STYLE_PLAYABLE_HINT, CONTENT_STYLE_LIST)
        }
        return BrowserRoot(BROWSE_ROOT, extras)
    }

    override fun onLoadChildren(parentId: String, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        // The root tabs are static and need no network, so answer synchronously.
        if (parentId == BROWSE_ROOT) {
            result.sendResult(buildRootTabs())
            return
        }
        // Everything below the root is a network call — detach and fill asynchronously.
        result.detach()
        serviceScope.launch {
            val items = try {
                withContext(Dispatchers.IO) { loadCategory(parentId) }
            } catch (e: Exception) {
                LokiLogger.e(TAG, "onLoadChildren failed for $parentId", e)
                mutableListOf()
            }
            result.sendResult(items)
        }
    }

    /** Top-level tabs shown across the Android Auto browse bar. */
    private fun buildRootTabs(): MutableList<MediaBrowserCompat.MediaItem> = mutableListOf(
        browsableItem(CAT_PLAYLISTS, "Playlists", null, null),
        browsableItem(CAT_ALBUMS, "Albums", null, null),
        browsableItem(CAT_LIKED, "Liked Songs", null, null),
        browsableItem(CAT_RECENT, "Recently Played", null, null),
    )

    /** Resolve the children of any non-root node: a category tab, or a playlist/album to drill into. */
    private suspend fun loadCategory(parentId: String): MutableList<MediaBrowserCompat.MediaItem> {
        val sess = SessionHolder.session ?: return mutableListOf()
        return when (parentId) {
            CAT_PLAYLISTS -> Playlist(sess).getLibrary(limit = 50).items
                .filter { it.uri.contains(":playlist:") }
                .map { browsableItem(it.uri, it.name, it.ownerName ?: "Playlist", it.imageUrl) }
                .toMutableList()

            CAT_ALBUMS -> Playlist(sess).getLibrary(limit = 50).items
                .filter { it.uri.contains(":album:") }
                .map { browsableItem(it.uri, it.name, it.ownerName ?: "Album", it.imageUrl) }
                .toMutableList()

            CAT_RECENT -> Playlist(sess).getLibrary(limit = 50).items
                .filter { it.uri.contains(":playlist:") || it.uri.contains(":album:") }
                .sortedByDescending { it.playedAt ?: "" }
                .take(20)
                .map { browsableItem(it.uri, it.name, it.ownerName ?: it.type, it.imageUrl) }
                .toMutableList()

            CAT_LIKED, LIKED_SONGS_URI -> {
                val out = mutableListOf(playAllItem(LIKED_SONGS_URI))
                Playlist(sess).getLikedSongs(limit = 100).items.forEach { t ->
                    out.add(trackItem(t.uri, LIKED_SONGS_URI, t.name, t.artists.joinToString(", ") { it.name }, t.album.coverArtUrl))
                }
                out
            }

            else -> when {
                parentId.contains(":playlist:") -> {
                    val info = Playlist(sess).getPlaylist(parentId.substringAfterLast(":"), limit = 100)
                    val out = mutableListOf(playAllItem(parentId))
                    info.tracks.forEach { t ->
                        out.add(trackItem(t.uri, parentId, t.name, t.artists.joinToString(", "), t.coverArtUrl))
                    }
                    out
                }
                parentId.contains(":album:") -> {
                    val info = Album(sess).getAlbum(parentId.substringAfterLast(":"), limit = 100)
                    val out = mutableListOf(playAllItem(parentId))
                    info.tracks.forEach { t ->
                        out.add(trackItem(t.uri, parentId, t.name, t.artists.joinToString(", "), info.coverArtUrl))
                    }
                    out
                }
                else -> mutableListOf()
            }
        }
    }

    override fun onSearch(query: String, extras: Bundle?, result: Result<MutableList<MediaBrowserCompat.MediaItem>>) {
        result.detach()
        serviceScope.launch {
            val items = try {
                withContext(Dispatchers.IO) {
                    val sess = SessionHolder.session ?: return@withContext mutableListOf()
                    Song(sess).search(query, limit = 30).tracks.items.map { t ->
                        // Search hits have no surrounding context — play the single track.
                        trackItem(t.uri, t.uri, t.name, t.artists.joinToString(", ") { it.name }, t.album.coverArtUrl)
                    }.toMutableList()
                }
            } catch (e: Exception) {
                LokiLogger.e(TAG, "onSearch failed for '$query'", e)
                mutableListOf()
            }
            result.sendResult(items)
        }
    }

    /** A node Auto can open to reveal its children (a category, playlist or album). */
    private fun browsableItem(mediaId: String, title: String, subtitle: String?, imageUrl: String?): MediaBrowserCompat.MediaItem {
        val desc = MediaDescriptionCompat.Builder()
            .setMediaId(mediaId)
            .setTitle(title)
            .setSubtitle(subtitle)
            .apply { imageUrl?.let { setIconUri(Uri.parse(it)) } }
            .build()
        return MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_BROWSABLE)
    }

    /** "Play all" entry at the top of a context, so the whole playlist/album can play in one tap. */
    private fun playAllItem(contextUri: String): MediaBrowserCompat.MediaItem {
        val desc = MediaDescriptionCompat.Builder()
            .setMediaId(PLAY_PREFIX + contextUri)
            .setTitle("▶ Play all")
            .build()
        return MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }

    /** A single playable track; tapping plays it within [contextUri] so the queue continues. */
    private fun trackItem(trackUri: String, contextUri: String, title: String, subtitle: String?, imageUrl: String?): MediaBrowserCompat.MediaItem {
        val desc = MediaDescriptionCompat.Builder()
            .setMediaId("$TRACK_PREFIX$trackUri|$contextUri")
            .setTitle(title)
            .setSubtitle(subtitle)
            .apply { imageUrl?.let { setIconUri(Uri.parse(it)) } }
            .build()
        return MediaBrowserCompat.MediaItem(desc, MediaBrowserCompat.MediaItem.FLAG_PLAYABLE)
    }

    /**
     * Act on a tapped Android Auto item. "play:<ctx>" plays a whole context from its first track;
     * "track:<trackUri>|<ctx>" plays a specific track inside its context (so the queue continues);
     * a bare context URI keeps the old whole-context behaviour. The dealer then drives the rest of
     * the pipeline (onTrackChange -> resolve -> ExoPlayer) exactly like an in-app tap. Requires a
     * live session — if the app process is cold this is a no-op (see the Auto cold-start note).
     */
    private fun playFromMediaId(mediaId: String) {
        serviceScope.launch {
            try {
                val sess = SessionHolder.session ?: return@launch
                val player = SessionHolder.player ?: return@launch
                if (mediaId.startsWith(TRACK_PREFIX)) {
                    val body = mediaId.removePrefix(TRACK_PREFIX)
                    val trackUri = body.substringBefore("|")
                    val contextUri = body.substringAfter("|", trackUri)
                    LokiLogger.i(TAG, "Auto: playing track $trackUri in $contextUri")
                    withContext(Dispatchers.IO) { player.playTrack(trackUri, contextUri) }
                    return@launch
                }
                val contextUri = mediaId.removePrefix(PLAY_PREFIX)
                val firstTrack = withContext(Dispatchers.IO) {
                    when {
                        contextUri == LIKED_SONGS_URI ->
                            Playlist(sess).getLikedSongs(limit = 1).items.firstOrNull()?.uri
                        contextUri.contains(":playlist:") ->
                            Playlist(sess).getPlaylist(contextUri.substringAfterLast(":"), limit = 1).tracks.firstOrNull()?.uri
                        contextUri.contains(":album:") ->
                            Album(sess).getAlbum(contextUri.substringAfterLast(":"), limit = 1).tracks.firstOrNull()?.uri
                        else -> null
                    }
                } ?: return@launch
                LokiLogger.i(TAG, "Auto: playing $contextUri from $firstTrack")
                withContext(Dispatchers.IO) { player.playTrack(firstTrack, contextUri) }
            } catch (e: Exception) {
                LokiLogger.e(TAG, "Auto play failed for $mediaId", e)
            }
        }
    }

    private var connectivityManager: ConnectivityManager? = null
    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        private var hadNetwork = false
        override fun onAvailable(network: Network) {
            // A new default network arrived. If we already had one, this is a handover (Wi-Fi<->cell)
            // that silently invalidates the dealer socket — reconnect now instead of waiting for the
            // keep-alive liveness timeout to notice.
            if (hadNetwork) {
                LokiLogger.i(TAG, "Default network changed — reconnecting dealer")
                SessionHolder.player?.onNetworkChanged()
            }
            hadNetwork = true
        }
    }

    private fun registerNetworkCallback() {
        val cm = getSystemService(ConnectivityManager::class.java) ?: return
        connectivityManager = cm
        try {
            cm.registerDefaultNetworkCallback(networkCallback)
        } catch (e: Exception) {
            LokiLogger.e(TAG, "registerDefaultNetworkCallback failed", e)
        }
    }

    private fun unregisterNetworkCallback() {
        try {
            connectivityManager?.unregisterNetworkCallback(networkCallback)
        } catch (_: Exception) {}
        connectivityManager = null
    }

    override fun onDestroy() {
        unregisterNetworkCallback()
        if (openAudioEffectSession) {
            broadcastAudioEffectAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION)
            openAudioEffectSession = false
        }
        mediaSession?.run {
            isActive = false
            release()
        }
        player.release()
        deezerProxy.stop()
        // Do NOT clear SessionHolder here — the VM and MainActivity already
        // handle explicit user-close teardown. If the service is dying for
        // other reasons (e.g. low memory) we want the session to stay live
        // so the next launch can resume.
        instance = null
        super.onDestroy()
    }
}
