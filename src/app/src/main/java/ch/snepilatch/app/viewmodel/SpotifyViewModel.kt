package ch.snepilatch.app.viewmodel

import android.content.Context
import android.content.Intent
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.snepilatch.app.util.LokiLogger
import ch.snepilatch.app.util.detectActiveAudioOutput
import ch.snepilatch.app.util.extractThemeColorsFromArt
import ch.snepilatch.app.util.normalizeSpotifyImageUrl
import ch.snepilatch.app.playback.MusicPlaybackService
import ch.snepilatch.app.playback.PositionInterpolator
import ch.snepilatch.app.playback.SessionHolder
import ch.snepilatch.app.playback.engine.SpotifyCdnResolver
import ch.snepilatch.app.playback.engine.SpotifyStream
import ch.snepilatch.app.data.*
import kotify.api.album.Album
import kotify.api.artist.Artist
import kotify.api.home.Home
import kotify.api.home.HomeData
import kotify.api.playerconnect.PlayerConnect
import kotify.api.playlist.Playlist
import kotify.api.playerstatus.DeviceInfo
import kotify.api.playerstatus.PlayerStateData
import kotify.api.lyrics.Lyrics
import kotify.api.lyrics.LyricsData
import kotify.api.song.Song
import kotify.api.user.User
import kotify.api.canvas.Canvas
import kotify.cdn.CdnPlayback
import kotify.cdn.SpotifyPlayback
import kotify.cdn.StreamInfo
import kotify.cdn.StreamResult
import kotify.session.Session
import kotify.session.SessionConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

class SpotifyViewModel : ViewModel() {

    private val TAG = "SpotifyVM"

    // Navigation
    val currentScreen = MutableStateFlow(Screen.HOME)
    private var screenStack = mutableListOf<Screen>()
    val needsLogin = MutableStateFlow(false)

    // Session state — ownership lives in SessionHolder (process-scoped).
    // These accessors make it obvious that the VM is a reader, not an owner,
    // and keep the rest of the file unchanged.
    private var session: Session?
        get() = SessionHolder.session
        set(value) { SessionHolder.session = value }
    private var player: PlayerConnect?
        get() = SessionHolder.player
        set(value) { SessionHolder.player = value }
    private var username: String = ""
    val isInitialized = MutableStateFlow(false)
    val initError = MutableStateFlow<String?>(null)
    val rateLimitCooldown = MutableStateFlow(false)
    val cooldownSeconds = MutableStateFlow(0)
    private var initRetryCount = 0

    // Streaming
    private val cdn = CdnPlayback()
    private var spotifyPlayback: SpotifyPlayback?
        get() = SessionHolder.spotifyPlayback
        set(value) { SessionHolder.spotifyPlayback = value }
    private var cdnResolver: SpotifyCdnResolver?
        get() = SessionHolder.cdnResolver
        set(value) { SessionHolder.cdnResolver = value }
    private var latestFileId: String? = null  // from TrackPlaybackHandler via onPlaybackId
    private var currentStreamUri: String? = null
    private var nextStreamUrl: String? = null
    private var nextTrackInfo: TrackInfo? = null
    private var nextStreamProvider: String? = null
    private var nextCdnUrl: String? = null      // Pre-resolved Spotify CDN URL (DRM)
    private var nextCdnFileId: String? = null   // File ID for the pre-resolved CDN track
    private var lastCommandTs: Long = 0L  // timing: when last user command was sent
    private var lastCommandName: String = ""
    private var playUrlAt: Long = 0L      // timing: when playUrl/playDrmUrl was last called

    // Cold-start sync: when the user taps play with nothing loaded in ExoPlayer,
    // we call transferPlaybackHere(restorePaused=true) — claim the device on
    // Spotify Connect WITHOUT emitting audio anywhere — and wait for Spotify's
    // state machine to push the current track's file_id via the onPlaybackId
    // callback. We then resolve a CDN URL for that file, load ExoPlayer paused,
    // and the shared onReady callback seeks + starts ExoPlayer + Spotify in
    // lock-step. This is the same protocol the open.spotify.com web player uses.
    private var coldStartPending = false
    private var coldStartFileId: kotlinx.coroutines.CompletableDeferred<String>? = null
    val isStreaming = MutableStateFlow(false)
    val streamProvider = MutableStateFlow<String?>(null)
    val isNextReady = MutableStateFlow(false)
    private var suppressRemotePause = false
    private var resolveJob: Job? = null  // Cancel in-flight resolveAndPlay when a new track arrives


    // Account
    private val _account = MutableStateFlow(AccountInfo())
    val account: StateFlow<AccountInfo> = _account

    // Playback
    private val _playback = MutableStateFlow(PlaybackUiState())
    val playback: StateFlow<PlaybackUiState> = _playback
    private val positionInterpolator = PositionInterpolator(
        scope = viewModelScope,
        playback = _playback,
        isStreaming = isStreaming,
        getExoPositionMs = { MusicPlaybackService.instance?.getCurrentPosition() },
        reportPosition = { pos -> player?.reportPosition(pos, false) ?: Unit }
    )
    private var commandJob: Job? = null
    private var seekGuardUntil: Long = 0  // suppress remote position updates briefly after local seek

    // Home
    private val _homeData = MutableStateFlow<HomeData?>(null)
    val homeData: StateFlow<HomeData?> = _homeData
    val isHomeLoading = MutableStateFlow(true)

    // Search
    val searchQuery = MutableStateFlow("")
    private val _searchResults = MutableStateFlow<List<TrackInfo>>(emptyList())
    val searchResults: StateFlow<List<TrackInfo>> = _searchResults
    val isSearching = MutableStateFlow(false)
    private var searchJob: Job? = null

    // Library
    private val _library = MutableStateFlow<List<LibraryItem>>(emptyList())
    val library: StateFlow<List<LibraryItem>> = _library

    // Queue
    private val _queue = MutableStateFlow<List<TrackInfo>>(emptyList())
    val queue: StateFlow<List<TrackInfo>> = _queue
    // Next track info (always available from WebSocket state for mini player swipe)
    val nextTrackPreview = MutableStateFlow<TrackInfo?>(null)

    // Detail
    private val _detail = MutableStateFlow(DetailData())
    val detail: StateFlow<DetailData> = _detail

    // Devices
    private val _devices = MutableStateFlow<List<DeviceInfo>>(emptyList())
    val devices: StateFlow<List<DeviceInfo>> = _devices
    val showDevices = MutableStateFlow(false)
    val activeDeviceName = MutableStateFlow<String?>(null)
    val ourDeviceId: String? get() = player?.ourDeviceId()

    // Playing context (e.g. "Album • Abbey Road" or "Playlist • Chill Vibes")
    data class PlayingContext(val type: String, val name: String, val uri: String? = null)
    val playingContext = MutableStateFlow<PlayingContext?>(null)
    private var lastContextUri: String? = null
    private val playlistNameCache = mutableMapOf<String, String>()

    // Loading
    val isLoading = MutableStateFlow(false)
    val isStreamLoading = MutableStateFlow(false)

    // Snackbar messages
    private val _snackbarMessage = kotlinx.coroutines.flow.MutableSharedFlow<String>(extraBufferCapacity = 1)
    val snackbarMessage: kotlinx.coroutines.flow.SharedFlow<String> = _snackbarMessage

    // Like state
    val currentTrackLiked = MutableStateFlow(false)
    private var lastLikeCheckUri: String? = null

    // Dynamic theme
    val themeColors = MutableStateFlow(ThemeColors())
    private var appContext: Context? = null
    private var lastPaletteUrl: String? = null

    // Audio output device (Bluetooth, speaker, wired)
    val audioOutputName = MutableStateFlow<String?>(null)
    val audioOutputType = MutableStateFlow("speaker") // "speaker", "bluetooth", "wired", "usb"

    // Audio source preference: null = auto (default chain), or "tidal", "qobuz", "youtube"
    val preferredAudioSource = MutableStateFlow<String?>(null)
    // Lyrics animation direction for line-synced (non word-synced): "vertical" or "horizontal"
    val lyricsAnimDirection = MutableStateFlow("vertical")
    // Language preference: "system", "en", "de", "ru", "gsw"
    val appLanguage = MutableStateFlow("system")
    // Notification button preferences: "like", "shuffle", "repeat"
    val notificationLeftButton = MutableStateFlow("repeat")
    val notificationRightButton = MutableStateFlow("like")
    // Content region for CDN resolution
    val contentRegion = MutableStateFlow("US")
    // Canvas background
    val canvasEnabled = MutableStateFlow(false)
    val canvasUrl = MutableStateFlow<String?>(null)
    private var lastCanvasTrackUri: String? = null

    // Lyrics
    private val _lyrics = MutableStateFlow<LyricsData?>(null)
    val lyrics: StateFlow<LyricsData?> = _lyrics
    val isLyricsLoading = MutableStateFlow(false)
    private var lastLyricsTrackUri: String? = null


