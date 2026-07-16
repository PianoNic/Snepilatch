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
import kotify.api.podcast.Podcast
import kotify.api.playerstatus.DeviceInfo
import kotify.api.playerstatus.PlayerStateData
import kotify.api.playerstatus.PlayerTrack
import kotify.api.lyrics.Lyrics
import kotify.api.lyrics.LyricsData
import kotify.api.song.Song
import kotify.api.user.User
import kotify.api.canvas.Canvas
import kotify.cdn.CdnPlayback
import kotify.cdn.SpotifyPlayback
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
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.coroutines.cancellation.CancellationException

@Suppress("TooManyFunctions") // central view-model; split-by-feature is tracked separately
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

    // Direct https audio URLs for external/RSS podcast episodes (no Spotify file id, no DRM), keyed by
    // episode uri and pushed via onExternalUrl. Small bounded cache; hosted content never appears here.
    private val externalUrlByUri = java.util.Collections.synchronizedMap(
        object : LinkedHashMap<String, String>(16, 0.75f, false) {
            override fun removeEldestEntry(eldest: Map.Entry<String, String>) = size > 32
        }
    )
    private var currentStreamUri: String? = null
    private var nextStreamUrl: String? = null
    private var nextTrackInfo: TrackInfo? = null
    private var nextStreamProvider: String? = null

    // Request headers the pre-resolved next stream needs (anandserver X-API-Key);
    // empty for direct URLs and Deezer (which is pre-registered with the proxy).
    private var nextStreamHeaders: Map<String, String> = emptyMap()

    // Set when the user explicitly taps a track to play (playTrack). Lets the
    // next onTrackChange resolve+play locally even from an idle/not-streaming
    // start — otherwise the guard meant for passive init pushes swallows it and
    // the first song after launch plays on Spotify's side with no local audio.
    private var pendingUserPlay = false
    private var nextCdnUrl: String? = null      // Pre-resolved Spotify CDN URL (DRM)
    private var nextCdnFileId: String? = null   // File ID for the pre-resolved CDN track
    private var lastCommandTs: Long = 0L  // timing: when last user command was sent
    private var lastCommandName: String = ""

    // Diagnostic: wall-clock when the current ad-skip began (onAd). Milestones log deltas against it
    // so we can see exactly where a single-ad skip spends its ~3s (silent clip / advance / post-ad
    // resolve). Reset to 0 once the post-ad real track's audio is producing.
    private var adSkipStartTs: Long = 0L
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

    // Auto-recovery budget for transient ExoPlayer/DRM errors (e.g. a throttled Widevine license):
    // instead of going silent until the user taps play, re-resolve + reload the SAME track at its last
    // position. Refilled when a DIFFERENT track reaches onReady; exhausting it skips forward so a
    // genuinely unplayable track can't loop forever. See [recoveringUri] for why "different" matters.
    private var playbackErrorRetries = 0

    // The uri currently being auto-recovered. A recovery reload of the SAME failing track also reaches
    // onReady (it buffers fine, then fails again at the same spot), so resetting the retry budget on
    // every onReady pinned recovery to mirror #1 forever — the track never escalated mirrors or
    // skipped. We only refill the budget when onReady fires for a uri OTHER than the one we're
    // recovering, i.e. playback genuinely moved on to a healthy track.
    private var recoveringUri: String? = null
    val isStreaming = MutableStateFlow(false)
    val streamProvider = MutableStateFlow<String?>(null)
    val isNextReady = MutableStateFlow(false)

    private var suppressRemotePause = false

    // Set when an end-of-track advance fails because the transport dropped (e.g. a Wi-Fi/cell
    // handover times the connect-state request out). Like the web player, we DON'T tear the session
    // down on a recoverable disconnect — we keep the stream and re-fire the advance once the dealer
    // reconnects (mirrors the JS _onConnectionId → register → resume path).
    @Volatile
    private var advancePendingReconnect = false
    private var resolveJob: Job? = null  // Cancel in-flight resolveAndPlay when a new track arrives


    // Account
    private val _account = MutableStateFlow(AccountInfo())
    val account: StateFlow<AccountInfo> = _account

    // Playback (internal so tests can seed state without going through the
    // network-dependent initialize/resolve paths)
    internal val _playback = MutableStateFlow(PlaybackUiState())
    val playback: StateFlow<PlaybackUiState> = _playback

    // Minimal projections of playback for list rows. Subscribing a row to the whole
    // PlaybackUiState recomposes it on every position tick (the interpolator updates
    // positionMs several times a second), so a list of N visible rows recomposes N times
    // per tick — the scroll jank. A row only needs to know whether *it* is the current
    // track and whether playback is active; distinctUntilChanged collapses the ticks so
    // these emit only on an actual track change or play/pause.
    val currentTrackUri: StateFlow<String?> = _playback
        .map { it.track?.uri }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)
    val isPlayingFlow: StateFlow<Boolean> = _playback
        .map { it.isPlaying }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)
    private val positionInterpolator = PositionInterpolator(
        scope = viewModelScope,
        playback = _playback,
        isStreaming = isStreaming,
        getExoPositionMs = { MusicPlaybackService.instance?.getCurrentPosition() },
        reportPosition = { pos -> player?.reportPosition(pos, _playback.value.isPaused) ?: Unit }
    )
    private var commandJob: Job? = null
    private var userPlayJob: Job? = null  // Cancel an in-flight user-initiated play when another track is tapped

    // Home
    private val _homeData = MutableStateFlow<HomeData?>(null)
    val homeData: StateFlow<HomeData?> = _homeData
    val isHomeLoading = MutableStateFlow(true)

    // Search state was extracted into SearchViewModel — see viewmodel/SearchViewModel.kt

    // Library
    private val _library = MutableStateFlow<List<LibraryItem>>(emptyList())
    val library: StateFlow<List<LibraryItem>> = _library
    private val _libraryTotal = MutableStateFlow(-1)
    val libraryTotal: StateFlow<Int> = _libraryTotal
    private val _isLoadingMoreLibrary = MutableStateFlow(false)
    val isLoadingMoreLibrary: StateFlow<Boolean> = _isLoadingMoreLibrary

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

    // Audio source preference: null = Spotify (default), "lossless" = third-party FLAC chain.
    val preferredAudioSource = MutableStateFlow<String?>(null)
    // Lyrics animation direction for line-synced (non word-synced): "vertical" or "horizontal"
    val lyricsAnimDirection = MutableStateFlow("vertical")
    // Language preference: "system", "en", "de", "ru", "gsw"
    val appLanguage = MutableStateFlow("system")
    // Notification button preferences: "like", "shuffle", "repeat"
    val notificationLeftButton = MutableStateFlow("repeat")
    val notificationRightButton = MutableStateFlow("like")
    // Content region for CDN resolution
    val contentRegion = MutableStateFlow("nearest")

    // Player background style: true = album-colour gradient (Spotify/YTM style), false = blurred art.
    val playerGradientBg = MutableStateFlow(true)

    // Canvas background
    val canvasEnabled = MutableStateFlow(false)
    val canvasUrl = MutableStateFlow<String?>(null)
    private var lastCanvasTrackUri: String? = null

    // Lyrics
    private val _lyrics = MutableStateFlow<LyricsData?>(null)
    val lyrics: StateFlow<LyricsData?> = _lyrics
    val isLyricsLoading = MutableStateFlow(false)
    private var lastLyricsTrackUri: String? = null


    // Single-flight guard for onAuthLost recovery so a run of anonymous-token blips can't spawn
    // overlapping re-inits. Reset when initialization reaches a terminal state (success / give-up /
    // surfaced sign-in).
    @Volatile private var authRecovering = false

    /**
     * Handle a genuinely-lost session: try to silently re-authenticate from the stored cookies, and
     * only prompt for a fresh sign-in if there are none. Spotify sometimes hands back a transient
     * anonymous token for a still-valid cookie, so a re-init usually recovers without the user ever
     * seeing the "connection lost" prompt. [initialize] carries its own bounded rate-limit retry, so
     * a truly dead cookie still lands on the sign-in gate after those attempts are exhausted.
     */
    private fun recoverAuthOrPromptLogin() {
        if (authRecovering) return
        authRecovering = true
        viewModelScope.launch(Dispatchers.IO) {
            val ctx = MusicPlaybackService.instance as? android.content.Context
            val savedCookies = ctx?.let { ch.snepilatch.app.util.loadCookies(it) }
            if (savedCookies == null) {
                surfaceAuthLost()
            } else {
                LokiLogger.i(TAG, "Auth recovery: re-initializing session from saved cookies")
                initialize(savedCookies)
            }
        }
    }

    /** Terminal "you must sign in again" state — the notification + now-playing error + loading gate. */
    private fun surfaceAuthLost() {
        authRecovering = false
        initError.value = "Lost Spotify session — sign in again to continue"
        isInitialized.value = false
        MusicPlaybackService.instance?.showError(
            "Snepilatch — connection lost",
            "Tap to reconnect to Spotify"
        )
    }

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

                // KotifyClient now fires this ONLY when the login is genuinely dead (Spotify handed
                // back an anonymous token — an expired/revoked sp_dc). Transient dealer/network trouble
                // retries forever inside KotifyClient and never lands here. But Spotify occasionally
                // returns a transient anonymous token even for a live cookie, so instead of dead-ending
                // the user we first try to silently re-authenticate from the stored cookies; only when
                // that's impossible (no saved cookies) do we surface the sign-in prompt.
                sess.onAuthLost = {
                    LokiLogger.e(TAG, "Session auth lost (onAuthLost) — attempting silent recovery")
                    recoverAuthOrPromptLogin()
                }
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
                authRecovering = false
                // Session is healthy again — dismiss any lingering "connection lost" alert.
                MusicPlaybackService.instance?.clearError()

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

                // No background token-refresh loop: KotifyClient provisions tokens proactively before
                // every request (access token refreshed before expiry, client token on its
                // refresh_after_seconds) and on dealer reconnect, so manual refresh here is redundant.

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
                        authRecovering = false
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
     * A remote controller seeked the current track (KotifyClient surfaced the inbound `seek_to`
     * command). Seek ExoPlayer to the exact target — the web player's only seek path, replacing the
     * old position-diffing heuristic. Only act when we're the streaming device. Public for the rig.
     *
     * A legitimate seek is always within the current track. A stale cloud snapshot can produce a
     * wildly out-of-range target (e.g. 23 min into a 3-min song); applying it would seek ExoPlayer
     * past the end and instantly kill the track. The web player never hits this — the media element
     * clamps currentTime to [0, duration] — so we ignore any target outside the known duration.
     */
    internal fun handleRemoteSeek(positionMs: Long) {
        if (!isStreaming.value) return
        val duration = _playback.value.durationMs
        if (positionMs < 0 || (duration > 0 && positionMs > duration + SEEK_BOUNDS_TOLERANCE_MS)) {
            LokiLogger.w(TAG, "Ignoring out-of-range remote seek -> ${positionMs}ms (duration=${duration}ms)")
            return
        }
        LokiLogger.i(TAG, "Remote seek -> ${positionMs}ms")
        MusicPlaybackService.instance?.syncSeek(positionMs)
        _playback.value = _playback.value.copy(positionMs = positionMs)
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

        pc.onExternalUrl { url, uri, name ->
            // External/RSS episode: no Spotify file id, no Widevine — just a direct https audio url.
            // Cache it by episode uri so resolveAndPlayEpisode can stream it straight when the echo lands
            // (onExternalUrl and onTrackChange race, exactly like onPlaybackId does).
            LokiLogger.i(TAG, "External/RSS episode audio for ${uri ?: name}: ${url.take(80)}")
            if (uri != null) externalUrlByUri[uri] = url
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

        pc.onAd { durationMs ->
            // KotifyClient signals an ad (no ad audio is ever fetched) and clocks it out in ~1s,
            // advancing to the next real track on its own. Play a local silent clip so the
            // MediaSession stays alive (no idle gap) and flip the UI to a "Skipping ad…" placeholder.
            // isAd is cleared when the next real track's state rebuilds PlaybackUiState.
            adSkipStartTs = System.currentTimeMillis()
            LokiLogger.i(TAG, "[AdTiming] onAd received (clip=${durationMs}ms) — T0")
            LokiLogger.i(TAG, "Ad — skipping with local silent clip (~${durationMs}ms)")
            // Reset position and show the ad's real (~1s) duration so the progress bar reflects the
            // short skip instead of the previous track's length.
            _playback.value = _playback.value.copy(
                isAd = true, isPlaying = true, isPaused = false, positionMs = 0, durationMs = durationMs
            )
            viewModelScope.launch(Dispatchers.Main) {
                MusicPlaybackService.instance?.playSilentAd()
            }
        }

        pc.onSeek { positionMs -> handleRemoteSeek(positionMs) }

        pc.onState { state ->
            val delta = if (lastCommandTs > 0) System.currentTimeMillis() - lastCommandTs else -1
            LokiLogger.i(TAG, "[Timing] WS onState arrived (${delta}ms after CMD '$lastCommandName')")
            viewModelScope.launch { updatePlaybackFromState(state) }
        }

        pc.onTrackChange { event ->
            val delta = if (lastCommandTs > 0) System.currentTimeMillis() - lastCommandTs else -1
            LokiLogger.i(TAG, "[Timing] WS onTrackChange arrived (${delta}ms after CMD '$lastCommandName') -> ${event.current?.uri} fileId=${event.currentFileId}")
            if (adSkipStartTs > 0 && event.current?.uri?.startsWith("spotify:ad:") == false) {
                LokiLogger.i(TAG, "[AdTiming] post-ad onTrackChange -> real track (+${System.currentTimeMillis() - adSkipStartTs}ms from T0)")
            }
            // Set latestFileId from cluster state so resolveAndPlay doesn't wait for onPlaybackId
            if (event.currentFileId != null) latestFileId = event.currentFileId
            // Only auto-resolve when we're already streaming (legit track changes
            // during active playback), OR when the user just tapped a track to
            // play (pendingUserPlay). Otherwise the very first WS push on init
            // runs a futile CDN resolve, eats retries on the fallback path, AND
            // resets _playback.value.positionMs to 0 — clobbering the saved
            // snapshot position. The one-shot pendingUserPlay flag distinguishes
            // a user-initiated play from a passive idle push.
            val userPlay = pendingUserPlay
            pendingUserPlay = false
            if (!isStreaming.value && !userPlay) {
                LokiLogger.d(TAG, "Skipping resolveAndPlay: not streaming (idle WS push)")
                return@onTrackChange
            }
            resolveJob?.cancel()
            resolveJob = viewModelScope.launch(Dispatchers.IO) {
                resolveAndPlay(event)
                if (currentScreen.value == Screen.QUEUE) refreshQueue()
            }
        }

        pc.onPlay { state -> handleRemotePlay(state.position_as_of_timestamp) }

        pc.onPause { state -> handleRemotePause(state.position_as_of_timestamp) }

        pc.onReconnected {
            viewModelScope.launch(Dispatchers.IO) { resyncAfterReconnect(pc) }
        }
    }

    /**
     * Re-establish state after the dealer WebSocket reconnects: pull the live state, refresh devices,
     * resume if we were playing, and re-fire any advance the disconnect interrupted so playback
     * self-heals after a network handover. Mirrors the web player resuming once the transport
     * re-registers.
     */
    private suspend fun resyncAfterReconnect(pc: PlayerConnect) {
        val wasPlaying = withContext(Dispatchers.Main) {
            MusicPlaybackService.instance?.isPlaying() == true
        }
        LokiLogger.i(TAG, "WebSocket reconnected, re-syncing state (wasPlaying=$wasPlaying)")
        if (wasPlaying) suppressRemotePause = true
        try {
            pc.getState()?.let { updatePlaybackFromState(it) }
            loadDevices()
            if (wasPlaying) fallbackResume()
            retryPendingAdvance()  // self-heal an advance the disconnect interrupted
        } catch (e: Exception) {
            LokiLogger.e(TAG, "Failed to re-sync after reconnect", e)
        } finally {
            suppressRemotePause = false
        }
    }

    /**
     * Handle a remote "play" event from Spotify Connect (lockscreen, browser,
     * other device). Public for unit tests so they can fire the event without
     * needing a real PlayerConnect.
     */
    internal fun handleRemotePlay(positionMs: Long) {
        if (!isStreaming.value) {
            LokiLogger.i(TAG, "Spotify: play at ${positionMs}ms")
            MusicPlaybackService.instance?.syncPlay(positionMs)
        } else if (_playback.value.isPaused) {
            LokiLogger.i(TAG, "Remote play while streaming: resuming ExoPlayer")
            MusicPlaybackService.instance?.syncPlay(_playback.value.positionMs)
            _playback.value = _playback.value.copy(isPlaying = true, isPaused = false)
            startPositionTicker()
        }
    }

    /**
     * Handle a remote "pause" event from Spotify Connect. Public for unit tests.
     */
    internal fun handleRemotePause(positionMs: Long) {
        if (suppressRemotePause) {
            LokiLogger.d(TAG, "Spotify: pause suppressed (reconnecting)")
            return
        }
        if (!isStreaming.value) {
            LokiLogger.i(TAG, "Spotify: pause at ${positionMs}ms")
            MusicPlaybackService.instance?.syncPause()
        } else {
            LokiLogger.i(TAG, "Remote pause while streaming: pausing ExoPlayer")
            MusicPlaybackService.instance?.syncPause()
            _playback.value = _playback.value.copy(isPlaying = false, isPaused = true)
            stopPositionTicker()
        }
    }

    /**
     * An end-of-track advance threw. The web player never tears the session down on a recoverable
     * transport error (a Wi-Fi/cell handover that times the request out) — it keeps the session,
     * reconnects the dealer, and resumes. We mirror that: keep the stream alive, show paused, and arm
     * [advancePendingReconnect] so [onReconnected] re-fires the advance once the dealer is back. Only a
     * genuinely lost session (no PlayerConnect) falls through to a hard stop. Public for the test rig.
     */
    internal fun handleAdvanceFailure(e: Throwable) {
        if (player != null) {
            LokiLogger.w(TAG, "End-of-track advance failed (likely transport drop: ${e.message}) — arming retry on reconnect")
            advancePendingReconnect = true
            _playback.value = _playback.value.copy(isPlaying = false, isPaused = true)
            stopPositionTicker()
            return
        }
        LokiLogger.e(TAG, "playbackEnded advance failed with no player, stopping", e)
        isStreaming.value = false
        streamProvider.value = null
        currentStreamUri = null
        _playback.value = _playback.value.copy(isPlaying = false, isPaused = true)
        stopPositionTicker()
    }

    /**
     * After the dealer reconnects, re-fire an advance that the disconnect interrupted (mirrors the web
     * player resuming its queue once the transport re-registers). No-op unless one is pending. Public
     * for the test rig.
     */
    internal suspend fun retryPendingAdvance() {
        if (!advancePendingReconnect) return
        advancePendingReconnect = false
        LokiLogger.i(TAG, "Reconnected — retrying the interrupted end-of-track advance")
        try {
            player?.forceAdvance()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Still no good — re-arm; the next reconnect will try again, exactly like the web player.
            LokiLogger.w(TAG, "Advance retry after reconnect failed (${e.message}) — re-arming")
            advancePendingReconnect = true
        }
    }

    /**
     * Repeat-one (loop): when the just-ended track is set to loop, replay it from 0 instead of
     * advancing. Spotify signals loop by pointing the state machine's `advance` back to the same track
     * (next == current), which the engine reports as "exhausted" — so the loop has to be driven here,
     * on ExoPlayer, where it's instant and gapless (the web player loops the same way: advance → same
     * state). Returns true if it handled the loop (caller should not advance). Public for the test rig.
     */
    internal fun maybeLoopRepeatTrack(): Boolean {
        if (_playback.value.repeatMode != "track") return false
        LokiLogger.i(TAG, "Repeat-track on — looping current track")
        MusicPlaybackService.instance?.syncPlay(0)
        _playback.value = _playback.value.copy(isPlaying = true, isPaused = false, positionMs = 0)
        startPositionTicker()
        // Re-arm KotifyClient's clock and report position 0 so Spotify keeps counting the loop.
        launchWithPlayer("repeatLoop") { it.localSeek(0) }
        return true
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

    /**
     * Same shape as [launchWithSession] but receives the live [PlayerConnect].
     * Used by transport commands (shuffle, repeat, volume, ...) that don't
     * need to touch the session object directly.
     */
    private fun launchWithPlayer(tag: String, block: suspend (PlayerConnect) -> Unit): Job =
        viewModelScope.launch(Dispatchers.IO) {
            val pc = player ?: return@launch
            try {
                block(pc)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LokiLogger.e(TAG, tag, e)
            }
        }

    fun showLogin() {
        needsLogin.value = true
    }

    /** The player and lyrics are overlays, not pages — they never sit on the back stack. */
    private fun isOverlay(s: Screen) = s == Screen.NOW_PLAYING || s == Screen.LYRICS

    fun navigateTo(screen: Screen) {
        val current = currentScreen.value
        if (screen == current) return
        // Don't record an overlay we're leaving for a real page, else back re-opens the
        // player instead of returning to the page beneath it.
        if (!isOverlay(current) || isOverlay(screen)) screenStack.add(current)
        currentScreen.value = screen
    }

    /** Tabs are roots: switching resets the stack so back returns to Home, not a stale page. */
    fun navigateToTab(screen: Screen) {
        screenStack.clear()
        if (screen != Screen.HOME) screenStack.add(Screen.HOME)
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

    /**
     * All credited artists joined for display, e.g. "Post Malone, Swae Lee". Spotify's cluster
     * metadata lists extra artists under indexed keys which KotifyClient collects into [artistNames];
     * falls back to the single [artistName] (also the show name for episodes) then "Unknown".
     */
    private fun PlayerTrack.displayArtist(): String =
        artistNames.takeIf { it.isNotEmpty() }?.joinToString(", ") ?: artistName ?: "Unknown"

    private suspend fun updatePlaybackFromState(state: PlayerStateData) {
        val track = state.track
        val imageUrl = normalizeSpotifyImageUrl(
            track?.imageLargeUrl ?: track?.imageUrl ?: track?.imageSmallUrl
        )
        val trackInfo = if (track != null) {
            TrackInfo(
                uri = track.uri,
                name = track.name.ifBlank { "Unknown" },
                artist = track.displayArtist(),
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

        // Remote seeks are applied explicitly via pc.onSeek (KotifyClient surfaces the inbound
        // connect-state seek_to command), exactly like the web player. The active local device owns
        // its clock, so while streaming the position is read straight from ExoPlayer (its currentTime)
        // and we never reconcile against the lagging cloud snapshot.
        val posMs = when {
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
            volume = _playback.value.volume,
            // A real track state clears any in-progress ad-skip placeholder.
            isAd = false
        )

        // While we're idle (not streaming locally), push the cluster's
        // current track to the system media notification so the user sees
        // what would play if they tap the play button — both in the app
        // mini-player AND in the lockscreen / notification shade. The
        // service ignores this call if a media item is already loaded.
        if (!isStreaming.value && displayTrack != null) {
            withContext(Dispatchers.Main) {
                MusicPlaybackService.instance?.setIdleMetadata(
                    title = displayTrack.name,
                    artist = displayTrack.artist,
                    albumArtUrl = displayTrack.albumArt,
                    durationMs = displayDuration
                )
            }
        }

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
                        // Local state report — never fails the way a command can, so no transfer/retry needed.
                        p.localResume(_playback.value.positionMs)
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
                    // Local state report — never fails the way a command can, so no transfer/retry needed.
                    p.localPause(_playback.value.positionMs)
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
     * On any failure we reset state and fall back to a plain resume, so the user
     * still gets audio, just slower.
     */
    private suspend fun coldStartPlay() {
        val p = player ?: return
        if (cdnResolver == null) {
            LokiLogger.w(TAG, "[ColdStart] CdnResolver not initialized, falling back to resume")
            fallbackResume()
            return
        }

        // Capture the saved resume position NOW, before we kick off any async work
        // that could overwrite _playback.value via WS state pushes. The transfer
        // call below triggers cluster_update pushes that updatePlaybackFromState
        // happily writes back into _playback.value.positionMs.
        val savedPositionAtEntry = _playback.value.positionMs

        coldStartPending = true
        // transferPlaybackHere(restore_paused=true) makes Spotify push a *paused* cluster state.
        // That echo would hit handleRemotePause and syncPause() ExoPlayer mid-start — the stream we
        // are about to play — leaving audio silent even though onReady flips the UI to "playing", so
        // the user has to tap play a second time. The paused state is our own protocol artifact, not a
        // real remote pause, so suppress it for the cold-start window (same guard reconnect uses).
        // Cleared on success in onReady and on every failure path via resetColdStart().
        suppressRemotePause = true
        isStreamLoading.value = true
        // CompletableDeferred that gets completed when onPlaybackId fires with
        // the current track's file id. Set up BEFORE the transfer call so we
        // don't miss the push.
        val fileIdDeferred = kotlinx.coroutines.CompletableDeferred<String>()
        coldStartFileId = fileIdDeferred

        LokiLogger.i(TAG, "[ColdStart] transfer to self with restore_paused=pause")
        try {
            p.transferPlaybackHere(restorePaused = true)
        } catch (e: CancellationException) {
            resetColdStart()
            throw e
        } catch (e: Exception) {
            LokiLogger.e(TAG, "[ColdStart] transferPlaybackHere failed, falling back", e)
            resetColdStart()
            fallbackResume()
            return
        }

        // Wait for Spotify's state machine to push the file id via onPlaybackId.
        // Capped at 5s — typically arrives in <1s on a fast connection. If we
        // already have a cached latestFileId from an earlier session it'll arrive
        // even sooner because the cluster snapshot includes it.
        val fileId = kotlinx.coroutines.withTimeoutOrNull(5_000L) { fileIdDeferred.await() }
        coldStartFileId = null
        if (fileId == null) {
            LokiLogger.w(TAG, "[ColdStart] timed out waiting for file id, falling back to resume")
            resetColdStart()
            fallbackResume()
            return
        }

        // Read the current track from the snapshot (updatePlaybackFromState has
        // updated it during the transfer's WS state pushes). Fall back to the
        // saved snapshot track if needed.
        val track = _playback.value.track
        if (track == null || track.uri.isBlank()) {
            LokiLogger.w(TAG, "[ColdStart] no track in playback state after transfer, falling back")
            resetColdStart()
            fallbackResume()
            return
        }
        val trackUri = track.uri
        val title = track.name.ifBlank { "Unknown" }
        val artist = track.artist.ifBlank { "Unknown" }
        val art = track.albumArt
        LokiLogger.i(TAG, "[ColdStart] file id=$fileId for $trackUri — resolving CDN")

        try {
            val resolver = cdnResolver ?: throw IllegalStateException("CdnResolver not initialized")

            // Lossless mode: resolve via the third-party chain (Qobuz → Deezer →
            // Deezer) and play locally instead of Spotify's Widevine CDN, so
            // resume-from-idle stays consistent with the rest of the lossless flow.
            if (preferredAudioSource.value != null) {
                val trackId = trackUri.removePrefix("spotify:track:")
                val query = listOf(artist, title)
                    .filter { it.isNotBlank() && it != "Unknown" }
                    .joinToString(" ")
                val result = cdn.resolveStreamUrl(
                    trackId, region = effectiveRegion(),
                    searchQuery = query, preferredSource = preferredAudioSource.value
                )
                if (result is StreamResult.Success) {
                    val info = result.info
                    playUrlAt = System.currentTimeMillis()
                    withContext(Dispatchers.Main) {
                        val svc = MusicPlaybackService.instance
                        val key = info.decryptionKey
                        if (key != null) {
                            svc?.playDeezer(
                                info.url, key, info.headers, title, artist, art,
                                startPositionMs = savedPositionAtEntry
                            )
                        } else {
                            svc?.playUrl(
                                info.url, title, artist, art,
                                startPlaying = true, headers = info.headers, startPositionMs = savedPositionAtEntry
                            )
                        }
                    }
                    currentStreamUri = trackUri
                    isStreaming.value = true
                    streamProvider.value = info.provider
                    LokiLogger.i(TAG, "[ColdStart] lossless (${info.provider}) loading at ${savedPositionAtEntry}ms")
                    return
                }
                LokiLogger.w(TAG, "[ColdStart] lossless resolve failed, falling back to Spotify CDN")
            }

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
                    pssh = stream.pssh,
                )
            }
            currentStreamUri = trackUri
            isStreaming.value = true
            streamProvider.value = "Spotify CDN"
            LokiLogger.i(TAG, "[ColdStart] ExoPlayer loading at ${savedPositionAtEntry}ms, will start on STATE_READY")
        } catch (e: Exception) {
            LokiLogger.e(TAG, "[ColdStart] CDN/playDrmUrl failed, falling back to resume", e)
            resetColdStart()
            currentStreamUri = null
            isStreaming.value = false
            fallbackResume()
        }
    }

    /** Reset the transient cold-start handoff flags. Variant fields (file id, stream state) stay inline. */
    private fun resetColdStart() {
        coldStartPending = false
        coldStartFileId = null
        suppressRemotePause = false
        isStreamLoading.value = false
    }

    /** Hand control back to Spotify Connect (resume) — a state report that can't meaningfully fail. */
    private suspend fun fallbackResume() {
        try { player?.resume() } catch (_: Exception) {}
    }

    /**
     * Auto-recover from a transient ExoPlayer/DRM error (most commonly
     * `ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED` — a throttled Widevine license). Rather than going
     * silent until the user taps play, re-resolve the SAME track and reload it at [positionMs], up to
     * [MAX_PLAYBACK_ERROR_RETRIES] times. A fresh resolve also rebuilds the license headers with a
     * current access token, which is what clears a transient throttle. Spotify-CDN / podcast path only;
     * the lossless (third-party) path keeps the old hand-back-to-Spotify behaviour. The retry budget
     * resets on the next successful [MusicPlaybackService.onReady], so an unplayable track can't loop.
     */
    /**
     * Refill the auto-recovery retry budget when a track reaches STATE_READY — but ONLY when the ready
     * track differs from the one being recovered. A recovery reload of the same failing track also
     * reaches READY (it buffers a few seconds, then fails again at the same spot); refilling there
     * pinned recovery to mirror #1 forever, so the track never escalated mirrors or skipped. Refilling
     * only on a genuinely different (healthy) track restores the escalate-then-skip progression.
     */
    internal fun refillRetryBudgetOnReady(readyUri: String?) {
        if (readyUri != null && readyUri != recoveringUri) {
            playbackErrorRetries = 0
            recoveringUri = null
        }
    }

    internal suspend fun recoverFromPlaybackError(failedUri: String?, positionMs: Long) {
        // Nothing to reload locally, or lossless mode: hand back to Spotify (previous behaviour).
        if (failedUri == null || preferredAudioSource.value != null) {
            fallbackResume()
            return
        }
        // Mark the track under recovery so a same-track reload's onReady doesn't refill the budget.
        recoveringUri = failedUri
        if (playbackErrorRetries >= MAX_PLAYBACK_ERROR_RETRIES) {
            // Every mirror we tried still failed — the track is genuinely unplayable right now. Don't
            // sit in silence on it; skip forward to the next track (local advance, uncapped). Falls
            // back to a plain Spotify resume only if there's no live player to advance.
            playbackErrorRetries = 0
            recoveringUri = null
            val pc = player
            if (pc != null) {
                LokiLogger.w(TAG, "All CDN mirrors failed for $failedUri — skipping to next track")
                try {
                    pc.localNext()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    LokiLogger.e(TAG, "skip-on-exhaustion failed: ${e.message}")
                    fallbackResume()
                }
            } else {
                fallbackResume()
            }
            return
        }
        playbackErrorRetries++
        val attempt = playbackErrorRetries
        delay(400L * attempt) // brief backoff so a transient license throttle can clear
        LokiLogger.i(TAG, "Auto-recovering $failedUri @${positionMs}ms (attempt $attempt/$MAX_PLAYBACK_ERROR_RETRIES)")
        val recovered = try {
            reloadFailedTrack(failedUri, positionMs)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LokiLogger.e(TAG, "Auto-recovery attempt $attempt failed: ${e.message}")
            false
        }
        // The re-resolve couldn't produce audio (missing resolver/file id, or it threw) — retry within
        // budget; when the budget is spent the guard above hands back to Spotify.
        if (!recovered) recoverFromPlaybackError(failedUri, positionMs)
    }

    /**
     * Reload the given track/episode at [positionMs] with a freshly-resolved stream. Returns false
     * (rather than throwing) when it can't produce audio — no resolver, or no file id for a hosted item
     * — so the caller can retry within budget. A network/license throw from the resolver propagates.
     */
    private suspend fun reloadFailedTrack(failedUri: String, positionMs: Long): Boolean {
        val track = _playback.value.track
        val title = track?.name?.ifBlank { "Unknown" } ?: "Unknown"
        val artist = track?.artist?.ifBlank { "Unknown" } ?: "Unknown"
        val art = normalizeSpotifyImageUrl(track?.albumArt)

        // External/RSS episode: replay the direct url (no DRM) from where it stopped.
        externalUrlByUri[failedUri]?.let { externalUrl ->
            withContext(Dispatchers.Main) { MusicPlaybackService.instance?.stop() }
            playUrlAt = System.currentTimeMillis()
            withContext(Dispatchers.Main) {
                MusicPlaybackService.instance?.playUrl(
                    externalUrl, title, artist, art,
                    startPlaying = true, headers = emptyMap(), startPositionMs = positionMs
                )
            }
            commitRecoveredStream(failedUri, "Podcast (RSS)")
            LokiLogger.i(TAG, "Auto-recovered external episode $failedUri @${positionMs}ms")
            return true
        }

        // Hosted track / episode: re-resolve a fresh CDN url + license and reload at position. Prefer
        // the per-uri media self-resolve (authoritative for the failed uri) so a state-machine advance
        // can't leave us reloading the wrong file; latestFileId covers hosted episodes.
        val resolver = cdnResolver ?: return false
        val fileId = safeMediaFileId(failedUri)
            ?: latestFileId
            ?: resolver.fetchFileIdFromMetadata(failedUri)
            ?: return false
        // Rotate to a DIFFERENT CDN mirror each retry (mirror index = attempt count). storage-resolve
        // returns several edges; retrying the same dead one is what left a bad track stuck in silence.
        val stream = resolver.resolveForFileId(fileId, mirrorIndex = playbackErrorRetries)
        withContext(Dispatchers.Main) { MusicPlaybackService.instance?.stop() }
        playUrlAt = System.currentTimeMillis()
        withContext(Dispatchers.Main) {
            MusicPlaybackService.instance?.playDrmUrl(
                stream.cdnUrl, stream.licenseUrl, stream.licenseHeaders, title, artist, art,
                startPlaying = true, startPositionMs = positionMs, pssh = stream.pssh,
            )
        }
        commitRecoveredStream(failedUri, "Spotify CDN")
        LokiLogger.i(TAG, "Auto-recovered $failedUri via mirror #$playbackErrorRetries at ${positionMs}ms")
        return true
    }

    private fun commitRecoveredStream(uri: String, provider: String) {
        currentStreamUri = uri
        isStreaming.value = true
        streamProvider.value = provider
    }

    fun skipNext() {
        commandJob?.cancel()
        lastCommandTs = System.currentTimeMillis()
        lastCommandName = "skipNext"
        LokiLogger.i(TAG, "[Timing] CMD skipNext sent")
        commandJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val t0 = System.currentTimeMillis()
                // Local advance (state report, never skip-capped) — the new track loads via onPlaybackId.
                player?.localNext()
                LokiLogger.i(TAG, "[Timing] CMD skipNext API done in ${System.currentTimeMillis() - t0}ms")
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { LokiLogger.e(TAG, "skipNext", e) }
        }
    }

    fun skipPrevious() {
        // If we're more than 3s into the track, restart it instead of going to previous.
        // (Same threshold KotifyClient's localPrevious uses; restarting also seeks ExoPlayer.)
        if (_playback.value.positionMs > PREV_RESTART_THRESHOLD_MS) {
            seekTo(0)
            return
        }
        val pos = _playback.value.positionMs
        commandJob?.cancel()
        lastCommandTs = System.currentTimeMillis()
        lastCommandName = "skipPrevious"
        LokiLogger.i(TAG, "[Timing] CMD skipPrevious sent")
        commandJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val t0 = System.currentTimeMillis()
                // Local go-to-previous (state report, never skip-capped) — prev track loads via onPlaybackId.
                player?.localPrevious(pos)
                LokiLogger.i(TAG, "[Timing] CMD skipPrevious API done in ${System.currentTimeMillis() - t0}ms")
            }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { LokiLogger.e(TAG, "skipPrevious", e) }
        }
    }

    fun seekTo(positionMs: Long) {
        // Reflect the target immediately; ExoPlayer's getCurrentPosition() catches up once the
        // posted seek lands, and the position ticker then reads it straight from the player.
        _playback.value = _playback.value.copy(positionMs = positionMs)
        // Seek ExoPlayer on main thread
        viewModelScope.launch(Dispatchers.Main) {
            MusicPlaybackService.instance?.syncSeek(positionMs)
        }
        // Report the seek to Spotify locally (state report, not a seek command)
        launchWithPlayer("seek") { it.localSeek(positionMs) }
    }

    fun toggleShuffle() {
        launchWithPlayer("shuffle") { pc ->
            val newMode = if (_playback.value.isShuffling) "off" else "on"
            pc.setShuffle(newMode)
            _playback.value = _playback.value.copy(isShuffling = newMode != "off")
        }
    }

    fun cycleRepeat() {
        launchWithPlayer("repeat") { pc ->
            val newMode = when (_playback.value.repeatMode) {
                "off" -> "context"
                "context" -> "track"
                else -> "off"
            }
            pc.setRepeat(newMode)
            _playback.value = _playback.value.copy(repeatMode = newMode)
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
        launchWithPlayer("setSpotifyVolume") { it.setVolume(volumePercent) }
    }

    /** URI-only entry point (search results, home shortcuts) — no uid/metadata, the WS echo enriches it. */
    fun playTrack(trackUri: String, contextUri: String? = null) =
        playTrack(TrackInfo(uri = trackUri, name = "", artist = "", albumArt = null), contextUri)

    /**
     * Play a tapped track via the Connect "play" command, then let the WS onTrackChange echo drive
     * local playback (known-good path). The track's uid + index (carried by playlist/album rows) are
     * forwarded so a context with the same track more than once starts on the exact tapped occurrence,
     * matching the JS skip_to.
     */
    fun playTrack(track: TrackInfo, contextUri: String? = null, trackIndex: Int? = null) {
        userPlayJob?.cancel()
        userPlayJob = viewModelScope.launch(Dispatchers.IO) { startUserPlayback(track, contextUri, trackIndex) }
    }

    internal suspend fun startUserPlayback(track: TrackInfo, contextUri: String?, trackIndex: Int? = null) {
        // Honor the resulting onTrackChange even if we're starting from idle (no local audio yet).
        pendingUserPlay = true
        val pc = player ?: return
        coroutineScope {
            // Instant tap-to-play (always on): self-resolve the tapped track's audio and start
            // ExoPlayer NOW, in parallel with the Connect play command, instead of waiting for the WS
            // echo. Only for the Spotify-CDN source + a track URI, and not during a cold-start handoff.
            // On success it sets currentStreamUri so the echo's resolveAndPlay short-circuits; on
            // failure it does nothing and the echo path plays as usual. Runs as a child of this scope
            // so a rapid re-tap (which cancels userPlayJob) cancels it too.
            if (shouldInstantTap(track.uri)) {
                launch(Dispatchers.IO) { optimisticTapPlay(track) }
            }
            try {
                try {
                    pc.playTrack(track.uri, contextUri, track.uid, trackIndex)
                } catch (e: Exception) {
                    if (e.message?.contains("PLAYER_COMMAND_REJECTED") == true) {
                        LokiLogger.i(TAG, "Command rejected, transferring playback here and retrying")
                        pc.transferPlaybackHere()
                        delay(500)
                        pc.playTrack(track.uri, contextUri, track.uid, trackIndex)
                    } else throw e
                }
                delay(500)
                refreshState()
            } catch (e: Exception) { LokiLogger.e(TAG, "playTrack", e) }
        }
    }

    /**
     * Gate for optimistic tap-to-play (always on): we're on the Spotify-CDN source (the only one that
     * resolves by file id), it's a track URI, and no cold-start handoff is in flight. Extracted so the
     * call site keeps a simple condition.
     */
    private fun shouldInstantTap(trackUri: String): Boolean =
        preferredAudioSource.value == null &&
            trackUri.startsWith("spotify:track:") && !coldStartPending

    /**
     * EXPERIMENTAL (Mode 2): resolve the tapped track's audio via track-playback/v1/media and start
     * ExoPlayer immediately, without waiting for the WS command echo. Mirrors the Spotify-CDN branch of
     * [resolveAndPlay]. Best-effort: any failure logs and returns, leaving the echo path to play the
     * track the normal way. On success it commits currentStreamUri so the echo's resolveAndPlay
     * short-circuits instead of double-loading.
     */
    private suspend fun optimisticTapPlay(track: TrackInfo) {
        val t0 = System.currentTimeMillis()
        try {
            val resolver = cdnResolver ?: return
            val trackUri = track.uri
            val fileId = safeMediaFileId(trackUri) ?: run {
                LokiLogger.i(TAG, "[InstantTap] no licensable media file id for $trackUri, deferring to echo")
                return
            }
            val stream = resolver.resolveForFileId(fileId)
            // The echo may have already loaded this exact track while we were resolving — don't double-load.
            if (currentStreamUri == trackUri) return
            val title = track.name.ifBlank { "Unknown" }
            val artist = track.artist.ifBlank { "Unknown" }
            val art = normalizeSpotifyImageUrl(track.albumArt)
            // Reflect the tapped track in the UI immediately (echo's onState corrects any stale metadata).
            _playback.value = _playback.value.copy(track = track.copy(albumArt = art), positionMs = 0)
            if (art != null && art != lastPaletteUrl) { lastPaletteUrl = art; extractColorsFromArt(art) }
            checkLikedState(trackUri)
            fetchCanvasForTrack(trackUri)
            // DRM: stop the old player to close its Widevine session, then load the new track.
            withContext(Dispatchers.Main) { MusicPlaybackService.instance?.stop() }
            playUrlAt = System.currentTimeMillis()
            withContext(Dispatchers.Main) {
                MusicPlaybackService.instance?.playDrmUrl(
                    stream.cdnUrl, stream.licenseUrl, stream.licenseHeaders, title, artist, art,
                    startPlaying = true, pssh = stream.pssh,
                )
            }
            latestFileId = fileId
            currentStreamUri = trackUri
            isStreaming.value = true
            streamProvider.value = "Spotify CDN"
            isStreamLoading.value = false
            LokiLogger.i(TAG, "[InstantTap] optimistic play started in ${System.currentTimeMillis() - t0}ms for $trackUri")
            preResolveNextTrack()
        } catch (e: Exception) {
            LokiLogger.w(TAG, "[InstantTap] optimistic play failed (${e.message}); echo path will handle it")
        }
    }

    fun addTrackToPlaylist(playlistId: String, trackUri: String) {
        addTracksToPlaylist(playlistId, listOf(trackUri))
    }

    /**
     * Add many tracks to a playlist in a single API call. Used by the detail
     * header's "Add to Playlist" action when the user adds an entire album,
     * playlist, or the Liked Songs collection to another playlist.
     */
    fun addTracksToPlaylist(playlistId: String, trackUris: List<String>) {
        if (trackUris.isEmpty()) return
        launchWithSession("addTracksToPlaylist") { sess ->
            kotify.api.playlist.Playlist(sess).addToPlaylist(playlistId, trackUris)
            LokiLogger.i(TAG, "Added ${trackUris.size} tracks to playlist $playlistId")
            _snackbarMessage.tryEmit(
                if (trackUris.size == 1) "Added to playlist" else "Added ${trackUris.size} tracks to playlist"
            )
        }
    }

    // Tracks pending playlist picker. Always treated as a list so the same
    // picker dialog covers single-track adds (from TrackRow) and bulk adds
    // (from the album/playlist detail header).
    val pendingPlaylistTrackUris = MutableStateFlow<List<String>>(emptyList())
    val showPlaylistPicker = MutableStateFlow(false)

    fun showPlaylistPickerForTrack(trackUri: String) {
        showPlaylistPickerForTracks(listOf(trackUri))
    }

    fun showPlaylistPickerForTracks(trackUris: List<String>) {
        if (trackUris.isEmpty()) return
        pendingPlaylistTrackUris.value = trackUris
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
                    LokiLogger.w(TAG, "No UID for queue track, falling back to local advance")
                    repeat(index + 1) { p.localNext(); delay(300) }
                }
            } catch (e: Exception) { LokiLogger.e(TAG, "skipToQueueIndex", e) }
        }
    }

    fun addToQueue(trackUri: String) {
        addAllToQueue(listOf(trackUri))
    }

    /**
     * Queue multiple tracks in a single Connect call. Used by the detail
     * header's "Add to Queue" action.
     */
    fun addAllToQueue(trackUris: List<String>) {
        if (trackUris.isEmpty()) return
        viewModelScope.launch(Dispatchers.IO) {
            try {
                player?.addToQueue(trackUris)
                _snackbarMessage.tryEmit(
                    if (trackUris.size == 1) "Added to queue" else "Added ${trackUris.size} tracks to queue"
                )
            }
            catch (e: Exception) { LokiLogger.e(TAG, "addAllToQueue", e) }
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
        launchWithSession("likeSong") { sess ->
            Song(sess).likeSong(trackId)
            currentTrackLiked.value = true
            _snackbarMessage.tryEmit("Added to Liked Songs")
        }
    }

    fun unlikeSong(trackId: String) {
        launchWithSession("unlikeSong") { sess ->
            Song(sess).unlikeSong(trackId)
            currentTrackLiked.value = false
            _snackbarMessage.tryEmit("Removed from Liked Songs")
        }
    }

    private fun checkLikedState(trackUri: String) {
        if (trackUri == lastLikeCheckUri) return
        lastLikeCheckUri = trackUri
        // isLiked is the track-library check; it doesn't apply to podcast episodes (a different API).
        if (!trackUri.startsWith("spotify:track:")) {
            currentTrackLiked.value = false
            return
        }
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

    /**
     * The 2-letter region passed to the CDN resolver. "nearest" resolves to the
     * device's real country at call time, preferring the mobile-network country
     * (where you physically are, roaming-aware), then the SIM's home country,
     * then the system locale region. The telephony signals are tried first
     * because the locale region only reflects the language/region *setting* — a
     * user in Switzerland with an English phone would otherwise resolve to the
     * wrong region. Any value other than "nearest" is used as-is.
     */
    private fun effectiveRegion(): String {
        if (contentRegion.value != "nearest") return contentRegion.value
        val tm = appContext?.getSystemService(Context.TELEPHONY_SERVICE) as? android.telephony.TelephonyManager
        val net = tm?.networkCountryIso
        val sim = tm?.simCountryIso
        val locale = android.content.res.Resources.getSystem().configuration.locales[0].country
        // Keep only letters so a dual-SIM phone's "ch," collapses to "ch", then
        // take the first signal that yields a 2-letter code.
        val region = listOf(net, sim, locale)
            .firstNotNullOfOrNull { it?.filter(Char::isLetter)?.take(2)?.takeIf { c -> c.length == 2 } }
            ?.uppercase(java.util.Locale.ROOT)
            ?: "US"
        LokiLogger.i(TAG, "Content region 'nearest' -> net='$net' sim='$sim' locale='$locale' -> $region")
        return region
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
        } else {
            java.util.Locale.forLanguageTag(language)
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
            val locale = java.util.Locale.forLanguageTag(lang)
            val config = context.resources.configuration
            config.setLocale(locale)
            @Suppress("DEPRECATION")
            context.resources.updateConfiguration(config, context.resources.displayMetrics)
        }
        canvasEnabled.value = prefs.getBoolean("canvas_enabled", true)
        playerGradientBg.value = prefs.getBoolean("player_gradient_bg", true)
        contentRegion.value = prefs.getString("content_region", "nearest") ?: "nearest"
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

    fun setPlayerGradientBg(enabled: Boolean, context: Context) {
        playerGradientBg.value = enabled
        context.getSharedPreferences("kotify_prefs", Context.MODE_PRIVATE)
            .edit().putBoolean("player_gradient_bg", enabled).apply()
    }

    private fun fetchCanvasForTrack(trackUri: String) {
        if (!canvasEnabled.value) return
        if (trackUri == lastCanvasTrackUri) return
        lastCanvasTrackUri = trackUri
        // Canvas is a track-only visual; podcast episodes have none. Skip the futile lookup + clear.
        if (!trackUri.startsWith("spotify:track:")) {
            canvasUrl.value = null
            return
        }
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

    /** Play / pause / skip / seek — forwarded through KotifyClient's local-device transport (uncapped). */
    private fun wireTransportCallbacks(svc: MusicPlaybackService) {
        svc.onPlay = { togglePlayPause() }
        svc.onPause = { togglePlayPause() }
        svc.onSkipNext = {
            viewModelScope.launch(Dispatchers.IO) {
                try { player?.localNext() }
                catch (e: CancellationException) { throw e }
                catch (e: Exception) { LokiLogger.e(TAG, "svc next", e) }
            }
        }
        svc.onSkipPrevious = { skipPrevious() }
        svc.onSeek = { posMs ->
            viewModelScope.launch(Dispatchers.IO) {
                try { player?.localSeek(posMs) }
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
    /**
     * When the 1s silent ad clip ends, KotifyClient's engine normally advances off the ad on its own.
     * But if it stalls (COMMAND_FAILED, a slow post-ad reveal, a dealer drop) nothing advances and we're
     * stuck ON the ad until the user manually skips. Mirror the normal-track fallback: if we're STILL on
     * the ad after the grace window (isAd set AND no real track has taken over), force the advance —
     * the same local advance a manual skip does.
     */
    private fun armAdAdvanceWatchdog() {
        val adStuckUri = currentStreamUri
        viewModelScope.launch(Dispatchers.IO) {
            try {
                delay(AD_ADVANCE_WATCHDOG_MS)
                if (_playback.value.isAd && currentStreamUri == adStuckUri) {
                    LokiLogger.w(TAG, "Ad advance didn't fire (still on the ad) — forcing local advance")
                    player?.forceAdvance()
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) { handleAdvanceFailure(e) }
        }
    }

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
            // Capture what was playing BEFORE clearing state — the recovery re-resolves this exact
            // track at this position. DRM license failures in particular are usually transient (a
            // throttled license endpoint), so we retry rather than going silent until the user taps.
            val failedUri = currentStreamUri
            val failedPos = _playback.value.positionMs
            LokiLogger.e(TAG, "ExoPlayer error: $errorCode on ${failedUri ?: "?"} @${failedPos}ms — attempting auto-recovery")
            isStreaming.value = false
            streamProvider.value = null
            currentStreamUri = null
            viewModelScope.launch(Dispatchers.IO) { recoverFromPlaybackError(failedUri, failedPos) }
        }
        svc.onPlaybackEnded = onEnded@{
            // The silent ad clip ending is not a real track end: KotifyClient's engine clocks the
            // ad out and drives the post-ad advance itself. Forcing an advance here would skip a
            // real track. Ignore — the next real track's setMediaItem replaces the clip.
            if (_playback.value.isAd) {
                if (adSkipStartTs > 0) LokiLogger.i(TAG, "[AdTiming] silent clip ended (+${System.currentTimeMillis() - adSkipStartTs}ms from T0)")
                LokiLogger.d(TAG, "Silent ad clip ended — engine drives the post-ad advance")
                armAdAdvanceWatchdog()
                return@onEnded
            }
            if (maybeLoopRepeatTrack()) return@onEnded
            // Snapshot the URI that JUST ended. If a new track gets loaded
            // (currentStreamUri changes) before our timer fires, Spotify
            // already auto-advanced naturally — do NOT force-skip, that would
            // skip a song ahead and the audio/UI desync.
            //
            // Spotify's natural onTrackChange typically lands 0.9-1.5s after
            // ExoPlayer's STATE_ENDED. A 1s window was racing it by 50-150ms
            // and causing a double-skip when the natural advance arrived just
            // after the forced skipNext. AUTO_ADVANCE_GRACE_MS gives generous
            // headroom; the silent fallback only fires when Spotify Connect
            // genuinely fails to advance.
            val endedUri = currentStreamUri
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    LokiLogger.i(TAG, "ExoPlayer ended — waiting ${AUTO_ADVANCE_GRACE_MS}ms for auto-advance...")
                    delay(AUTO_ADVANCE_GRACE_MS)
                    val newTrackLoaded = currentStreamUri != null && currentStreamUri != endedUri
                    val exoPlaying = withContext(Dispatchers.Main) {
                        MusicPlaybackService.instance?.isPlaying() == true
                    }
                    if (newTrackLoaded || exoPlaying) {
                        LokiLogger.d(TAG, "Auto-advance fired naturally (newTrackLoaded=$newTrackLoaded, exoPlaying=$exoPlaying)")
                    } else {
                        // Advance locally (no skip command) so a slightly-late auto-advance (e.g. the
                        // post-ad track) resolves without burning a Free skip and hitting the cap.
                        LokiLogger.w(TAG, "Auto-advance didn't fire (still on $endedUri), forcing local advance")
                        player?.forceAdvance()
                    }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) { handleAdvanceFailure(e) }
            }
        }
        svc.onReady = {
            val now = System.currentTimeMillis()
            val playUrlToReady = if (playUrlAt > 0) now - playUrlAt else -1L
            val cmdToReady = if (lastCommandTs > 0) now - lastCommandTs else -1L
            LokiLogger.i(TAG, "[Timing-CDN] ExoPlayer ready — playUrl→ready=${playUrlToReady}ms, cmd→ready=${cmdToReady}ms")
            if (currentStreamUri?.startsWith("spotify:ad:") == false) logAdSkipDone()
            // A track reached STATE_READY — maybe refill the transient-error retry budget.
            refillRetryBudgetOnReady(currentStreamUri)

            if (coldStartPending) {
                // Cold-start sync: ExoPlayer was loaded with startPositionMs and
                // playWhenReady=true, so by the time onReady fires audio is already
                // producing at the right position. We just need to:
                //   1. Tell Spotify Connect to resume (so other clients show us playing)
                //   2. Update the UI playing state and start the position ticker
                //   3. Hide the loading spinner
                val pos = MusicPlaybackService.instance?.getCurrentPosition() ?: _playback.value.positionMs
                LokiLogger.i(TAG, "[ColdStart] ExoPlayer producing at ${pos}ms — resuming Spotify Connect")
                coldStartPending = false
                // Cold start done: audio is producing and we're about to resume Connect, so real
                // remote pauses (e.g. from another device) must apply again.
                suppressRemotePause = false
                _playback.value = _playback.value.copy(isPlaying = true, isPaused = false, positionMs = pos)
                startPositionTicker()
                viewModelScope.launch(Dispatchers.IO) {
                    try {
                        fallbackResume()
                    } finally {
                        isStreamLoading.value = false
                    }
                }
            } else {
                _playback.value = _playback.value.copy(isPlaying = true, isPaused = false)
                startPositionTicker()
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

    /**
     * Media-endpoint file id, gated to what the account can actually license. The media endpoint
     * returns the highest offered quality (often MP4_256, format 11), but a FREE account can only get
     * a Widevine license for MP4_128 (format 10) — handing its CDM a premium file id yields
     * ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED mid-track. Return the media file id only for premium
     * accounts or a free-safe MP4_128; otherwise null so the caller relies on the connect-state file
     * id (the account's entitled quality). Fixes the regression from self-resolving the echo path via
     * the media endpoint.
     */
    internal suspend fun safeMediaFileId(trackUri: String): String? {
        val entries = cdnResolver?.resolveMediaEntries(trackUri).orEmpty()
        if (entries.isEmpty()) return null
        // Premium: take the highest quality the manifest offers (first entry). Free: the CDM can only
        // license MP4_128, so pick the free-safe format-10 (or 12=128-dual) entry the manifest also
        // carries; if it offers only premium formats, return null so we rely on the connect-state id.
        if (_account.value.isPremium) return entries.first().first
        val free = entries.firstOrNull { it.second == "10" || it.second == "12" }
        if (free == null) {
            LokiLogger.i(TAG, "No free-tier (MP4_128) media file id for $trackUri; deferring to connect-state")
        }
        return free?.first
    }

    /** Diagnostic: log the final ad-skip delta once the post-ad real track's audio is producing. */
    private fun logAdSkipDone() {
        if (adSkipStartTs <= 0) return
        LokiLogger.i(TAG, "[AdTiming] post-ad audio PRODUCING (+${System.currentTimeMillis() - adSkipStartTs}ms from T0) — skip done")
        adSkipStartTs = 0L
    }

    /** Test seam: set the account's premium flag so [safeMediaFileId] can be exercised. */
    internal fun setPremiumForTest(premium: Boolean) {
        _account.value = _account.value.copy(isPremium = premium)
    }

    /**
     * Test seam: simulate being inside the cold-start window (or reconnect) where [suppressRemotePause]
     * is held, so [handleRemotePause] must ignore the self-inflicted restore_paused echo.
     */
    internal fun setSuppressRemotePauseForTest(suppress: Boolean) {
        suppressRemotePause = suppress
    }

    private suspend fun resolveAndPlay(event: kotify.api.playerstatus.TrackChangeEvent) {
        val resolveStart = System.currentTimeMillis()
        val current = event.current ?: return
        val trackUri = current.uri
        LokiLogger.i(TAG, "[Timing] resolveAndPlay start for $trackUri (${resolveStart - lastCommandTs}ms after CMD)")
        if (trackUri.startsWith("spotify:ad:")) {
            // KotifyClient owns ad handling (lifecycle reporting + audio
            // suppression). The app must not skip or play ads — just ignore.
            LokiLogger.i(TAG, "[Ad] ignoring ad track in resolveAndPlay: $trackUri")
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
        val artist = current.displayArtist()

        isStreamLoading.value = true
        isNextReady.value = false
        stopPositionTicker()
        // A real track is loading — the queue moved on, so any advance we'd armed for a reconnect
        // retry is now satisfied. Clear it so a later reconnect can't double-advance past this track.
        advancePendingReconnect = false

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

        // Podcast episodes always resolve through Spotify — they are not on the third-party music CDNs
        // (Qobuz/Deezer/YouTube), so we must never fall back to that chain for them. Hosted episodes
        // carry a Spotify file id (Widevine, same as a track); external/RSS episodes carry a direct
        // https url surfaced via onExternalUrl. resolveAndPlayEpisode handles both and returns.
        if (trackUri.startsWith("spotify:episode:")) {
            resolveAndPlayEpisode(trackUri, event, title, artist, art, resolveStart)
            return
        }

        // Check if we already pre-resolved this track
        val preResolvedUrl = nextStreamUrl
        val preResolvedProvider = nextStreamProvider
        if (nextTrackInfo?.uri == trackUri && preResolvedUrl != null) {
            LokiLogger.i(TAG, "Using pre-resolved stream for $trackUri")
            // Don't resume Spotify yet — onReady callback will sync after ExoPlayer buffers
            playUrlAt = System.currentTimeMillis()
            MusicPlaybackService.instance?.playUrl(preResolvedUrl, title, artist, art, headers = nextStreamHeaders)
            currentStreamUri = trackUri
            isStreaming.value = true
            isStreamLoading.value = false
            streamProvider.value = preResolvedProvider
            nextStreamUrl = null
            nextTrackInfo = null
            nextStreamProvider = null
            nextStreamHeaders = emptyMap()
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
                    resolver.buildStreamForCachedUrl(cachedCdnUrl, currentFileId)
                } else {
                    // Use file ID from cluster state or from onPlaybackId (state machine)
                    var fileId = event.currentFileId ?: latestFileId
                    if (fileId == null) {
                        // Wait for onPlaybackId — the state machine pushes the account's ENTITLED file id
                        // (MP4_128 on free). Give it real time before self-resolving, because the media
                        // endpoint below only offers premium MP4_256 on many accounts, which a free CDM
                        // can't license. Cheap: only runs when the cluster hasn't supplied a file id yet.
                        LokiLogger.d(TAG, "SpotifyCDN: Waiting for state-machine file ID...")
                        for (i in 1..15) {
                            delay(100)
                            fileId = latestFileId
                            if (fileId != null) break
                        }
                    }
                    // Still null: self-resolve. Use the media endpoint only when the file id is
                    // licensable for this account (see safeMediaFileId), else metadata/4/track.
                    if (fileId == null) {
                        LokiLogger.i(TAG, "SpotifyCDN: No file ID from state machine, self-resolving...")
                        fileId = safeMediaFileId(trackUri) ?: resolver.fetchFileIdFromMetadata(trackUri)
                        if (fileId != null) {
                            LokiLogger.i(TAG, "SpotifyCDN: Got file ID from self-resolve: $fileId")
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
                withContext(Dispatchers.Main) {
                    MusicPlaybackService.instance?.playDrmUrl(
                        stream.cdnUrl, stream.licenseUrl, stream.licenseHeaders, title, artist, art,
                        startPlaying = !coldStart,
                        pssh = stream.pssh,
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
            val result = cdn.resolveFromTrack(event, region = effectiveRegion(), preferredSource = preferredAudioSource.value)
            when (result) {
                is StreamResult.Success -> {
                    // Don't resume Spotify yet — onReady callback will sync after ExoPlayer buffers
                    playUrlAt = System.currentTimeMillis()
                    val info = result.info
                    val key = info.decryptionKey
                    if (key != null) {
                        // Deezer: encrypted stream -> decrypt via the loopback proxy.
                        MusicPlaybackService.instance?.playDeezer(info.url, key, info.headers, title, artist, art)
                    } else {
                        MusicPlaybackService.instance?.playUrl(info.url, title, artist, art, headers = info.headers)
                    }
                    currentStreamUri = trackUri
                    isStreaming.value = true
                    streamProvider.value = result.info.provider
                    LokiLogger.i(TAG, "Streaming: ${result.info.provider} -> ${result.info.url.take(80)}")
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

    /**
     * Resolve + play a podcast episode. Two shapes, both fed from the same connect-state/track-playback
     * state machine (never third-party):
     *  - **Hosted** (Spotify-hosted): carries a Spotify file id (via onPlaybackId / cluster state) →
     *    Widevine CDN, identical to a track.
     *  - **External/RSS**: no file id; a direct https audio url surfaced via onExternalUrl → streamed
     *    as-is, no DRM.
     * onPlaybackId / onExternalUrl race onTrackChange, so we wait briefly for either to arrive. On
     * failure we stop cleanly — we do NOT fall back to the third-party music CDN (wrong for podcasts).
     */
    /**
     * Resolve + play an episode via soundfinder (`soundfinder/v1/unauth/episode`), the web player's
     * episode path. Passthrough episodes stream their direct DRM-free url (no Widevine); hosted
     * episodes resolve the Widevine file id with the corrected v2 seektable PSSH. Returns true if
     * playback started; false (after logging) to fall back to the state-machine path.
     */
    internal suspend fun resolveEpisodeViaSoundfinder(
        trackUri: String, title: String, artist: String, art: String?, resolveStart: Long
    ): Boolean {
        val resolver = cdnResolver ?: return false
        val episodeId = trackUri.removePrefix("spotify:episode:")
        return try {
            val ep = resolver.resolveEpisode(episodeId)
            val coldStart = coldStartPending
            val passthroughUrl = ep?.passthroughUrl?.takeIf { ep.isPassthrough }
            val fileId = ep?.fileId
            when {
                // Passthrough: the show's original DRM-free url, streamed as-is.
                passthroughUrl != null -> {
                    withContext(Dispatchers.Main) { MusicPlaybackService.instance?.stop() }
                    playUrlAt = System.currentTimeMillis()
                    withContext(Dispatchers.Main) {
                        MusicPlaybackService.instance?.playUrl(passthroughUrl, title, artist, art, startPlaying = !coldStart, headers = emptyMap())
                    }
                    commitEpisodeStream(trackUri, "Podcast")
                    LokiLogger.i(TAG, "[Episode] passthrough (direct, no DRM) for $trackUri in ${System.currentTimeMillis() - resolveStart}ms")
                    true
                }
                // Hosted: Widevine file id -> CDN + v2 PSSH + license, played like a track.
                fileId != null -> {
                    latestFileId = fileId
                    val stream = resolver.resolveForFileId(fileId)
                    withContext(Dispatchers.Main) { MusicPlaybackService.instance?.stop() }
                    playUrlAt = System.currentTimeMillis()
                    withContext(Dispatchers.Main) {
                        MusicPlaybackService.instance?.playDrmUrl(
                            stream.cdnUrl, stream.licenseUrl, stream.licenseHeaders, title, artist, art,
                            startPlaying = !coldStart, pssh = stream.pssh,
                        )
                    }
                    commitEpisodeStream(trackUri, "Spotify CDN")
                    LokiLogger.i(TAG, "[Episode] soundfinder hosted DRM loaded in ${System.currentTimeMillis() - resolveStart}ms")
                    true
                }
                else -> false
            }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LokiLogger.e(TAG, "[Episode] soundfinder path failed for $trackUri, falling back", e)
            false
        }
    }

    private fun commitEpisodeStream(trackUri: String, provider: String) {
        currentStreamUri = trackUri
        isStreaming.value = true
        streamProvider.value = provider
    }

    private suspend fun resolveAndPlayEpisode(
        trackUri: String,
        event: kotify.api.playerstatus.TrackChangeEvent,
        title: String,
        artist: String,
        art: String?,
        resolveStart: Long
    ) {
        try {
            // Primary path: resolve the episode directly via soundfinder (what the web player does).
            // Passthrough episodes hand back a direct DRM-free url (skip Widevine entirely); hosted
            // episodes carry a Widevine file id we resolve with the corrected v2 seektable PSSH. This
            // avoids racing the state machine and fixes DRM_LICENSE_ACQUISITION_FAILED on podcasts.
            if (resolveEpisodeViaSoundfinder(trackUri, title, artist, art, resolveStart)) return

            var fileId = event.currentFileId ?: latestFileId
            var externalUrl = externalUrlByUri[trackUri]
            if (fileId == null && externalUrl == null) {
                // Either callback can land just after onTrackChange — give them a moment.
                for (i in 1..8) {
                    delay(100)
                    fileId = latestFileId
                    externalUrl = externalUrlByUri[trackUri]
                    if (fileId != null || externalUrl != null) break
                }
            }

            // External/RSS: direct https url, no DRM. Stream it as-is.
            if (fileId == null && externalUrl != null) {
                val coldStart = coldStartPending
                withContext(Dispatchers.Main) { MusicPlaybackService.instance?.stop() }
                playUrlAt = System.currentTimeMillis()
                withContext(Dispatchers.Main) {
                    MusicPlaybackService.instance?.playUrl(
                        externalUrl, title, artist, art, startPlaying = !coldStart, headers = emptyMap()
                    )
                }
                currentStreamUri = trackUri
                isStreaming.value = true
                streamProvider.value = "Podcast (RSS)"
                LokiLogger.i(TAG, "[Episode] streaming external/RSS url for $trackUri")
                return
            }

            // Hosted: Spotify file id → Widevine, exactly like a track.
            if (fileId != null) {
                val resolver = cdnResolver ?: throw IllegalStateException("CdnResolver not initialized")
                latestFileId = fileId
                val stream = resolver.resolveForFileId(fileId)
                val coldStart = coldStartPending
                withContext(Dispatchers.Main) { MusicPlaybackService.instance?.stop() }
                playUrlAt = System.currentTimeMillis()
                withContext(Dispatchers.Main) {
                    MusicPlaybackService.instance?.playDrmUrl(
                        stream.cdnUrl, stream.licenseUrl, stream.licenseHeaders, title, artist, art,
                        startPlaying = !coldStart, pssh = stream.pssh,
                    )
                }
                currentStreamUri = trackUri
                isStreaming.value = true
                streamProvider.value = "Spotify CDN"
                LokiLogger.i(TAG, "[Episode] hosted DRM loaded in ${System.currentTimeMillis() - resolveStart}ms")
                return
            }

            // Neither shape resolved — fail cleanly. No third-party fallback for podcasts.
            LokiLogger.e(TAG, "[Episode] no audio for $trackUri (no file id, no external url)")
            withContext(Dispatchers.Main) { MusicPlaybackService.instance?.stop() }
            isStreaming.value = false
            streamProvider.value = null
        } catch (e: Exception) {
            LokiLogger.e(TAG, "[Episode] resolve failed for $trackUri", e)
            isStreaming.value = false
        } finally {
            isStreamLoading.value = false
        }
    }

    private fun resolveCurrentTrack(state: PlayerStateData) {
        val track = state.track ?: return
        val uri = track.uri
        if (uri == currentStreamUri) return
        // Podcast episodes never resolve via the third-party music CDN this path uses. If an episode is
        // already playing on the active device at init, let the state-machine callbacks + resolveAndPlay
        // drive it (they carry the file id / external url) rather than doing a futile third-party lookup.
        if (uri.startsWith("spotify:episode:")) {
            LokiLogger.d(TAG, "resolveCurrentTrack: skipping third-party resolve for episode $uri")
            return
        }
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

                val result = cdn.resolveStreamUrl(
                    trackId, region = effectiveRegion(),
                    searchQuery = searchQuery, preferredSource = preferredAudioSource.value
                )
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
                // Don't pre-resolve ads — KotifyClient handles them.
                LokiLogger.i(TAG, "[Ad] not pre-resolving ad URI in preResolveNextTrack: $nextUri")
                return
            }
            val nextId = nextUri.removePrefix("spotify:track:")

            val title = nextTrack.name ?: "Unknown"
            val artist = nextTrack.artistName ?: "Unknown"
            val art = normalizeSpotifyImageUrl(nextTrack.imageUrl)
            val searchQuery = listOfNotNull(artist.takeIf { it != "Unknown" }, title.takeIf { it != "Unknown" })
                .joinToString(" ").takeIf { it.isNotBlank() }

            LokiLogger.i(TAG, "Pre-resolving next: $title by $artist")
            val result = cdn.resolveStreamUrl(nextId, region = effectiveRegion(), searchQuery = searchQuery, preferredSource = preferredAudioSource.value)
            if (result is StreamResult.Success) {
                val info = result.info
                val key = info.decryptionKey
                // Deezer is encrypted — pre-register it with the proxy so the
                // enqueued URL is a plaintext localhost URL (no headers). Other
                // sources keep their request headers (anandserver X-API-Key).
                val playable: String?
                val headers: Map<String, String>
                if (key != null) {
                    playable = MusicPlaybackService.instance?.proxyUrlForDeezer(info.url, key, info.headers)
                    headers = emptyMap()
                } else {
                    playable = info.url
                    headers = info.headers
                }
                if (playable != null) {
                    nextStreamUrl = playable
                    nextStreamHeaders = headers
                    nextTrackInfo = TrackInfo(uri = nextUri, name = title, artist = artist, albumArt = art)
                    nextStreamProvider = info.provider
                    MusicPlaybackService.instance?.setNextUrl(playable, title, artist, art, headers)
                    isNextReady.value = true
                    LokiLogger.i(TAG, "Next track pre-resolved: ${info.provider}")
                }
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

    // --- Library ---

    fun loadLibrary() {
        launchWithSession("loadLibrary") { sess ->
            val page = Playlist(sess).getLibrary(limit = 50, offset = 0)
            _library.value = page.toUiLibraryList()
            _libraryTotal.value = page.total
        }
    }

    fun loadMoreLibrary() {
        if (_isLoadingMoreLibrary.value) return
        val loaded = _library.value.size
        val total = _libraryTotal.value
        if (total in 0..loaded) return
        _isLoadingMoreLibrary.value = true
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sess = session ?: return@launch
                val page = Playlist(sess).getLibrary(limit = 50, offset = loaded)
                val more = page.toUiLibraryList()
                _library.value = _library.value + more
                _libraryTotal.value = page.total
            } catch (e: Exception) { LokiLogger.e(TAG, "loadMoreLibrary", e) }
            finally { _isLoadingMoreLibrary.value = false }
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
                    val info = Playlist(sess).getPlaylist(id, limit = DETAIL_PAGE_SIZE, offset = offset)
                    val more = info.tracks.map { it.toTrackInfo() }
                    val newSize = current.tracks.size + more.size
                    // Server-reported totalTracks is unreliable (PlaylistMapper
                    // returns 0 when content.totalCount is missing). Use the
                    // page-shorter-than-limit signal as the authoritative
                    // "we're at the end" indicator instead.
                    val newTotalCount = when {
                        more.size < DETAIL_PAGE_SIZE -> newSize
                        info.totalTracks > 0 -> info.totalTracks
                        else -> -1
                    }
                    _detail.value = current.copy(
                        tracks = current.tracks + more,
                        totalCount = newTotalCount,
                        loadedOffset = newSize
                    )
                } else if (uri.startsWith("spotify:album:")) {
                    val id = uri.removePrefix("spotify:album:")
                    val info = Album(sess).getAlbum(id, limit = 50, offset = offset)
                    val more = info.tracks.map { it.toTrackInfo(info.coverArtUrl) }
                    _detail.value = current.copy(
                        tracks = current.tracks + more,
                        totalCount = info.totalTracks,
                        loadedOffset = offset + more.size
                    )
                } else if (uri.startsWith("spotify:show:")) {
                    val id = uri.removePrefix("spotify:show:")
                    val info = Podcast(sess, id).getPodcastInfo(limit = DETAIL_PAGE_SIZE, offset = offset)
                    val more = info?.episodes?.map { it.toTrackInfo(current.name) } ?: emptyList()
                    val newSize = current.tracks.size + more.size
                    // A short page means we've hit the end; otherwise keep the server-reported total.
                    val newTotalCount = if (more.size < DETAIL_PAGE_SIZE) newSize else (info?.totalEpisodes ?: newSize)
                    _detail.value = current.copy(
                        tracks = current.tracks + more,
                        totalCount = newTotalCount,
                        loadedOffset = newSize
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

    /**
     * Open a podcast show. [publisher]/[imageUrl] come from the search/library item that was tapped
     * (the `queryPodcastEpisodes` payload doesn't carry them); they fall back to the first episode's
     * cover art. Episodes render as episode-URI [TrackInfo]s and play through the normal [playTrack].
     */
    fun openShow(showId: String, publisher: String? = null, imageUrl: String? = null) {
        navigateTo(Screen.SHOW_DETAIL)
        viewModelScope.launch(Dispatchers.IO) {
            isLoading.value = true
            try {
                val sess = session ?: return@launch
                val info = Podcast(sess, showId).getPodcastInfo(limit = 50, offset = 0)
                if (info != null) {
                    _detail.value = info.toDetailData(showId, publisher, imageUrl)
                } else {
                    LokiLogger.e(TAG, "openShow: no podcast info for $showId")
                }
            } catch (e: Exception) { LokiLogger.e(TAG, "openShow", e) }
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
        launchWithPlayer("transferPlayback") { pc ->
            pc.transferPlaybackTo(deviceId)
            delay(500)
            refreshState()
            loadDevices()
            showDevices.value = false
        }
    }

    // --- Playlist Management ---

    fun createPlaylist(name: String) {
        launchWithSession("createPlaylist") { sess ->
            Playlist(sess).createPlaylist(name, username)
            delay(1000)
            loadLibrary()
        }
    }

    fun followArtist(artistId: String) {
        launchWithSession("followArtist") { sess -> Artist(sess).follow(artistId) }
    }

    fun unfollowArtist(artistId: String) {
        launchWithSession("unfollowArtist") { sess -> Artist(sess).unfollow(artistId) }
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

    companion object {
        /** How long to wait after ExoPlayer's STATE_ENDED before falling back to
         *  a forced player.skipNext(). Spotify's natural onTrackChange reliably
         *  lands within ~1.5s; 5s gives generous headroom so we don't double-skip
         *  when the natural advance arrives just past a tighter timeout. */
        private const val AUTO_ADVANCE_GRACE_MS = 5000L

        /** How long to wait after the 1s silent ad clip ends for the engine to advance off the ad on
         *  its own before we force it. The post-ad track normally lands ~2-3s after the ad started
         *  (~1-2s after the clip ends), so this gives headroom without racing the natural advance —
         *  but still unsticks a stalled ad automatically instead of leaving the user to skip manually. */
        private const val AD_ADVANCE_WATCHDOG_MS = 3000L

        // Slack allowed when bounds-checking a remote seek target against the track duration, so a
        // seek to the very end isn't rejected on rounding/boundary jitter.
        private const val SEEK_BOUNDS_TOLERANCE_MS = 1000L

        /** Page size for playlist/album detail pagination. Matches the limit
         *  passed to [kotify.api.playlist.Playlist.getPlaylist] in
         *  [loadMoreDetail]; when a page returns fewer rows than this we've
         *  reached the end regardless of any server-reported total. */
        private const val DETAIL_PAGE_SIZE = 50

        /** How many times to auto-re-resolve + reload a track (rotating CDN mirrors) after a transient
         *  ExoPlayer/DRM error before skipping to the next track. See [recoverFromPlaybackError]. */
        private const val MAX_PLAYBACK_ERROR_RETRIES = 3

        /** If skipPrevious is invoked after this many ms into the current track,
         *  restart the track instead of going to the previous one. Matches the
         *  behavior most music players use. */
        private const val PREV_RESTART_THRESHOLD_MS = 3000L
    }
}