    fun initialize(cookies: Map<String, String>) {
        // Clean up any leftover from previous session
        SessionHolder.player?.let {
            try { kotlinx.coroutines.runBlocking { it.disconnect() } } catch (_: Exception) {}
        }
        SessionHolder.clear()
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sess = Session(SessionConfig(
                    identifier = "kotify-android",
                    initialCookies = cookies,
                    deviceProfile = kotify.config.DeviceProfile.CHROME_WINDOWS
                ))
                sess.load()
                session = sess
                val sp = SpotifyPlayback(sess)
                spotifyPlayback = sp
                cdnResolver = SpotifyCdnResolver(sess, sp)
                LokiLogger.i(TAG, "Session loaded")

                // Start loading home and library immediately after session is ready
                // These run in parallel with user profile and player setup
                loadHome()
                loadLibrary()

                val userApi = User(sess)
                val me = userApi.getCurrentUser()
                username = me.username
                val isPremium = userApi.hasPremium()
                // Get public profile (display name + avatar) from user-profile-view API
                val pubProfile = userApi.getProfile(username)
                val displayName = pubProfile.displayName.ifEmpty { username }
                val userId = username
                val imageUrl = pubProfile.imageUrl
                LokiLogger.i(TAG, "Profile: display=$displayName, user=$username, image=${imageUrl?.take(40)}")
                _account.value = AccountInfo(
                    username = username,
                    displayName = displayName,
                    isPremium = isPremium,
                    profileImageUrl = imageUrl,
                    userId = username,
                    followers = pubProfile.followers,
                    playlistCount = pubProfile.publicPlaylists
                )
                LokiLogger.i(TAG, "User: $username ($displayName), premium: $isPremium")

                isInitialized.value = true
                initRetryCount = 0

                // Disconnect any existing player before creating a new one
                // This prevents duplicate device registrations
                player?.let { oldPlayer ->
                    LokiLogger.i(TAG, "Disconnecting old player before creating new one")
                    try { oldPlayer.disconnect() } catch (_: Exception) {}
                    player = null
                }

                val phoneName = android.os.Build.MODEL
                val pc = PlayerConnect(sess, deviceName = phoneName)

                // Fresh device ID every launch — avoids stale server-side registrations
                pc.ready()
                // Assigning through the property setter publishes the player to
                // SessionHolder — session/spotifyPlayback/cdnResolver are already
                // live there from the initialization block above.
                player = pc
                LokiLogger.i(TAG, "Player ready, device: ${pc.ourDeviceId()}")
                loadDevices()

                // Refresh access token periodically to prevent stale credentials
                viewModelScope.launch(Dispatchers.IO) {
                    while (true) {
                        delay(50 * 60 * 1000L) // Every 50 minutes
                        try {
                            sess.baseClient.refreshAccessToken()
                            LokiLogger.i(TAG, "Access token refreshed")
                        } catch (e: Exception) {
                            LokiLogger.w(TAG, "Token refresh failed: ${e.message}")
                        }
                    }
                }

                wirePlayerConnectCallbacks(pc)
                wireServiceControls()

                // Initial state
                val state = pc.getState()
                if (state != null) {
                    updatePlaybackFromState(state)
                    // Only resolve and play if our device is active and Spotify is actually playing.
                    // Idle preload is intentionally NOT done — pressing play sends resume to
                    // Spotify, which pushes the file_id via the WS state machine, and
                    // resolveAndPlay then loads ExoPlayer with the right CDN URL.
                    if (state.is_active_device && state.isActuallyPlaying) {
                        resolveCurrentTrack(state)
                    }
                }
            } catch (e: Exception) {
                LokiLogger.e(TAG, "Init failed", e)
                val msg = e.message ?: "Unknown error"
                if (msg.contains("Unauthorized") || msg.contains("401") || msg.contains("code\":400")) {
                    initRetryCount++
                    if (initRetryCount > 5) {
                        LokiLogger.e(TAG, "Rate limited — 5 retries exhausted, giving up")
                        initError.value = "Connection failed after 5 attempts. Please try again later."
                        rateLimitCooldown.value = false
                        return@launch
                    }
                    val cooldownSecs = 20 * initRetryCount // 20s, 40s, 60s...
                    LokiLogger.w(TAG, "Rate limited, attempt $initRetryCount/5, cooling down ${cooldownSecs}s...")
                    initError.value = "Rate limited — attempt $initRetryCount/5"
                    rateLimitCooldown.value = true
                    for (i in cooldownSecs downTo 1) {
                        cooldownSeconds.value = i
                        delay(1000)
                    }
                    rateLimitCooldown.value = false
                    cooldownSeconds.value = 0
                    initError.value = null
                    // Retry after cooldown
                    try {
                        val ctx = MusicPlaybackService.instance as? android.content.Context ?: return@launch
                        val savedCookies = ch.snepilatch.app.util.loadCookies(ctx)
                        if (savedCookies != null) {
                            initialize(savedCookies)
                        } else {
                            needsLogin.value = true
                        }
                    } catch (_: Exception) {
                        needsLogin.value = true
                    }
                } else {
                    initError.value = msg
                }
            }
        }
    }

    /**
     * Attach the ViewModel's reactions to a freshly created PlayerConnect.
     *
     * These callbacks are what keeps the UI in sync with whatever Spotify is
     * doing on the account — track changes, pauses from other devices,
     * WebSocket reconnects, and file-id pushes that feed the cold-start
     * protocol.
     */
    private fun wirePlayerConnectCallbacks(pc: PlayerConnect) {
        pc.onPlaybackId { fileId ->
            LokiLogger.i(TAG, "Got file ID from state machine: $fileId")
            latestFileId = fileId
            // Cold-start: complete the deferred so coldStartPlay can proceed
            // with resolving the CDN URL and loading ExoPlayer.
            val deferred = coldStartFileId
            if (coldStartPending && deferred != null && !deferred.isCompleted) {
                deferred.complete(fileId)
            }
        }

        pc.onNextPlaybackId { fileId, _, name ->
            if (preferredAudioSource.value != null) return@onNextPlaybackId
            // Deduplicate — don't re-resolve if we already have this file ID cached
            if (fileId == nextCdnFileId && nextCdnUrl != null) return@onNextPlaybackId
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    val resolver = cdnResolver ?: return@launch
                    // Double-check after coroutine dispatch
                    if (fileId == nextCdnFileId && nextCdnUrl != null) return@launch
                    LokiLogger.d(TAG, "Pre-resolving next Spotify CDN: $name ($fileId)")
                    val stream = resolver.resolveForFileId(fileId)
                    // Cache only — DRM items can't be pre-queued because each
                    // needs its own Widevine license session.
                    nextCdnUrl = stream.cdnUrl
                    nextCdnFileId = fileId
                    isNextReady.value = true
                    LokiLogger.i(TAG, "Next Spotify CDN pre-resolved: $name")
                } catch (e: Exception) {
                    LokiLogger.d(TAG, "Pre-resolve next CDN failed: ${e.message}")
                }
            }
        }

        pc.onState { state ->
            val delta = if (lastCommandTs > 0) System.currentTimeMillis() - lastCommandTs else -1
            LokiLogger.i(TAG, "[Timing] WS onState arrived (${delta}ms after CMD '$lastCommandName')")
            viewModelScope.launch { updatePlaybackFromState(state) }
        }

        pc.onTrackChange { event ->
            val delta = if (lastCommandTs > 0) System.currentTimeMillis() - lastCommandTs else -1
            LokiLogger.i(TAG, "[Timing] WS onTrackChange arrived (${delta}ms after CMD '$lastCommandName') -> ${event.current?.uri} fileId=${event.currentFileId}")
            // Set latestFileId from cluster state so resolveAndPlay doesn't wait for onPlaybackId
            if (event.currentFileId != null) latestFileId = event.currentFileId
            // Only auto-resolve when we're already streaming (legit track changes
            // during active playback). Otherwise the very first WS push on init
            // runs a futile CDN resolve, eats 18s of SongLink retries on the
            // third-party fallback path, AND resets _playback.value.positionMs
            // to 0 — clobbering the saved snapshot position.
            if (!isStreaming.value) {
                LokiLogger.d(TAG, "Skipping resolveAndPlay: not streaming (idle WS push)")
                return@onTrackChange
            }
            resolveJob?.cancel()
            resolveJob = viewModelScope.launch(Dispatchers.IO) {
                resolveAndPlay(event)
                if (currentScreen.value == Screen.QUEUE) refreshQueue()
            }
        }

        pc.onPlay { state ->
            if (!isStreaming.value) {
                LokiLogger.i(TAG, "Spotify: play at ${state.position_as_of_timestamp}ms")
                MusicPlaybackService.instance?.syncPlay(state.position_as_of_timestamp)
            } else if (_playback.value.isPaused) {
                // We're streaming locally and were paused — the user just hit
                // play from another Spotify client. Resume ExoPlayer in lock-step.
                LokiLogger.i(TAG, "Remote play while streaming: resuming ExoPlayer")
                MusicPlaybackService.instance?.syncPlay(_playback.value.positionMs)
                _playback.value = _playback.value.copy(isPlaying = true, isPaused = false)
                startPositionTicker()
            }
        }

        pc.onPause { state ->
            if (suppressRemotePause) {
                LokiLogger.d(TAG, "Spotify: pause suppressed (reconnecting)")
                return@onPause
            }
            if (!isStreaming.value) {
                LokiLogger.i(TAG, "Spotify: pause at ${state.position_as_of_timestamp}ms")
                MusicPlaybackService.instance?.syncPause()
            } else {
                // We're streaming locally and the user hit pause from another
                // Spotify client (browser, desktop, another phone). Mirror the
                // pause on ExoPlayer so audio actually stops. Without this the
                // browser pause event is silently dropped and music keeps
                // playing here.
                LokiLogger.i(TAG, "Remote pause while streaming: pausing ExoPlayer")
                MusicPlaybackService.instance?.syncPause()
                _playback.value = _playback.value.copy(isPlaying = false, isPaused = true)
                stopPositionTicker()
            }
        }

        pc.onReconnected {
            viewModelScope.launch(Dispatchers.IO) {
                val wasPlaying = withContext(Dispatchers.Main) {
                    MusicPlaybackService.instance?.isPlaying() == true
                }
                LokiLogger.i(TAG, "WebSocket reconnected, re-syncing state (wasPlaying=$wasPlaying)")
                if (wasPlaying) suppressRemotePause = true
                try {
                    pc.getState()?.let { updatePlaybackFromState(it) }
                    loadDevices()
                    if (wasPlaying) {
                        try { pc.resume() } catch (_: Exception) {}
                    }
                } catch (e: Exception) {
                    LokiLogger.e(TAG, "Failed to re-sync after reconnect", e)
                } finally {
                    suppressRemotePause = false
                }
            }
        }
    }

    fun onLoginComplete(cookies: Map<String, String>) {
        needsLogin.value = false
        initError.value = null
        initialize(cookies)
    }

    /**
     * Launch [block] on Dispatchers.IO with the current [Session] as its
     * receiver. If no session is live the launch is a no-op. Cancellation
     * propagates; all other exceptions are caught and logged against [tag]
     * so a single failed call can't crash the ViewModel.
     *
     * This replaces the boilerplate pattern of
     * `viewModelScope.launch(Dispatchers.IO) { try { val s = session ?: return@launch ... } catch ... }`
     * that appeared throughout the ViewModel.
     */
    private fun launchWithSession(tag: String, block: suspend (Session) -> Unit): Job =
        viewModelScope.launch(Dispatchers.IO) {
            val sess = session ?: return@launch
            try {
                block(sess)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LokiLogger.e(TAG, tag, e)
            }
        }

    fun showLogin() {
        needsLogin.value = true
    }

    fun navigateTo(screen: Screen) {
        screenStack.add(currentScreen.value)
        currentScreen.value = screen
    }

    fun goBack(): Boolean {
        if (screenStack.isNotEmpty()) {
            currentScreen.value = screenStack.removeAt(screenStack.lastIndex)
            return true
        }
        return false
    }

    /**
     * Handle a deep link URI from open.spotify.com.
     * Supported paths: /track/{id}, /album/{id}, /playlist/{id}, /artist/{id}
     */
    fun handleDeepLink(uri: android.net.Uri) {
        val segments = uri.pathSegments ?: return
        if (segments.size < 2) return
        val type = segments[0]
        val id = segments[1]
        if (id.isBlank()) return

        LokiLogger.i(TAG, "Deep link: type=$type id=$id")

        when (type) {
            "track" -> playTrack("spotify:track:$id")
            "album" -> openAlbum(id)
            "playlist" -> openPlaylist(id)
            "artist" -> openArtist(id)
            else -> LokiLogger.i(TAG, "Unsupported deep link type: $type")
        }
    }

    // --- Playback ---

    private suspend fun updatePlaybackFromState(state: PlayerStateData) {
        val track = state.track
        val imageUrl = normalizeSpotifyImageUrl(
            track?.imageLargeUrl ?: track?.imageUrl ?: track?.imageSmallUrl
        )
        val trackInfo = if (track != null) {
            TrackInfo(
                uri = track.uri,
                name = track.name.ifBlank { "Unknown" },
                artist = track.artistName ?: "Unknown",
                albumArt = imageUrl,
                albumName = track.albumName,
                durationMs = state.duration
            )
        } else null

        // When streaming locally, ExoPlayer is usually the source of truth for
        // play/pause. BUT during a remote pause transition Spotify's state push
        // can arrive BEFORE our posted `player.pause()` has actually paused
        // ExoPlayer on the main thread — so reading ExoPlayer here would still
        // see "playing" and we'd overwrite the paused state the onPause handler
        // just set. Guard against the race by treating EITHER ExoPlayer-paused
        // OR Spotify-reports-paused as "paused", never flipping back.
        val exoPlaying = if (isStreaming.value) {
            withContext(Dispatchers.Main) {
                MusicPlaybackService.instance?.isPlaying() == true
            }
        } else false

        val actuallyPlaying = if (isStreaming.value) (exoPlaying && !state.is_paused) else state.isActuallyPlaying
        val actuallyPaused = if (isStreaming.value) (!exoPlaying || state.is_paused) else state.is_paused

        // When streaming, the audio source of truth is ExoPlayer, not Spotify's state.
        // If Spotify's state says track B but we're still playing track A, keep showing track A.
        val stateTrackUri = track?.uri
        val isTrackMismatch = isStreaming.value && currentStreamUri != null && stateTrackUri != currentStreamUri

        // Detect remote seek: when streaming, if Spotify's position differs significantly
        // from ExoPlayer's, someone seeked from the web player — sync ExoPlayer.
        // Skip during track transitions (mismatch) to avoid false seeks.
        // Skip if the spotify timestamp is stale (>10s old) — the cluster snapshot's
        // timestamp can be minutes in the past after the user resumes from idle, and
        // interpolating against it produces a bogus position past the end of the track.
        val isSeekGuarded = System.currentTimeMillis() < seekGuardUntil
        val timestampAge = System.currentTimeMillis() - state.timestamp
        val isStaleSnapshot = timestampAge > 10_000L
        if (isStreaming.value && !isSeekGuarded && !isStreamLoading.value && !isTrackMismatch && !isStaleSnapshot) {
            val exoPos = withContext(Dispatchers.Main) {
                MusicPlaybackService.instance?.getCurrentPosition()
            } ?: 0L
            // Interpolate Spotify's position using timestamp
            val elapsed = timestampAge.coerceAtLeast(0)
            val spotifyPos = if (state.is_playing && !state.is_paused) {
                state.position_as_of_timestamp + elapsed
            } else {
                state.position_as_of_timestamp
            }
            if (kotlin.math.abs(spotifyPos - exoPos) > 3000) {
                LokiLogger.i(TAG, "Remote seek detected: Spotify=${spotifyPos}ms (interpolated), ExoPlayer=${exoPos}ms — syncing")
                seekGuardUntil = System.currentTimeMillis() + 1500
                withContext(Dispatchers.Main) {
                    MusicPlaybackService.instance?.syncSeek(spotifyPos)
                }
            }
        } else if (isStaleSnapshot && isStreaming.value) {
            LokiLogger.d(TAG, "Skipping remote-seek sync: snapshot timestamp is ${timestampAge}ms old")
        }

        val posMs = when {
            isSeekGuarded -> _playback.value.positionMs
            isTrackMismatch -> _playback.value.positionMs  // Keep old position during transition
            isStreamLoading.value -> 0L
            isStreaming.value -> withContext(Dispatchers.Main) {
                MusicPlaybackService.instance?.getCurrentPosition()
            } ?: state.position_as_of_timestamp
            else -> {
                // Not streaming: interpolate the snapshot position only if a device is
                // actually playing right now. Spotify keeps is_playing=true even when no
                // device is active (idle state), so on init the snapshot can be hours
                // stale — interpolating against that would clamp the position to the end
                // of the track. isActuallyPlaying already accounts for has_active_device
                // and the position-vs-duration boundary, so it's the correct gate here.
                if (state.isActuallyPlaying) {
                    val elapsed = (System.currentTimeMillis() - state.timestamp).coerceAtLeast(0)
                    (state.position_as_of_timestamp + elapsed).coerceAtMost(state.duration)
                } else {
                    state.position_as_of_timestamp
                }
            }
        }

        val displayTrack = if (isTrackMismatch) _playback.value.track else trackInfo
        val displayDuration = if (isTrackMismatch) _playback.value.durationMs else state.duration

        _playback.value = PlaybackUiState(
            track = displayTrack,
            isPlaying = actuallyPlaying,
            isPaused = actuallyPaused,
            positionMs = posMs,
            durationMs = displayDuration,
            isShuffling = state.is_shuffling,
            repeatMode = state.repeat_mode,
            volume = _playback.value.volume
        )

        // Only update theme/liked/canvas for the track we're ACTUALLY displaying
        val displayUri = displayTrack?.uri
        if (!isTrackMismatch) {
            if (imageUrl != null && imageUrl != lastPaletteUrl) {
                lastPaletteUrl = imageUrl
                extractColorsFromArt(imageUrl)
            }
            if (displayUri != null) {
                checkLikedState(displayUri)
                fetchCanvasForTrack(displayUri)
            }
        }

        // Extract next track info for mini player swipe preview
        nextTrackPreview.value = state.next_tracks.firstOrNull()?.toTrackInfo()

        if (actuallyPlaying) {
            startPositionTicker()
        } else {
            stopPositionTicker()
        }

        // Sync notification button states
        MusicPlaybackService.instance?.let { svc ->
            svc.isLiked = currentTrackLiked.value
            svc.isShuffling = state.is_shuffling
            svc.repeatMode = state.repeat_mode
        }

        // Detect playback transfer away — stop local ExoPlayer
        LokiLogger.d(TAG, "Transfer check: streaming=${isStreaming.value} hasActive=${state.has_active_device} isOurs=${state.is_active_device}")
        if (isStreaming.value && state.has_active_device && !state.is_active_device) {
            LokiLogger.i(TAG, "Playback transferred to another device — stopping local stream")
            isStreaming.value = false
            streamProvider.value = null
            currentStreamUri = null
            stopPositionTicker()
            withContext(Dispatchers.Main) {
                MusicPlaybackService.instance?.stop()
            }
        }

        // Update active device indicator from state
        if (state.is_active_device) {
            activeDeviceName.value = android.os.Build.MODEL
        } else if (state.has_active_device) {
            // Another device is active, try to get its name
            loadDevices()
        } else {
            activeDeviceName.value = null
        }

        playingContext.value = resolvePlayingContext(state.context_uri, track)
        lastContextUri = state.context_uri
    }

    /**
     * Map the current context URI (spotify:playlist:..., :album:..., :artist:...,
     * :collection:tracks) into a [PlayingContext] suitable for display. Playlist
     * names require an API lookup, which is cached and fired asynchronously —
     * the first emission uses the cached value (or a placeholder) and a
     * subsequent push updates the playingContext flow once the API returns.
     */
    private fun resolvePlayingContext(
        contextUri: String?,
        track: kotify.api.playerstatus.PlayerTrack?
    ): PlayingContext? {
        if (contextUri == null) return null
        return when {
            contextUri.contains(":collection:tracks") -> PlayingContext("Liked Songs", "Liked Songs", contextUri)
            contextUri.contains(":playlist:") -> {
                val playlistId = contextUri.substringAfter(":playlist:")
                val cached = playlistNameCache[playlistId]
                if (cached == null && lastContextUri != contextUri) {
                    viewModelScope.launch(Dispatchers.IO) {
                        try {
                            val sess = session ?: return@launch
                            val info = kotify.api.playlist.Playlist(sess).getPlaylist(playlistId, limit = 1)
                            val title = info.name.takeIf { it.isNotBlank() }
                            if (title != null) {
                                playlistNameCache[playlistId] = title
                                playingContext.value = PlayingContext("Playlist", title, contextUri)
                            }
                        } catch (_: Exception) {}
                    }
                }
                PlayingContext("Playlist", cached ?: "Playlist", contextUri)
            }
            contextUri.contains(":album:") -> PlayingContext("Album", track?.albumName ?: "Album", contextUri)
            contextUri.contains(":artist:") -> PlayingContext("Artist", track?.artistName ?: "Artist", contextUri)
            else -> null
        }
    }

    private fun startPositionTicker() = positionInterpolator.start()
    private fun stopPositionTicker() = positionInterpolator.stop()

    fun togglePlayPause() {
        commandJob?.cancel()
        val action = if (_playback.value.isPaused || !_playback.value.isPlaying) "resume" else "pause"
        lastCommandTs = System.currentTimeMillis()
        lastCommandName = action
        LokiLogger.i(TAG, "[Timing] CMD $action sent")
        commandJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val p = player ?: return@launch
                val t0 = System.currentTimeMillis()
                if (action == "resume") {
                    if (isStreaming.value) {
                        // Hot path: ExoPlayer is already loaded — flip the UI to playing
                        // and start audio locally + sync Spotify Connect.
                        _playback.value = _playback.value.copy(isPlaying = true, isPaused = false)
                        startPositionTicker()
                        withContext(Dispatchers.Main) { MusicPlaybackService.instance?.syncPlay(_playback.value.positionMs) }
                        try {
                            p.resume()
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            LokiLogger.w(TAG, "resume failed, transferring playback and retrying: ${e.message}")
                            p.transferPlaybackHere()
                            delay(500)
                            p.resume()
                        }
                    } else {
                        // Cold start: nothing loaded in ExoPlayer yet. Mirror the Spotify
                        // web player's protocol — fetch track metadata directly (no WS,
                        // no Spotify state changes), claim the device with
                        // restore_paused=true, load ExoPlayer paused, and only then
                        // start Spotify+ExoPlayer in sync via the onReady callback.
                        coldStartPlay()
                    }
                } else {
                    _playback.value = _playback.value.copy(isPaused = true)
                    stopPositionTicker()
                    if (isStreaming.value) {
                        withContext(Dispatchers.Main) { MusicPlaybackService.instance?.syncPause() }
                    }
                    try {
                        p.pause()
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) {
                        LokiLogger.w(TAG, "pause failed, transferring playback and retrying: ${e.message}")
                        p.transferPlaybackHere()
                        delay(500)
                        p.pause()
                    }
                }
                LokiLogger.i(TAG, "[Timing] CMD $action API done in ${System.currentTimeMillis() - t0}ms")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { LokiLogger.e(TAG, "togglePlayPause", e) }
        }
    }

    /**
     * Cold-start playback that mirrors the Spotify web player's protocol.
     *
     * The web player's HAR shows the file_id is NOT in the metadata API for most
     * accounts — it comes from the state machine after the transfer call. So the
     * actual sequence is:
     *
     *   1. transferPlaybackHere(restorePaused = true)
     *      → POST /connect-state/v1/connect/transfer with `restore_paused: "pause"`
     *      Spotify Connect claims this device as active WITHOUT emitting audio,
     *      and the dealer pushes a cluster_update with the next track + file_id
     *      via the WS state machine.
     *   2. The existing onTrackChange listener fires resolveAndPlay; it reads
     *      coldStartPending and calls playDrmUrl(startPlaying = false). ExoPlayer
     *      buffers the track and prepares its Widevine session, but stays paused.
     *   3. wireServiceControls.onReady sees coldStartPending, seeks ExoPlayer to
     *      the saved position, syncPlay()s locally, and tells Spotify to resume.
     *      Local audio and remote state start together — no ghost playback on
     *      any other device.
     *
     * On any failure we reset state and fall back to the legacy path (p.resume()),
     * so the user still gets audio just slower.
     */
    private suspend fun coldStartPlay() {
        val p = player ?: return
        if (cdnResolver == null) {
            LokiLogger.w(TAG, "[ColdStart] CdnResolver not initialized, falling back to legacy resume")
            try { p.resume() } catch (_: Exception) {}
            return
        }

        // Capture the saved resume position NOW, before we kick off any async work
        // that could overwrite _playback.value via WS state pushes. The transfer
        // call below triggers cluster_update pushes that updatePlaybackFromState
        // happily writes back into _playback.value.positionMs.
        val savedPositionAtEntry = _playback.value.positionMs

        coldStartPending = true
        isStreamLoading.value = true
        // Suppress remote-seek detection during the entire cold-start handoff so
        // cluster_update pushes triggered by the transfer don't race-seek
        // ExoPlayer before we explicitly position it in the onReady callback.
        seekGuardUntil = System.currentTimeMillis() + 30_000L
        // CompletableDeferred that gets completed when onPlaybackId fires with
        // the current track's file id. Set up BEFORE the transfer call so we
        // don't miss the push.
        val fileIdDeferred = kotlinx.coroutines.CompletableDeferred<String>()
        coldStartFileId = fileIdDeferred

        LokiLogger.i(TAG, "[ColdStart] transfer to self with restore_paused=pause")
        try {
            p.transferPlaybackHere(restorePaused = true)
        } catch (e: CancellationException) {
            coldStartPending = false
            coldStartFileId = null
            isStreamLoading.value = false
            seekGuardUntil = 0L
            throw e
        } catch (e: Exception) {
            LokiLogger.e(TAG, "[ColdStart] transferPlaybackHere failed, falling back", e)
            coldStartPending = false
            coldStartFileId = null
            isStreamLoading.value = false
            seekGuardUntil = 0L
            try { p.resume() } catch (_: Exception) {}
            return
        }

        // Wait for Spotify's state machine to push the file id via onPlaybackId.
        // Capped at 5s — typically arrives in <1s on a fast connection. If we
        // already have a cached latestFileId from an earlier session it'll arrive
        // even sooner because the cluster snapshot includes it.
        val fileId = kotlinx.coroutines.withTimeoutOrNull(5_000L) { fileIdDeferred.await() }
        coldStartFileId = null
        if (fileId == null) {
            LokiLogger.w(TAG, "[ColdStart] timed out waiting for file id, falling back to legacy resume")
            coldStartPending = false
            isStreamLoading.value = false
            seekGuardUntil = 0L
            try { p.resume() } catch (_: Exception) {}
            return
        }

        // Read the current track from the snapshot (updatePlaybackFromState has
        // updated it during the transfer's WS state pushes). Fall back to the
        // saved snapshot track if needed.
        val track = _playback.value.track
        if (track == null || track.uri.isBlank()) {
            LokiLogger.w(TAG, "[ColdStart] no track in playback state after transfer, falling back")
            coldStartPending = false
            isStreamLoading.value = false
            seekGuardUntil = 0L
            try { p.resume() } catch (_: Exception) {}
            return
        }
        val trackUri = track.uri
        val title = track.name.ifBlank { "Unknown" }
        val artist = track.artist.ifBlank { "Unknown" }
        val art = track.albumArt
        LokiLogger.i(TAG, "[ColdStart] file id=$fileId for $trackUri — resolving CDN")

        try {
            val resolver = cdnResolver ?: throw IllegalStateException("CdnResolver not initialized")
            val stream = resolver.resolveForFileId(fileId)
            LokiLogger.i(TAG, "[ColdStart] resolved ${stream.mirrorCount} CDN mirrors")

            // Start ExoPlayer at the right position from the moment it's ready —
            // no post-prepare seek dance. setMediaItem(item, startPositionMs)
            // guarantees STATE_READY fires AT that position, and playWhenReady=true
            // makes audio start immediately. The onReady callback then calls
            // p.resume() to tell Spotify Connect we're now playing.
            playUrlAt = System.currentTimeMillis()
            withContext(Dispatchers.Main) {
                MusicPlaybackService.instance?.playDrmUrl(
                    stream.cdnUrl, stream.licenseUrl, stream.licenseHeaders, title, artist, art,
                    startPlaying = true,
                    startPositionMs = savedPositionAtEntry,
                )
            }
            currentStreamUri = trackUri
            isStreaming.value = true
            streamProvider.value = "Spotify CDN"
            LokiLogger.i(TAG, "[ColdStart] ExoPlayer loading at ${savedPositionAtEntry}ms, will start on STATE_READY")
        } catch (e: Exception) {
            LokiLogger.e(TAG, "[ColdStart] CDN/playDrmUrl failed, falling back to legacy resume", e)
            coldStartPending = false
            currentStreamUri = null
            isStreaming.value = false
            isStreamLoading.value = false
            seekGuardUntil = 0L
            try { p.resume() } catch (_: Exception) {}
        }
    }

    fun skipNext() {
        commandJob?.cancel()
        lastCommandTs = System.currentTimeMillis()
        lastCommandName = "skipNext"
        LokiLogger.i(TAG, "[Timing] CMD skipNext sent")
        commandJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val t0 = System.currentTimeMillis()
                player?.skipNext()
                LokiLogger.i(TAG, "[Timing] CMD skipNext API done in ${System.currentTimeMillis() - t0}ms")
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { LokiLogger.e(TAG, "skipNext", e) }
        }
    }

    fun skipPrevious() {
        commandJob?.cancel()
        lastCommandTs = System.currentTimeMillis()
        lastCommandName = "skipPrevious"
        LokiLogger.i(TAG, "[Timing] CMD skipPrevious sent")
        commandJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val t0 = System.currentTimeMillis()
                player?.skipPrevious()
                LokiLogger.i(TAG, "[Timing] CMD skipPrevious API done in ${System.currentTimeMillis() - t0}ms")
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { LokiLogger.e(TAG, "skipPrevious", e) }
        }
    }

    fun seekTo(positionMs: Long) {
        // Guard: suppress remote state updates for 1.5s to prevent seek position from being overwritten
        seekGuardUntil = System.currentTimeMillis() + 1500
        _playback.value = _playback.value.copy(positionMs = positionMs)
        // Seek ExoPlayer on main thread
        viewModelScope.launch(Dispatchers.Main) {
            MusicPlaybackService.instance?.syncSeek(positionMs)
        }
        // Seek Spotify in background
        viewModelScope.launch(Dispatchers.IO) {
            try {
                player?.seek(positionMs.toInt())
            } catch (e: Exception) { LokiLogger.e(TAG, "seek", e) }
        }
    }

    fun toggleShuffle() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val newMode = if (_playback.value.isShuffling) "off" else "on"
                player?.setShuffle(newMode)
                _playback.value = _playback.value.copy(isShuffling = newMode != "off")
            } catch (e: Exception) { LokiLogger.e(TAG, "shuffle", e) }
        }
    }

    fun cycleRepeat() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val newMode = when (_playback.value.repeatMode) {
                    "off" -> "context"
                    "context" -> "track"
                    else -> "off"
                }
                player?.setRepeat(newMode)
                _playback.value = _playback.value.copy(repeatMode = newMode)
            } catch (e: Exception) { LokiLogger.e(TAG, "repeat", e) }
        }
    }

    fun setVolume(volume: Double, context: Context? = null) {
        context?.let {
            val am = it.getSystemService(Context.AUDIO_SERVICE) as android.media.AudioManager
            val maxVol = am.getStreamMaxVolume(android.media.AudioManager.STREAM_MUSIC)
            val newVol = (volume * maxVol).toInt()
            am.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, newVol, 0)
        }
        _playback.value = _playback.value.copy(volume = volume)
    }

    fun setSpotifyVolume(volumePercent: Double) {
        _playback.value = _playback.value.copy(volume = volumePercent)
        viewModelScope.launch(Dispatchers.IO) {
            try {
                player?.setVolume(volumePercent)
            } catch (e: Exception) { LokiLogger.e(TAG, "setSpotifyVolume", e) }
        }
    }

    fun playTrack(trackUri: String, contextUri: String? = null) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val pc = player ?: return@launch
                try {
                    pc.playTrack(trackUri, contextUri)
                } catch (e: Exception) {
                    if (e.message?.contains("PLAYER_COMMAND_REJECTED") == true) {
                        LokiLogger.i(TAG, "Command rejected, transferring playback here and retrying")
                        pc.transferPlaybackHere()
                        delay(500)
                        pc.playTrack(trackUri, contextUri)
                    } else throw e
                }
                delay(500)
                refreshState()
            } catch (e: Exception) { LokiLogger.e(TAG, "playTrack", e) }
        }
    }

    fun addTrackToPlaylist(playlistId: String, trackUri: String) {
        launchWithSession("addTrackToPlaylist") { sess ->
            kotify.api.playlist.Playlist(sess).addToPlaylist(playlistId, listOf(trackUri))
            LokiLogger.i(TAG, "Added $trackUri to playlist $playlistId")
            _snackbarMessage.tryEmit("Added to playlist")
        }
    }

    // Track URI pending playlist picker
    val pendingPlaylistTrackUri = MutableStateFlow<String?>(null)
    val showPlaylistPicker = MutableStateFlow(false)

    fun showPlaylistPickerForTrack(trackUri: String) {
        pendingPlaylistTrackUri.value = trackUri
        showPlaylistPicker.value = true
    }

    fun skipToQueueIndex(index: Int) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val p = player ?: return@launch
                val track = _queue.value.getOrNull(index) ?: return@launch
                val uid = track.uid
                val ctxUri = playingContext.value?.uri
                if (uid != null) {
                    LokiLogger.i(TAG, "QUEUE SKIP: index=$index, name=${track.name}, uri=${track.uri}, uid=$uid")
                    p.skipToTrack(track.uri, uid, ctxUri)
                } else {
                    LokiLogger.w(TAG, "No UID for queue track, falling back to skip_next")
                    repeat(index + 1) { p.skipNext(); delay(300) }
                }
            } catch (e: Exception) { LokiLogger.e(TAG, "skipToQueueIndex", e) }
        }
    }

    fun addToQueue(trackUri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                player?.addToQueue(listOf(trackUri))
                _snackbarMessage.tryEmit("Added to queue")
            }
            catch (e: Exception) { LokiLogger.e(TAG, "addToQueue", e) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun openQueue() {
        navigateTo(Screen.QUEUE)
        refreshQueue()
    }

    fun openLyrics() {
        navigateTo(Screen.LYRICS)
        fetchLyrics()
    }

    fun fetchLyrics() {
        val trackUri = _playback.value.track?.uri ?: return
        if (trackUri == lastLyricsTrackUri && _lyrics.value != null) return
        lastLyricsTrackUri = trackUri
        isLyricsLoading.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sess = session ?: return@launch
                val trackId = trackUri.removePrefix("spotify:track:")
                val data = Lyrics(sess).getLyrics(trackId)
                _lyrics.value = data
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                LokiLogger.e(TAG, "fetchLyrics", e)
                _lyrics.value = null
            } finally {
                isLyricsLoading.value = false
            }
        }
    }

    fun refreshQueue() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = player?.getState() ?: return@launch
                val sess = session ?: return@launch
                val songApi = Song(sess)

                // Parse what we have from the typed cluster queue first.
                data class ParsedTrack(
                    val uri: String,
                    val info: TrackInfo,
                    val needsFetch: Boolean
                )
                val parsed = state.next_tracks.map { qt ->
                    val art = normalizeSpotifyImageUrl(qt.imageUrl)
                    val needsFetch = qt.name.isNullOrEmpty() || qt.artistName.isNullOrEmpty() || art == null
                    val info = TrackInfo(
                        uri = qt.uri,
                        name = qt.name ?: "Unknown",
                        artist = qt.artistName ?: "Unknown",
                        albumArt = art,
                        durationMs = qt.durationMs,
                        uid = qt.uid,
                    )
                    ParsedTrack(qt.uri, info, needsFetch)
                }

                // Show immediately with what we have
                _queue.value = parsed.map { it.info }

                // Fetch missing metadata concurrently
                val needFetch = parsed.filter { it.needsFetch }
                if (needFetch.isNotEmpty()) {
                    val fetched = coroutineScope {
                        needFetch.map { pt ->
                            async {
                                try {
                                    val trackId = pt.uri.removePrefix("spotify:track:")
                                    songApi.getSong(trackId)?.toTrackInfo() ?: pt.info
                                } catch (e: Exception) {
                                    LokiLogger.e(TAG, "refreshQueue fetch ${pt.uri}", e)
                                    pt.info
                                }
                            }
                        }.awaitAll()
                    }

                    // Rebuild the full list with fetched data, preserving UIDs
                    var fetchIdx = 0
                    _queue.value = parsed.map { pt ->
                        if (pt.needsFetch) {
                            val f = fetched[fetchIdx++]
                            f.copy(uid = pt.info.uid ?: f.uid)
                        } else pt.info
                    }
                }
            } catch (e: Exception) { LokiLogger.e(TAG, "refreshQueue", e) }
        }
    }

    @Suppress("UNCHECKED_CAST")
    fun openAlbumFromCurrentTrack() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = player?.getState() ?: return@launch
                val track = state.track ?: return@launch
                val albumUri = track.albumUri?.takeIf { it.isNotBlank() }
                    ?: state.context_uri?.takeIf { it.contains(":album:") }
                if (albumUri != null) {
                    val albumId = albumUri.removePrefix("spotify:album:")
                    openAlbum(albumId)
                } else {
                    LokiLogger.w(TAG, "No album URI on current track")
                }
            } catch (e: Exception) { LokiLogger.e(TAG, "openAlbumFromCurrentTrack", e) }
        }
    }

    fun navigateToContext() {
        val ctx = playingContext.value ?: return
        val uri = ctx.uri ?: return
        when {
            uri.contains(":playlist:") -> openPlaylist(uri.substringAfter(":playlist:"))
            uri.contains(":album:") -> openAlbum(uri.substringAfter(":album:"))
            uri.contains(":artist:") -> openArtist(uri.substringAfter(":artist:"))
            uri.contains(":collection:tracks") -> openLikedSongs()
        }
    }

    fun openAlbumForTrack(trackUri: String) {
        launchWithSession("openAlbumForTrack") { sess ->
            val trackId = trackUri.removePrefix("spotify:track:")
            val track = Song(sess).getSong(trackId) ?: return@launchWithSession
            val albumUri = track.album.uri.takeIf { it.isNotBlank() } ?: return@launchWithSession
            openAlbum(albumUri.substringAfterLast(":"))
        }
    }

    fun openArtistForTrack(trackUri: String) {
        launchWithSession("openArtistForTrack") { sess ->
            val trackId = trackUri.removePrefix("spotify:track:")
            val track = Song(sess).getSong(trackId) ?: return@launchWithSession
            val artistUri = track.artists.firstOrNull()?.uri?.takeIf { it.isNotBlank() } ?: return@launchWithSession
            openArtist(artistUri.substringAfterLast(":"))
        }
    }

    fun openArtistFromCurrentTrack() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = player?.getState() ?: return@launch
                val track = state.track ?: return@launch
                val artistUri = track.artistUri?.takeIf { it.isNotBlank() }
                if (artistUri != null) {
                    val artistId = artistUri.removePrefix("spotify:artist:")
                    openArtist(artistId)
                }
            } catch (e: Exception) { LokiLogger.e(TAG, "openArtistFromCurrentTrack", e) }
        }
    }

    fun likeSong(trackId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                session?.let { Song(it).likeSong(trackId) }
                currentTrackLiked.value = true
                _snackbarMessage.tryEmit("Added to Liked Songs")
            } catch (e: Exception) { LokiLogger.e(TAG, "likeSong", e) }
        }
    }

    fun unlikeSong(trackId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                session?.let { Song(it).unlikeSong(trackId) }
                currentTrackLiked.value = false
                _snackbarMessage.tryEmit("Removed from Liked Songs")
            } catch (e: Exception) { LokiLogger.e(TAG, "unlikeSong", e) }
        }
    }

    private fun checkLikedState(trackUri: String) {
        if (trackUri == lastLikeCheckUri) return
        lastLikeCheckUri = trackUri
        launchWithSession("checkLikedState") { sess ->
            val trackId = trackUri.removePrefix("spotify:track:")
            currentTrackLiked.value = Song(sess).isLiked(trackId)
        }
    }

    private suspend fun refreshState() {
        try {
            val state = player?.getState() ?: return
            updatePlaybackFromState(state)
        } catch (e: Exception) { LokiLogger.e(TAG, "refreshState", e) }
    }

    // --- Streaming ---

    fun setPreferredAudioSource(source: String?, context: Context) {
        preferredAudioSource.value = source
        context.getSharedPreferences("kotify_prefs", Context.MODE_PRIVATE)
            .edit().apply {
                if (source == null) remove("audio_source") else putString("audio_source", source)
            }.apply()
    }

    fun setContentRegion(region: String, context: Context) {
        contentRegion.value = region
        context.getSharedPreferences("kotify_prefs", Context.MODE_PRIVATE)
            .edit().putString("content_region", region).apply()
    }

    fun setLyricsAnimDirection(direction: String, context: Context) {
        lyricsAnimDirection.value = direction
        context.getSharedPreferences("kotify_prefs", Context.MODE_PRIVATE)
            .edit().putString("lyrics_anim_direction", direction).apply()
    }

    fun setAppLanguage(language: String, context: Context) {
        appLanguage.value = language
        context.getSharedPreferences("kotify_prefs", Context.MODE_PRIVATE)
            .edit().putString("app_language", language).apply()
        // Apply locale change
        val locale = if (language == "system") {
            java.util.Locale.getDefault()
        } else if (language == "gsw") {
            java.util.Locale.Builder().setLanguageTag("gsw").build()
        } else {
            java.util.Locale(language)
        }
        val config = context.resources.configuration
        config.setLocale(locale)
        @Suppress("DEPRECATION")
        context.resources.updateConfiguration(config, context.resources.displayMetrics)
        // Restart activity to apply
        (context as? android.app.Activity)?.recreate()
    }

    fun loadPreferences(context: Context) {
        val prefs = context.getSharedPreferences("kotify_prefs", Context.MODE_PRIVATE)
        val savedSource = prefs.getString("audio_source", null)
        // Migrate: old "spotify" value → null (Spotify CDN is now the default)
        preferredAudioSource.value = if (savedSource == "spotify") null else savedSource
        if (savedSource == "spotify") {
            prefs.edit().remove("audio_source").apply()
        }
        lyricsAnimDirection.value = prefs.getString("lyrics_anim_direction", "vertical") ?: "vertical"
        appLanguage.value = prefs.getString("app_language", "system") ?: "system"
        // Apply saved language on startup
        val lang = appLanguage.value
        if (lang != "system") {
            val locale = if (lang == "gsw") java.util.Locale.Builder().setLanguageTag("gsw").build() else java.util.Locale(lang)
            val config = context.resources.configuration
            config.setLocale(locale)
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
        }
        canvasEnabled.value = prefs.getBoolean("canvas_enabled", false)
        contentRegion.value = prefs.getString("content_region", "US") ?: "US"
        notificationLeftButton.value = prefs.getString("notification_left_button", "repeat") ?: "repeat"
        notificationRightButton.value = prefs.getString("notification_right_button", "like") ?: "like"
    }

    fun setNotificationLeftButton(button: String, context: Context) {
        notificationLeftButton.value = button
        context.getSharedPreferences("kotify_prefs", Context.MODE_PRIVATE)
            .edit().putString("notification_left_button", button).apply()
        MusicPlaybackService.instance?.let { svc ->
            svc.notificationLeftButton = button
            svc.updateNotification()
        }
    }

    fun setNotificationRightButton(button: String, context: Context) {
        notificationRightButton.value = button
        context.getSharedPreferences("kotify_prefs", Context.MODE_PRIVATE)
            .edit().putString("notification_right_button", button).apply()
        MusicPlaybackService.instance?.let { svc ->
            svc.notificationRightButton = button
            svc.updateNotification()
        }
    }

    fun setCanvasEnabled(enabled: Boolean, context: Context) {
        canvasEnabled.value = enabled
        context.getSharedPreferences("kotify_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("canvas_enabled", enabled).apply()
        if (!enabled) canvasUrl.value = null
    }

    private fun fetchCanvasForTrack(trackUri: String) {
        if (!canvasEnabled.value) return
        if (trackUri == lastCanvasTrackUri) return
        lastCanvasTrackUri = trackUri
        val trackId = trackUri.removePrefix("spotify:track:")
        val requestUri = trackUri // capture for async check
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sess = session ?: return@launch
                val canvas = Canvas(sess)
                val data = canvas.getCanvas(trackId)
                // Only update if this is still the current track (avoid race with rapid skips)
                if (lastCanvasTrackUri == requestUri) {
                    canvasUrl.value = data?.url
                    if (data != null) {
                        LokiLogger.i(TAG, "Canvas: ${data.url.take(60)}...")
                    }
                }
            } catch (e: Exception) {
                if (lastCanvasTrackUri == requestUri) {
                    canvasUrl.value = null
                }
                LokiLogger.d(TAG, "Canvas failed: ${e.message}")
            }
        }
    }

    fun startService(context: Context) {
        appContext = context.applicationContext
        val intent = Intent(context, MusicPlaybackService::class.java)
        context.startForegroundService(intent)
    }

    fun updateAudioOutput(context: Context) {
        val output = detectActiveAudioOutput(context)
        audioOutputName.value = output.name
        audioOutputType.value = output.type
    }

    fun wireServiceControls() {
        val svc = MusicPlaybackService.instance ?: return
        wireTransportCallbacks(svc)
        wireNotificationButtonCallbacks(svc)
        wirePlaybackLifecycleCallbacks(svc)
    }

    /** Play / pause / skip / seek — simple commands forwarded to Spotify Connect. */
    private fun wireTransportCallbacks(svc: MusicPlaybackService) {
        svc.onPlay = { togglePlayPause() }
        svc.onPause = { togglePlayPause() }
        svc.onSkipNext = {
            viewModelScope.launch(Dispatchers.IO) {
                try { player?.skipNext() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { LokiLogger.e(TAG, "svc next", e) }
            }
        }
        svc.onSkipPrevious = {
            viewModelScope.launch(Dispatchers.IO) {
                try { player?.skipPrevious() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { LokiLogger.e(TAG, "svc prev", e) }
            }
        }
        svc.onSeek = { posMs ->
            viewModelScope.launch(Dispatchers.IO) {
                try { player?.seek(posMs.toInt()) }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { LokiLogger.e(TAG, "svc seek", e) }
            }
        }
    }

    /** Like / shuffle / repeat buttons shown in the notification, plus their saved preferences. */
    private fun wireNotificationButtonCallbacks(svc: MusicPlaybackService) {
        val prefs = svc.getSharedPreferences("kotify_prefs", android.content.Context.MODE_PRIVATE)
        svc.notificationLeftButton = prefs.getString("notification_left_button", "repeat") ?: "repeat"
        svc.notificationRightButton = prefs.getString("notification_right_button", "like") ?: "like"

        svc.onLikeToggle = lambda@{
            val track = _playback.value.track ?: return@lambda
            val trackId = track.uri.removePrefix("spotify:track:")
            if (currentTrackLiked.value) unlikeSong(trackId) else likeSong(trackId)
            viewModelScope.launch {
                kotlinx.coroutines.delay(300)
                svc.isLiked = currentTrackLiked.value
                svc.updateNotification()
            }
        }
        svc.onShuffleToggle = {
            toggleShuffle()
            viewModelScope.launch {
                kotlinx.coroutines.delay(300)
                svc.isShuffling = _playback.value.isShuffling
                svc.updateNotification()
            }
        }
        svc.onRepeatToggle = {
            cycleRepeat()
            viewModelScope.launch {
                kotlinx.coroutines.delay(300)
                svc.repeatMode = _playback.value.repeatMode
                svc.updateNotification()
            }
        }
    }

    /**
     * ExoPlayer lifecycle events — track transitions, errors, end-of-track,
     * and the crucial onReady that completes the cold-start handoff.
     */
    private fun wirePlaybackLifecycleCallbacks(svc: MusicPlaybackService) {
        svc.onTrackTransition = {
            // ExoPlayer auto-advanced to the pre-buffered next track. The
            // WebSocket will push the new state; just pre-resolve the next one.
            viewModelScope.launch(Dispatchers.IO) {
                try { preResolveNextTrack() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { LokiLogger.e(TAG, "svc trackTransition", e) }
            }
        }
        svc.onPlaybackError = { errorCode ->
            LokiLogger.e(TAG, "ExoPlayer error: $errorCode — stopping stream, falling back to Spotify")
            isStreaming.value = false
            streamProvider.value = null
            currentStreamUri = null
            viewModelScope.launch(Dispatchers.IO) {
                try { player?.resume() } catch (_: Exception) {}
            }
        }
        svc.onPlaybackEnded = {
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    LokiLogger.i(TAG, "ExoPlayer ended — waiting 1s for auto-advance...")
                    // Give TrackPlaybackHandler time to auto-advance via state machine
                    delay(1000)
                    // Safety net: check ExoPlayer's actual playback state, not the
                    // cached _playback.value (which only updates on WS state pushes
                    // and lags by tens of seconds when nothing's happening). If
                    // ExoPlayer is still stopped after 1s, the state machine didn't
                    // auto-advance — force skipNext to kick the next track.
                    val exoPlaying = withContext(Dispatchers.Main) {
                        MusicPlaybackService.instance?.isPlaying() == true
                    }
                    if (!exoPlaying) {
                        LokiLogger.w(TAG, "Auto-advance didn't fire (ExoPlayer still stopped), forcing skipNext")
                        player?.skipNext()
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    LokiLogger.e(TAG, "playbackEnded advance failed, stopping", e)
                    isStreaming.value = false
                    streamProvider.value = null
                    currentStreamUri = null
                    _playback.value = _playback.value.copy(isPlaying = false, isPaused = true)
                    stopPositionTicker()
                }
            }
        }
        svc.onReady = {
            val now = System.currentTimeMillis()
            val playUrlToReady = if (playUrlAt > 0) now - playUrlAt else -1L
            val cmdToReady = if (lastCommandTs > 0) now - lastCommandTs else -1L
            LokiLogger.i(TAG, "[Timing-CDN] ExoPlayer ready — playUrl→ready=${playUrlToReady}ms, cmd→ready=${cmdToReady}ms")

            if (coldStartPending) {
                // Cold-start sync: ExoPlayer was loaded with startPositionMs and
                // playWhenReady=true, so by the time onReady fires audio is already
                // producing at the right position. We just need to:
                //   1. Tell Spotify Connect to resume (so other clients show us playing)
                //   2. Update the UI playing state and start the position ticker
                //   3. Release the seek guard so normal remote-seek sync can take over
                //   4. Hide the loading spinner
                val pos = MusicPlaybackService.instance?.getCurrentPosition() ?: _playback.value.positionMs
                LokiLogger.i(TAG, "[ColdStart] ExoPlayer producing at ${pos}ms — resuming Spotify Connect")
                coldStartPending = false
                _playback.value = _playback.value.copy(isPlaying = true, isPaused = false, positionMs = pos)
                startPositionTicker()
                // Hold the seek guard for 15s after cold-start completion.
                // The transfer + resume handshake can leave Spotify's cluster
                // snapshot with a stale position_as_of_timestamp that
                // interpolates tens of seconds past the real position — our
                // remote-seek detector would then yank ExoPlayer forward and
                // break playback. 15s is long enough for stale snapshots to
                // wash out without blocking legitimate user seeks for long.
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        try { player?.resume() } catch (_: Exception) {}
                        seekGuardUntil = System.currentTimeMillis() + 15_000L
                    } finally {
                        isStreamLoading.value = false
                    }
                }
            } else {
                _playback.value = _playback.value.copy(isPlaying = true, isPaused = false)
                startPositionTicker()
                // Hold the seek guard for 5s after any fresh track load so the
                // stale position Spotify carries across a track transition
                // can't trigger a bogus remote-seek that seeks ExoPlayer past
                // the end of the new track. Five seconds is enough for the
                // cluster snapshot to catch up to position 0 of the new song.
                seekGuardUntil = System.currentTimeMillis() + 5_000L
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        // For Spotify CDN: resume Spotify so other clients show us as playing
                        if (preferredAudioSource.value == null) {
                            player?.resume()
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private suspend fun resolveAndPlay(event: kotify.api.playerstatus.TrackChangeEvent) {
        val resolveStart = System.currentTimeMillis()
        val current = event.current ?: return
        val trackUri = current.uri
        LokiLogger.i(TAG, "[Timing] resolveAndPlay start for $trackUri (${resolveStart - lastCommandTs}ms after CMD)")
        if (trackUri.startsWith("spotify:ad:")) {
            LokiLogger.i(TAG, "[AdSkip] Ad detected: $trackUri — skipping to next track")
            // Don't stop ExoPlayer — let current song keep playing while ad is skipped
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    delay(1000) // Brief delay so Spotify processes the ad
                    player?.skipNext()
                } catch (_: Exception) {}
            }
            return
        }
        if (trackUri == currentStreamUri) {
            isStreamLoading.value = false
            return
        }
        // NOTE: do NOT set currentStreamUri here. We commit to it on success only,
        // otherwise a failed resolve poisons the cache and the next attempt at
        // the same track short-circuits the equality check above and never loads.

        val title = current.name.ifBlank { "Unknown" }
        val artist = current.artistName ?: "Unknown"

        isStreamLoading.value = true
        isNextReady.value = false
        stopPositionTicker()

        // Don't stop the old song — let it keep playing until the new one is ready.
        // ExoPlayer's setMediaItem() in playUrl/playDrmUrl will seamlessly replace it.
        // Pause Spotify so it doesn't advance while we resolve the stream
        // Skip for Spotify CDN — we want Spotify to keep showing us as "playing"
        if (preferredAudioSource.value != null) {
            try { player?.pause() } catch (_: Exception) {}
        }
        val art = normalizeSpotifyImageUrl(current.imageLargeUrl ?: current.imageUrl)

        // Update UI with new track info immediately — audio will follow in ~100ms
        val newTrack = TrackInfo(
            uri = trackUri, name = title, artist = artist, albumArt = art,
            albumName = current.albumName,
            durationMs = if (current.durationMs > 0) current.durationMs else _playback.value.durationMs
        )
        _playback.value = _playback.value.copy(track = newTrack, positionMs = 0)
        if (art != null && art != lastPaletteUrl) {
            lastPaletteUrl = art
            extractColorsFromArt(art)
        }
        checkLikedState(trackUri)
        fetchCanvasForTrack(trackUri)

        // Check if we already pre-resolved this track
        val preResolvedUrl = nextStreamUrl
        val preResolvedProvider = nextStreamProvider
        if (nextTrackInfo?.uri == trackUri && preResolvedUrl != null) {
            LokiLogger.i(TAG, "Using pre-resolved stream for $trackUri")
            // Don't resume Spotify yet — onReady callback will sync after ExoPlayer buffers
            playUrlAt = System.currentTimeMillis()
            MusicPlaybackService.instance?.playUrl(preResolvedUrl, title, artist, art)
            currentStreamUri = trackUri
            isStreaming.value = true
            isStreamLoading.value = false
            streamProvider.value = preResolvedProvider
            nextStreamUrl = null
            nextTrackInfo = null
            nextStreamProvider = null
            preResolveNextTrack()
            return
        }

        LokiLogger.i(TAG, "Resolving stream for $trackUri (source=${preferredAudioSource.value})")

        // Spotify CDN path: resolve CDN URL directly from Spotify's infrastructure
        if (preferredAudioSource.value == null) {
            try {
                val resolver = cdnResolver ?: throw IllegalStateException("CdnResolver not initialized")

                // Check if we already pre-resolved this CDN URL
                // IMPORTANT: nextCdnUrl is for the NEXT track. Only use it if the
                // file ID matches what we need for the CURRENT track.
                val currentFileId = event.currentFileId ?: latestFileId
                val cachedCdnUrl = if (currentFileId != null && nextCdnFileId == currentFileId) nextCdnUrl else null
                val stream: SpotifyStream = if (cachedCdnUrl != null) {
                    LokiLogger.i(TAG, "SpotifyCDN: Using pre-resolved CDN URL (fileId=$currentFileId)")
                    nextCdnUrl = null
                    nextCdnFileId = null
                    resolver.buildStreamForCachedUrl(cachedCdnUrl)
                } else {
                    // Use file ID from cluster state or from onPlaybackId (state machine)
                    var fileId = event.currentFileId ?: latestFileId
                    if (fileId == null) {
                        // Brief wait — onPlaybackId may fire slightly after onTrackChange
                        LokiLogger.d(TAG, "SpotifyCDN: Waiting briefly for file ID...")
                        for (attempt in 1..5) {
                            delay(100)
                            fileId = latestFileId
                            if (fileId != null) break
                        }
                    }
                    // If still null, fetch file ID from track metadata API
                    if (fileId == null) {
                        LokiLogger.i(TAG, "SpotifyCDN: No file ID from state machine, fetching from metadata API...")
                        fileId = resolver.fetchFileIdFromMetadata(trackUri)
                        if (fileId != null) {
                            LokiLogger.i(TAG, "SpotifyCDN: Got file ID from metadata: $fileId")
                            latestFileId = fileId
                        }
                    }
                    if (fileId == null) {
                        throw IllegalStateException("No file ID available")
                    }
                    LokiLogger.i(TAG, "SpotifyCDN: Resolving fileId=$fileId")
                    val resolved = resolver.resolveForFileId(fileId)
                    LokiLogger.i(TAG, "SpotifyCDN: Resolved ${resolved.mirrorCount} mirrors")
                    resolved
                }
                // DRM: must stop old player to close the Widevine session cleanly.
                // Unlike non-DRM, we can't seamlessly replace — each track needs its own license.
                withContext(Dispatchers.Main) {
                    MusicPlaybackService.instance?.stop()
                }
                playUrlAt = System.currentTimeMillis()
                val coldStart = coldStartPending
                // Extend the seek guard across the entire DRM load + ready
                // window. playDrmUrl posts to the main handler and returns
                // instantly; the actual STATE_READY fires 1-2s later. Between
                // those two points ExoPlayer reports position 0, and if a
                // stale cluster snapshot interpolates tens of seconds forward
                // the remote-seek detector would yank ExoPlayer past the
                // start of the new track — the song would effectively be
                // skipped because STATE_READY would fire at the seeked
                // position, not at 0.
                seekGuardUntil = System.currentTimeMillis() + 5_000L
                withContext(Dispatchers.Main) {
                    MusicPlaybackService.instance?.playDrmUrl(
                        stream.cdnUrl, stream.licenseUrl, stream.licenseHeaders, title, artist, art,
                        startPlaying = !coldStart,
                    )
                }
                currentStreamUri = trackUri
                isStreaming.value = true
                streamProvider.value = "Spotify CDN"
                isStreamLoading.value = false
                LokiLogger.i(TAG, "[Timing] resolveAndPlay DRM loaded in ${System.currentTimeMillis() - resolveStart}ms (${System.currentTimeMillis() - lastCommandTs}ms total from CMD)")
                preResolveNextTrack()
                return
            } catch (e: Exception) {
                LokiLogger.w(TAG, "Spotify CDN failed: ${e.message}, falling back to third-party CDN")
            }
        }

        try {
            val result = cdn.resolveFromTrack(event, region = contentRegion.value, preferredSource = preferredAudioSource.value)
            when (result) {
                is StreamResult.Success -> {
                    // Don't resume Spotify yet — onReady callback will sync after ExoPlayer buffers
                    playUrlAt = System.currentTimeMillis()
                    MusicPlaybackService.instance?.playUrl(result.info.url, title, artist, art)
                    currentStreamUri = trackUri
                    isStreaming.value = true
                    streamProvider.value = result.info.provider
                    LokiLogger.i(TAG, "Streaming: ${result.info.provider} -> ${result.info.url.take(80)}")
                }
                is StreamResult.YouTubeFallback -> {
                    LokiLogger.w(TAG, "YouTube fallback: ${result.url}")
                    MusicPlaybackService.instance?.stop()
                    isStreaming.value = false
                    streamProvider.value = null
                }
                is StreamResult.Failure -> {
                    LokiLogger.e(TAG, "Stream resolve failed: ${result.message}")
                    MusicPlaybackService.instance?.stop()
                    isStreaming.value = false
                    streamProvider.value = null
                }
            }
        } catch (e: Exception) {
            LokiLogger.e(TAG, "resolveAndPlay failed", e)
        } finally {
            isStreamLoading.value = false
        }

        preResolveNextTrack()
    }

    private fun resolveCurrentTrack(state: PlayerStateData) {
        val track = state.track ?: return
        val uri = track.uri
        if (uri == currentStreamUri) return
        // NOTE: do NOT set currentStreamUri here. Setting it before the resolve
        // succeeds poisons future attempts: if the resolve fails (rate limit, no
        // CDN match, network error) the URI sticks and the next time the user
        // tries to play this track, resolveAndPlay's `if (trackUri == currentStreamUri)`
        // short-circuits and nothing ever loads. Set it on success only.
        isStreamLoading.value = true
        isNextReady.value = false
        val startPositionMs = state.position_as_of_timestamp
        _playback.value = _playback.value.copy(positionMs = startPositionMs)
        stopPositionTicker()

        val shouldPlay = state.isActuallyPlaying
        val trackId = uri.removePrefix("spotify:track:")
        val title = track.name.ifBlank { null }
        val artist = track.artistName?.ifBlank { null }
        val searchQuery = listOfNotNull(artist, title).joinToString(" ").takeIf { it.isNotBlank() }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val art = normalizeSpotifyImageUrl(track.imageLargeUrl ?: track.imageUrl)

                val result = cdn.resolveStreamUrl(trackId, region = contentRegion.value, youtubeSearchQuery = searchQuery, preferredSource = preferredAudioSource.value)
                when (result) {
                    is StreamResult.Success -> {
                        playUrlAt = System.currentTimeMillis()
                        MusicPlaybackService.instance?.playUrl(
                            result.info.url,
                            title ?: "Unknown",
                            artist ?: "Unknown",
                            art,
                            startPlaying = shouldPlay,
                        )
                        currentStreamUri = uri
                        isStreaming.value = true
                        streamProvider.value = result.info.provider
                        LokiLogger.i(TAG, "Initial stream: ${result.info.provider} (playing=$shouldPlay)")
                    }
                    is StreamResult.YouTubeFallback -> {
                        LokiLogger.w(TAG, "resolveCurrentTrack: YouTube fallback, not streaming")
                        isStreaming.value = false
                        streamProvider.value = null
                    }
                    is StreamResult.Failure -> {
                        LokiLogger.e(TAG, "resolveCurrentTrack: stream resolution failed: ${result.message}")
                        isStreaming.value = false
                        streamProvider.value = null
                    }
                }
            } catch (e: Exception) {
                LokiLogger.e(TAG, "resolveCurrentTrack failed", e)
            } finally {
                isStreamLoading.value = false
            }
            preResolveNextTrack()
        }
    }

    @Suppress("UNCHECKED_CAST")
    private suspend fun preResolveNextTrack() {
        // Skip pre-resolution for Spotify CDN — file IDs only come at play time from the state machine
        if (preferredAudioSource.value == null) {
            isNextReady.value = true
            return
        }

        try {
            val state = player?.getState() ?: return
            val nextTrack = state.next_tracks.firstOrNull() ?: return
            val nextUri = nextTrack.uri
            if (nextUri.startsWith("spotify:ad:")) {
                LokiLogger.i(TAG, "[AdSkip] Skipping ad URI in preResolveNextTrack: $nextUri")
                return
            }
            val nextId = nextUri.removePrefix("spotify:track:")

            val title = nextTrack.name ?: "Unknown"
            val artist = nextTrack.artistName ?: "Unknown"
            val art = normalizeSpotifyImageUrl(nextTrack.imageUrl)
            val searchQuery = listOfNotNull(artist.takeIf { it != "Unknown" }, title.takeIf { it != "Unknown" })
                .joinToString(" ").takeIf { it.isNotBlank() }

            LokiLogger.i(TAG, "Pre-resolving next: $title by $artist")
            val result = cdn.resolveStreamUrl(nextId, region = contentRegion.value, youtubeSearchQuery = searchQuery, preferredSource = preferredAudioSource.value)
            if (result is StreamResult.Success) {
                nextStreamUrl = result.info.url
                nextTrackInfo = TrackInfo(uri = nextUri, name = title, artist = artist, albumArt = art)
                nextStreamProvider = result.info.provider
                MusicPlaybackService.instance?.setNextUrl(result.info.url, title, artist, art)
                isNextReady.value = true
                LokiLogger.i(TAG, "Next track pre-resolved: ${result.info.provider}")
            }
        } catch (e: Exception) {
            LokiLogger.e(TAG, "preResolveNextTrack failed", e)
        }
    }

    // --- Home ---

    private fun loadHome() {
        launchWithSession("loadHome") { sess ->
            try {
                val feed = Home(sess).getHomeFeed()
                _homeData.value = feed
                LokiLogger.i(TAG, "Home loaded: ${feed?.sections?.size} sections")
            } finally {
                isHomeLoading.value = false
            }
        }
    }

    // --- Search ---

    fun updateSearchQuery(query: String) {
        searchQuery.value = query
        if (query.length < 2) {
            _searchResults.value = emptyList()
            searchJob?.cancel()
            return
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch(Dispatchers.IO) {
            delay(400) // debounce
            isSearching.value = true
            try {
                val sess = session ?: return@launch
                val results = Song(sess).search(query, limit = 30)
                val trackList = results.tracks.items.map { it.toTrackInfo() }
                _searchResults.value = trackList
                LokiLogger.i(TAG, "Search '$query': ${trackList.size} results")
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                LokiLogger.e(TAG, "search", e)
            } finally {
                isSearching.value = false
            }
        }
    }


    // --- Library ---

    fun loadLibrary() {
        launchWithSession("loadLibrary") { sess ->
            _library.value = Playlist(sess).getLibrary(limit = 50).toUiLibraryList()
        }
    }


    // --- Detail ---

    private val _isLoadingMore = MutableStateFlow(false)
    val isLoadingMore: StateFlow<Boolean> = _isLoadingMore

    fun openLikedSongs() {
        navigateTo(Screen.PLAYLIST_DETAIL)
        viewModelScope.launch(Dispatchers.IO) {
            isLoading.value = true
            try {
                val sess = session ?: return@launch
                val data = Playlist(sess).getLikedSongs(limit = 50)
                _detail.value = data.toDetailData(offset = 0)
            } catch (e: Exception) { LokiLogger.e(TAG, "openLikedSongs", e) }
            finally { isLoading.value = false }
        }
    }

    fun loadMoreDetail() {
        val current = _detail.value
        if (_isLoadingMore.value) return
        if (current.totalCount in 0..current.tracks.size) return
        val uri = current.uri
        _isLoadingMore.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sess = session ?: return@launch
                val offset = current.tracks.size
                if (uri == "spotify:collection:tracks") {
                    val data = Playlist(sess).getLikedSongs(limit = 50, offset = offset)
                    val more = data.toDetailData(offset)
                    _detail.value = current.copy(
                        tracks = current.tracks + more.tracks,
                        totalCount = more.totalCount,
                        loadedOffset = offset + more.tracks.size
                    )
                } else if (uri.startsWith("spotify:playlist:")) {
                    val id = uri.removePrefix("spotify:playlist:")
                    val info = Playlist(sess).getPlaylist(id, limit = 50, offset = offset)
                    val more = info.tracks.map { it.toTrackInfo() }
                    _detail.value = current.copy(
                        tracks = current.tracks + more,
                        totalCount = info.totalTracks,
                        loadedOffset = offset + more.size
                    )
                }
            } catch (e: Exception) { LokiLogger.e(TAG, "loadMoreDetail", e) }
            finally { _isLoadingMore.value = false }
        }
    }


    fun openPlaylist(playlistId: String) {
        navigateTo(Screen.PLAYLIST_DETAIL)
        viewModelScope.launch(Dispatchers.IO) {
            isLoading.value = true
            try {
                val sess = session ?: return@launch
                _detail.value = Playlist(sess).getPlaylist(playlistId, limit = 50).toDetailData(playlistId)
            } catch (e: Exception) { LokiLogger.e(TAG, "openPlaylist", e) }
            finally { isLoading.value = false }
        }
    }


    fun openAlbum(albumId: String) {
        navigateTo(Screen.ALBUM_DETAIL)
        viewModelScope.launch(Dispatchers.IO) {
            isLoading.value = true
            try {
                val sess = session ?: return@launch
                _detail.value = Album(sess).getAlbum(albumId, limit = 50).toDetailData(albumId)
            } catch (e: Exception) { LokiLogger.e(TAG, "openAlbum", e) }
            finally { isLoading.value = false }
        }
    }


    fun openArtist(artistId: String) {
        navigateTo(Screen.ARTIST_DETAIL)
        viewModelScope.launch(Dispatchers.IO) {
            isLoading.value = true
            try {
                val sess = session ?: return@launch
                _detail.value = Artist(sess).getArtist(artistId).toDetailData(artistId)
            } catch (e: Exception) { LokiLogger.e(TAG, "openArtist", e) }
            finally { isLoading.value = false }
        }
    }


    // --- Library actions (save/follow) ---

    val detailSaved = MutableStateFlow(false)

    fun checkDetailSaved(type: String, id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sess = session ?: return@launch
                detailSaved.value = when (type) {
                    "album" -> Album(sess).isSaved(id)
                    "artist" -> Artist(sess).isFollowing(id)
                    else -> false
                }
            } catch (_: Exception) { detailSaved.value = false }
        }
    }

    fun toggleDetailSaved(type: String, id: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sess = session ?: return@launch
                val currentlySaved = detailSaved.value
                when (type) {
                    "album" -> if (currentlySaved) Album(sess).removeFromLibrary(id) else Album(sess).saveToLibrary(id)
                    "artist" -> if (currentlySaved) Artist(sess).unfollow(id) else Artist(sess).follow(id)
                }
                detailSaved.value = !currentlySaved
            } catch (e: Exception) { LokiLogger.e(TAG, "toggleDetailSaved", e) }
        }
    }

    fun removeFromLibrary(item: ch.snepilatch.app.data.LibraryItem) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sess = session ?: return@launch
                val id = item.uri.substringAfterLast(":")
                when (item.type) {
                    "album" -> Album(sess).removeFromLibrary(id)
                    "artist" -> Artist(sess).unfollow(id)
                    "playlist" -> kotify.api.playlist.Playlist(sess).deletePlaylist(id, username)
                }
                loadLibrary()
            } catch (e: Exception) { LokiLogger.e(TAG, "removeFromLibrary", e) }
        }
    }

    // --- Devices ---

    fun loadDevices() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val devicesInfo = player?.getDevices() ?: return@launch
                // Filter out hobs_ duplicates — they're internal Spotify IDs for the same device
                _devices.value = devicesInfo.devices.filter { !it.key.startsWith("hobs_") }.values.toList()
                val activeId = devicesInfo.activeDeviceId
                LokiLogger.i(TAG, "Devices: ${devicesInfo.devices.keys}, activeId=$activeId")
                activeDeviceName.value = if (activeId != null) {
                    // Try exact match first, then with/without hobs_ prefix
                    devicesInfo.devices[activeId]?.name
                        ?: devicesInfo.devices["hobs_$activeId"]?.name
                        ?: devicesInfo.devices.entries.firstOrNull { it.key == activeId || it.key == "hobs_$activeId" || "hobs_${it.key}" == activeId }?.value?.name
                } else null
                LokiLogger.i(TAG, "Active device name: ${activeDeviceName.value}")
            } catch (e: Exception) { LokiLogger.e(TAG, "loadDevices", e) }
        }
    }

    fun transferPlayback(deviceId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                player?.transferPlaybackTo(deviceId)
                delay(500)
                refreshState()
                loadDevices()
                showDevices.value = false
            } catch (e: Exception) { LokiLogger.e(TAG, "transferPlayback", e) }
        }
    }

    // --- Playlist Management ---

    fun createPlaylist(name: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sess = session ?: return@launch
                Playlist(sess).createPlaylist(name, username)
                delay(1000)
                loadLibrary()
            } catch (e: Exception) { LokiLogger.e(TAG, "createPlaylist", e) }
        }
    }

    fun followArtist(artistId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try { session?.let { Artist(it).follow(artistId) } }
            catch (e: Exception) { LokiLogger.e(TAG, "follow", e) }
        }
    }

    fun unfollowArtist(artistId: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try { session?.let { Artist(it).unfollow(artistId) } }
            catch (e: Exception) { LokiLogger.e(TAG, "unfollow", e) }
        }
    }

    private fun extractColorsFromArt(imageUrl: String) {
        val ctx = appContext ?: return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val colors = extractThemeColorsFromArt(ctx, imageUrl)
                if (colors != null) {
                    themeColors.value = colors
                    LokiLogger.d(TAG, "Palette updated from $imageUrl")
                }
            } catch (e: Exception) {
                LokiLogger.e(TAG, "extractColors", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        stopPositionTicker()
        // Kill everything — disconnect player and clear the process-level holder.
        val p = SessionHolder.player
        SessionHolder.clear()
        if (p != null) {
            Thread {
                kotlinx.coroutines.runBlocking {
                    try { p.disconnect() } catch (_: Exception) {}
                }
            }.start()
        }
    }
}
