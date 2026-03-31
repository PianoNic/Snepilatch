package ch.snepilatch.app.viewmodel

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.drawable.BitmapDrawable
import androidx.compose.ui.graphics.Color
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.palette.graphics.Palette
import coil.ImageLoader
import coil.request.ImageRequest
import coil.request.SuccessResult
import ch.snepilatch.app.LokiLogger
import ch.snepilatch.app.MusicPlaybackService
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

    // Session state
    private var session: Session? = null
    private var player: PlayerConnect? = null
    private var username: String = ""
    val isInitialized = MutableStateFlow(false)
    val initError = MutableStateFlow<String?>(null)

    // Streaming
    private val cdn = CdnPlayback()
    private var spotifyPlayback: SpotifyPlayback? = null
    private var latestFileId: String? = null  // from TrackPlaybackHandler via onPlaybackId
    private var currentStreamUri: String? = null
    private var nextStreamUrl: String? = null
    private var nextTrackInfo: TrackInfo? = null
    private var nextStreamProvider: String? = null
    private var nextCdnUrl: String? = null      // Pre-resolved Spotify CDN URL (DRM)
    private var nextCdnFileId: String? = null   // File ID for the pre-resolved CDN track
    private var lastCommandTs: Long = 0L  // timing: when last user command was sent
    private var lastCommandName: String = ""
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
    private var positionJob: Job? = null
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

    // Audio source preference: null = auto (default chain), or "tidal", "qobuz", "youtube"
    val preferredAudioSource = MutableStateFlow<String?>(null)
    // Lyrics animation direction for line-synced (non word-synced): "vertical" or "horizontal"
    val lyricsAnimDirection = MutableStateFlow("vertical")
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sess = Session(SessionConfig(
                    identifier = "kotify-android",
                    initialCookies = cookies,
                    deviceProfile = kotify.config.DeviceProfile.CHROME_WINDOWS
                ))
                sess.load()
                session = sess
                spotifyPlayback = SpotifyPlayback(sess)
                LokiLogger.i(TAG, "Session loaded")

                // Start loading home and library immediately after session is ready
                // These run in parallel with user profile and player setup
                loadHome()
                loadLibrary()

                val userApi = User(sess)
                val profile = userApi.getCurrentUser()
                val profileMap = profile["profile"] as? Map<*, *>
                username = profileMap?.get("username")?.toString() ?: ""
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

                val phoneName = android.os.Build.MODEL
                val pc = PlayerConnect(sess, deviceName = phoneName)

                // Persist device ID to avoid ghost devices on Spotify's device list
                val svcContext = MusicPlaybackService.instance as? android.content.Context
                val devicePrefs = svcContext?.getSharedPreferences("kotify_prefs", android.content.Context.MODE_PRIVATE)
                val savedDeviceId = devicePrefs?.getString("persisted_device_id", null)
                if (savedDeviceId != null) {
                    pc.setPersistedDeviceId(savedDeviceId)
                    LokiLogger.i(TAG, "Reusing persisted device ID: $savedDeviceId")
                }
                pc.ready()
                val currentDeviceId = pc.ourDeviceId()
                if (currentDeviceId != null && currentDeviceId != savedDeviceId) {
                    devicePrefs?.edit()?.putString("persisted_device_id", currentDeviceId)?.apply()
                }
                player = pc
                LokiLogger.i(TAG, "Player ready, device: $currentDeviceId")
                loadDevices()

                pc.onPlaybackId { fileId ->
                    LokiLogger.i(TAG, "Got file ID from state machine: $fileId")
                    latestFileId = fileId
                }

                pc.onNextPlaybackId { fileId, uri, name ->
                    if (preferredAudioSource.value == null) {
                        // Deduplicate — don't re-resolve if we already have this file ID cached
                        if (fileId == nextCdnFileId && nextCdnUrl != null) return@onNextPlaybackId
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                val sp = spotifyPlayback ?: return@launch
                                // Double-check after coroutine dispatch (another callback may have resolved it)
                                if (fileId == nextCdnFileId && nextCdnUrl != null) return@launch
                                LokiLogger.d(TAG, "Pre-resolving next Spotify CDN: $name ($fileId)")
                                val cdnUrls = sp.getCdnUrls(fileId)
                                val cdnUrl = cdnUrls.firstOrNull() ?: return@launch
                                // Cache the resolved CDN URL — DON'T queue in ExoPlayer.
                                // DRM items can't be pre-queued because each needs its own
                                // Widevine license session, and rapid transitions cause key mismatches.
                                nextCdnUrl = cdnUrl
                                nextCdnFileId = fileId
                                isNextReady.value = true
                                LokiLogger.i(TAG, "Next Spotify CDN pre-resolved: $name")
                            } catch (e: Exception) {
                                LokiLogger.d(TAG, "Pre-resolve next CDN failed: ${e.message}")
                            }
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
                    LokiLogger.i(TAG, "[Timing] WS onTrackChange arrived (${delta}ms after CMD '$lastCommandName') -> ${event.current?.get("uri")}")
                    // Cancel any in-flight resolve — only the latest track change matters
                    resolveJob?.cancel()
                    resolveJob = viewModelScope.launch(Dispatchers.IO) {
                        resolveAndPlay(event)
                        if (currentScreen.value == Screen.QUEUE) refreshQueue()
                    }
                }

                pc.onPlay { state ->
                    if (!isStreaming.value) {
                        // Not streaming locally — Spotify controls ExoPlayer
                        LokiLogger.i(TAG, "Spotify: play at ${state.position_as_of_timestamp}ms")
                        MusicPlaybackService.instance?.syncPlay(state.position_as_of_timestamp)
                    }
                }

                pc.onPause { state ->
                    if (!isStreaming.value) {
                        // Not streaming locally — Spotify controls ExoPlayer
                        LokiLogger.i(TAG, "Spotify: pause at ${state.position_as_of_timestamp}ms")
                        MusicPlaybackService.instance?.syncPause()
                    } else if (suppressRemotePause) {
                        LokiLogger.d(TAG, "Spotify: pause suppressed (reconnecting)")
                    }
                }

                pc.onReconnected {
                    viewModelScope.launch(Dispatchers.IO) {
                        val wasPlaying = withContext(Dispatchers.Main) {
                            MusicPlaybackService.instance?.isPlaying() == true
                        }
                        LokiLogger.i(TAG, "WebSocket reconnected, re-syncing state (wasPlaying=$wasPlaying)")
                        // Suppress remote pause during reconnect so ExoPlayer keeps playing
                        if (wasPlaying) suppressRemotePause = true
                        try {
                            val state = pc.getState()
                            if (state != null) {
                                updatePlaybackFromState(state)
                            }
                            loadDevices()
                            // If we were playing locally, resume Spotify playback to re-sync
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

                wireServiceControls()

                // Initial state
                val state = pc.getState()
                if (state != null) {
                    updatePlaybackFromState(state)
                    // Only resolve and play if our device is active and Spotify is actually playing
                    if (state.is_active_device && state.isActuallyPlaying) {
                        resolveCurrentTrack(state)
                    }
                }
            } catch (e: Exception) {
                LokiLogger.e(TAG, "Init failed", e)
                initError.value = e.message ?: "Unknown error"
            }
        }
    }

    fun onLoginComplete(cookies: Map<String, String>) {
        needsLogin.value = false
        initError.value = null
        initialize(cookies)
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
        val metadata = track?.get("metadata") as? Map<*, *>
        // Try multiple image keys from Spotify Connect metadata
        val imageUrl = metadata?.let {
            it["image_xlarge_url"]?.toString()
                ?: it["image_large_url"]?.toString()
                ?: it["image_url"]?.toString()
                ?: it["image_small_url"]?.toString()
                ?: it["album_cover_art_url"]?.toString()
        }
        val trackInfo = if (track != null) {
            TrackInfo(
                uri = track["uri"]?.toString() ?: "",
                name = metadata?.get("title")?.toString() ?: "Unknown",
                artist = metadata?.get("artist_name")?.toString() ?: "Unknown",
                albumArt = imageUrl,
                albumName = metadata?.get("album_title")?.toString(),
                durationMs = state.duration
            )
        } else null

        // When streaming locally, ExoPlayer is the source of truth for play state and position.
        // Spotify is intentionally paused, so its state says "paused" — ignore that.
        val exoPlaying = if (isStreaming.value) {
            withContext(Dispatchers.Main) {
                MusicPlaybackService.instance?.isPlaying() == true
            }
        } else false

        val actuallyPlaying = if (isStreaming.value) exoPlaying else state.isActuallyPlaying
        val actuallyPaused = if (isStreaming.value) !exoPlaying else state.is_paused

        // When streaming, the audio source of truth is ExoPlayer, not Spotify's state.
        // If Spotify's state says track B but we're still playing track A, keep showing track A.
        val stateTrackUri = track?.get("uri")?.toString()
        val isTrackMismatch = isStreaming.value && currentStreamUri != null && stateTrackUri != currentStreamUri

        // Detect remote seek: when streaming, if Spotify's position differs significantly
        // from ExoPlayer's, someone seeked from the web player — sync ExoPlayer
        // Skip during track transitions (mismatch) to avoid false seeks
        val isSeekGuarded = System.currentTimeMillis() < seekGuardUntil
        if (isStreaming.value && !isSeekGuarded && !isStreamLoading.value && !isTrackMismatch) {
            val exoPos = withContext(Dispatchers.Main) {
                MusicPlaybackService.instance?.getCurrentPosition()
            } ?: 0L
            // Interpolate Spotify's position using timestamp
            val elapsed = (System.currentTimeMillis() - state.timestamp).coerceAtLeast(0)
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
        }

        val posMs = when {
            isSeekGuarded -> _playback.value.positionMs
            isTrackMismatch -> _playback.value.positionMs  // Keep old position during transition
            isStreamLoading.value -> 0L
            isStreaming.value -> withContext(Dispatchers.Main) {
                MusicPlaybackService.instance?.getCurrentPosition()
            } ?: state.position_as_of_timestamp
            else -> {
                // Not streaming: interpolate from timestamp
                val elapsed = (System.currentTimeMillis() - state.timestamp).coerceAtLeast(0)
                if (state.is_playing && !state.is_paused) {
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
        @Suppress("UNCHECKED_CAST")
        val nextTracks = state.next_tracks as? List<Map<String, Any?>>
        val nextMeta = nextTracks?.firstOrNull()?.let { nt ->
            val ntUri = nt["uri"]?.toString() ?: return@let null
            val ntMd = nt["metadata"] as? Map<*, *> ?: return@let null
            TrackInfo(
                uri = ntUri,
                name = ntMd["title"]?.toString() ?: "Unknown",
                artist = ntMd["artist_name"]?.toString() ?: "Unknown",
                albumArt = ntMd["image_xlarge_url"]?.toString()
                    ?: ntMd["image_large_url"]?.toString()
                    ?: ntMd["image_url"]?.toString()
            )
        }
        nextTrackPreview.value = nextMeta

        if (actuallyPlaying) {
            startPositionTicker()
        } else {
            positionJob?.cancel()
        }

        // Sync notification button states
        MusicPlaybackService.instance?.let { svc ->
            svc.isLiked = currentTrackLiked.value
            svc.isShuffling = state.is_shuffling
            svc.repeatMode = state.repeat_mode
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

        // Update playing context (album/playlist/artist/collection)
        val contextUri = state.context_uri
        val albumTitle = metadata?.get("album_title")?.toString()
        playingContext.value = when {
            contextUri == null -> null
            contextUri.contains(":collection:tracks") -> PlayingContext("Liked Songs", "Liked Songs", contextUri)
            contextUri.contains(":playlist:") -> {
                val playlistId = contextUri.substringAfter(":playlist:")
                val cached = playlistNameCache[playlistId]
                if (cached != null) {
                    PlayingContext("Playlist", cached, contextUri)
                } else {
                    if (lastContextUri != contextUri) {
                        viewModelScope.launch(Dispatchers.IO) {
                            try {
                                val sess = session ?: return@launch
                                val data = kotify.api.playlist.Playlist(sess).getPlaylist(playlistId, limit = 1)
                                val name = (data["data"] as? Map<*, *>)
                                    ?.get("playlistV2") as? Map<*, *>
                                val title = name?.get("name")?.toString()
                                if (title != null) {
                                    playlistNameCache[playlistId] = title
                                    playingContext.value = PlayingContext("Playlist", title, contextUri)
                                }
                            } catch (_: Exception) {}
                        }
                    }
                    PlayingContext("Playlist", playlistNameCache[playlistId] ?: "Playlist", contextUri)
                }
            }
            contextUri.contains(":album:") -> {
                PlayingContext("Album", albumTitle ?: "Album", contextUri)
            }
            contextUri.contains(":artist:") -> {
                val name = metadata?.get("artist_name")?.toString()
                PlayingContext("Artist", name ?: "Artist", contextUri)
            }
            else -> null
        }
        lastContextUri = contextUri
    }

    private var tickCount = 0

    private fun startPositionTicker() {
        positionJob?.cancel()
        tickCount = 0
        positionJob = viewModelScope.launch {
            while (true) {
                delay(500)
                val current = _playback.value
                if (current.isPlaying && !current.isPaused && current.durationMs > 0) {
                    val newPos = if (isStreaming.value) {
                        MusicPlaybackService.instance?.getCurrentPosition() ?: (current.positionMs + 500)
                    } else {
                        current.positionMs + 500
                    }
                    _playback.value = current.copy(positionMs = newPos.coerceAtMost(current.durationMs))

                    // Every 30s, report position to Spotify via state PUT (not seek command)
                    tickCount++
                    if (isStreaming.value && tickCount % 60 == 0) {
                        launch(Dispatchers.IO) {
                            try { player?.reportPosition(newPos, false) } catch (_: Exception) {}
                        }
                    }
                }
            }
        }
    }

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
                    _playback.value = _playback.value.copy(isPlaying = true, isPaused = false)
                    startPositionTicker()
                    if (isStreaming.value) {
                        withContext(Dispatchers.Main) { MusicPlaybackService.instance?.syncPlay(_playback.value.positionMs) }
                    }
                    try {
                        p.resume()
                    } catch (e: Exception) {
                        LokiLogger.w(TAG, "resume failed, transferring playback and retrying: ${e.message}")
                        p.transferPlaybackHere()
                        delay(500)
                        p.resume()
                    }
                } else {
                    _playback.value = _playback.value.copy(isPaused = true)
                    positionJob?.cancel()
                    if (isStreaming.value) {
                        withContext(Dispatchers.Main) { MusicPlaybackService.instance?.syncPause() }
                    }
                    try {
                        p.pause()
                    } catch (e: Exception) {
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sess = session ?: return@launch
                kotify.api.playlist.Playlist(sess).addToPlaylist(playlistId, listOf(trackUri))
                LokiLogger.i(TAG, "Added $trackUri to playlist $playlistId")
                _snackbarMessage.tryEmit("Added to playlist")
            } catch (e: Exception) { LokiLogger.e(TAG, "addTrackToPlaylist", e) }
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

    @Suppress("UNCHECKED_CAST")
    fun refreshQueue() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = player?.getState() ?: return@launch
                val nextTracks = state.next_tracks as? List<Map<String, Any?>> ?: emptyList()
                val sess = session ?: return@launch
                val songApi = Song(sess)

                // Parse what we have from state metadata first
                data class ParsedTrack(
                    val uri: String,
                    val info: TrackInfo,
                    val needsFetch: Boolean
                )
                val parsed = nextTracks.mapNotNull { track ->
                    val uri = track["uri"]?.toString() ?: return@mapNotNull null
                    val uid = track["uid"]?.toString()
                    val metadata = track["metadata"] as? Map<*, *>
                    val title = metadata?.get("title")?.toString()
                    val artist = metadata?.get("artist_name")?.toString()
                    val art = metadata?.let {
                        it["image_xlarge_url"]?.toString()
                            ?: it["image_large_url"]?.toString()
                            ?: it["image_url"]?.toString()
                    }
                    val dur = metadata?.get("duration")?.toString()?.toLongOrNull() ?: 0
                    val needsFetch = title.isNullOrEmpty() || artist.isNullOrEmpty() || art == null
                    ParsedTrack(uri, TrackInfo(uri = uri, name = title ?: "Unknown", artist = artist ?: "Unknown", albumArt = art, durationMs = dur, uid = uid), needsFetch)
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
                                    songApi.getSong(trackId).let { parseTrackUnion(it) } ?: pt.info
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
                val metadata = track["metadata"] as? Map<*, *>
                val albumUri = metadata?.get("album_uri")?.toString()
                    ?: state.context_uri?.takeIf { it.contains(":album:") }
                if (albumUri != null) {
                    val albumId = albumUri.removePrefix("spotify:album:")
                    openAlbum(albumId)
                } else {
                    LokiLogger.w(TAG, "No album URI found in metadata: ${metadata?.keys}")
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sess = session ?: return@launch
                val trackId = trackUri.removePrefix("spotify:track:")
                val data = Song(sess).getSong(trackId)
                val albumUri = (data["data"] as? Map<*, *>)
                    ?.let { (it["trackUnion"] as? Map<*, *>) }
                    ?.let { (it["albumOfTrack"] as? Map<*, *>) }
                    ?.get("uri")?.toString()
                if (albumUri != null) openAlbum(albumUri.substringAfterLast(":"))
            } catch (e: Exception) { LokiLogger.e(TAG, "openAlbumForTrack", e) }
        }
    }

    fun openArtistForTrack(trackUri: String) {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sess = session ?: return@launch
                val trackId = trackUri.removePrefix("spotify:track:")
                val data = Song(sess).getSong(trackId)
                val artistUri = (data["data"] as? Map<*, *>)
                    ?.let { (it["trackUnion"] as? Map<*, *>) }
                    ?.let { (it["firstArtist"] as? Map<*, *>) }
                    ?.let { (it["items"] as? List<*>)?.firstOrNull() as? Map<*, *> }
                    ?.get("uri")?.toString()
                if (artistUri != null) openArtist(artistUri.substringAfterLast(":"))
            } catch (e: Exception) { LokiLogger.e(TAG, "openArtistForTrack", e) }
        }
    }

    fun openArtistFromCurrentTrack() {
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val state = player?.getState() ?: return@launch
                val track = state.track ?: return@launch
                val metadata = track["metadata"] as? Map<*, *>
                val artistUri = metadata?.get("artist_uri")?.toString()
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val trackId = trackUri.removePrefix("spotify:track:")
                val liked = session?.let { Song(it).isLiked(trackId) } ?: false
                currentTrackLiked.value = liked
            } catch (e: Exception) {
                LokiLogger.e(TAG, "checkLikedState", e)
            }
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

    fun loadPreferences(context: Context) {
        val prefs = context.getSharedPreferences("kotify_prefs", Context.MODE_PRIVATE)
        val savedSource = prefs.getString("audio_source", null)
        // Migrate: old "spotify" value → null (Spotify CDN is now the default)
        preferredAudioSource.value = if (savedSource == "spotify") null else savedSource
        if (savedSource == "spotify") {
            prefs.edit().remove("audio_source").apply()
        }
        lyricsAnimDirection.value = prefs.getString("lyrics_anim_direction", "vertical") ?: "vertical"
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

    fun wireServiceControls() {
        val svc = MusicPlaybackService.instance ?: return
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
        // Load notification button preference
        val prefs = svc.getSharedPreferences("kotify_prefs", android.content.Context.MODE_PRIVATE)
        svc.notificationLeftButton = prefs.getString("notification_left_button", "repeat") ?: "repeat"
        svc.notificationRightButton = prefs.getString("notification_right_button", "like") ?: "like"

        svc.onLikeToggle = lambda@{
            val track = _playback.value.track ?: return@lambda
            val trackId = track.uri.removePrefix("spotify:track:")
            if (currentTrackLiked.value) {
                unlikeSong(trackId)
            } else {
                likeSong(trackId)
            }
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
        svc.onTrackTransition = {
            // ExoPlayer auto-advanced to the pre-buffered next track.
            // TrackPlaybackHandler already advanced the state machine on Spotify's side.
            // Just refresh UI state and pre-resolve the new next track.
            viewModelScope.launch(Dispatchers.IO) {
                try {
                    // WebSocket will push the new state — just pre-resolve next track
                    preResolveNextTrack()
                } catch (e: CancellationException) { throw e }
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
                    // TrackPlaybackHandler.autoAdvanceToNextTrack() already advanced
                    // the state machine and sent state updates to Spotify.
                    // DON'T call skipNext() — it would double-advance.
                    LokiLogger.i(TAG, "ExoPlayer ended — TrackPlaybackHandler auto-advanced")
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    LokiLogger.e(TAG, "playbackEnded advance failed, stopping", e)
                    // Skip failed (auth error, etc.) — update UI to reflect stopped state
                    isStreaming.value = false
                    streamProvider.value = null
                    currentStreamUri = null
                    _playback.value = _playback.value.copy(isPlaying = false, isPaused = true)
                    positionJob?.cancel()
                }
            }
        }
        svc.onReady = {
            LokiLogger.i(TAG, "ExoPlayer ready — syncing Spotify")
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

    private suspend fun resolveAndPlay(event: kotify.api.playerstatus.TrackChangeEvent) {
        val resolveStart = System.currentTimeMillis()
        val trackUri = event.current?.get("uri")?.toString() ?: return
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
        currentStreamUri = trackUri

        val metadata = event.current?.get("metadata") as? Map<*, *>
        val title = metadata?.get("title")?.toString() ?: "Unknown"
        val artist = metadata?.get("artist_name")?.toString() ?: "Unknown"

        isStreamLoading.value = true
        isNextReady.value = false
        positionJob?.cancel()

        // Don't stop the old song — let it keep playing until the new one is ready.
        // ExoPlayer's setMediaItem() in playUrl/playDrmUrl will seamlessly replace it.
        // Pause Spotify so it doesn't advance while we resolve the stream
        // Skip for Spotify CDN — we want Spotify to keep showing us as "playing"
        if (preferredAudioSource.value != null) {
            try { player?.pause() } catch (_: Exception) {}
        }
        val art = metadata?.let {
            it["image_xlarge_url"]?.toString()
                ?: it["image_large_url"]?.toString()
                ?: it["image_url"]?.toString()
        }

        // Update UI with new track info immediately — audio will follow in ~100ms
        val newTrack = TrackInfo(
            uri = trackUri, name = title, artist = artist, albumArt = art,
            albumName = metadata?.get("album_title")?.toString(),
            durationMs = (metadata?.get("duration")?.toString()?.toLongOrNull() ?: _playback.value.durationMs)
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
            MusicPlaybackService.instance?.playUrl(preResolvedUrl, title, artist, art)
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
                val sp = spotifyPlayback ?: throw IllegalStateException("SpotifyPlayback not initialized")

                // Check if we already pre-resolved this CDN URL
                // IMPORTANT: nextCdnUrl is for the NEXT track. Only use it if the
                // file ID matches what we need for the CURRENT track.
                val currentFileId = event.currentFileId ?: latestFileId
                val cachedCdnUrl = if (currentFileId != null && nextCdnFileId == currentFileId) nextCdnUrl else null
                val cdnUrl: String
                if (cachedCdnUrl != null) {
                    cdnUrl = cachedCdnUrl
                    LokiLogger.i(TAG, "SpotifyCDN: Using pre-resolved CDN URL (fileId=$currentFileId)")
                    nextCdnUrl = null
                    nextCdnFileId = null
                } else {
                    // Use file ID from cluster state or from onPlaybackId (state machine)
                    // onPlaybackId fires on a different thread around the same time as onTrackChange
                    var fileId = event.currentFileId ?: latestFileId
                    if (fileId == null) {
                        LokiLogger.d(TAG, "SpotifyCDN: Waiting for file ID from state machine...")
                        for (attempt in 1..25) {
                            delay(200)
                            fileId = latestFileId
                            if (fileId != null) break
                        }
                    }
                    if (fileId == null) {
                        throw IllegalStateException("No file ID received from state machine after 3s")
                    }
                    LokiLogger.i(TAG, "SpotifyCDN: Resolving fileId=$fileId")
                    val cdnUrls = sp.getCdnUrls(fileId)
                    cdnUrl = cdnUrls.firstOrNull() ?: throw IllegalStateException("No CDN URLs")
                    LokiLogger.i(TAG, "SpotifyCDN: Resolved ${cdnUrls.size} mirrors")
                }
                // DRM: must stop old player to close the Widevine session cleanly.
                // Unlike non-DRM, we can't seamlessly replace — each track needs its own license.
                withContext(Dispatchers.Main) {
                    MusicPlaybackService.instance?.stop()
                }
                // ExoPlayer extracts PSSH from the MP4 init segment automatically
                val licenseHeaders = mutableMapOf<String, String>()
                session?.baseClient?.accessToken?.let { licenseHeaders["Authorization"] = "Bearer $it" }
                session?.baseClient?.clientToken?.let { licenseHeaders["client-token"] = it }
                val licenseUrl = "https://gew4-spclient.spotify.com/widevine-license/v1/audio/license"
                withContext(Dispatchers.Main) {
                    MusicPlaybackService.instance?.playDrmUrl(cdnUrl, licenseUrl, licenseHeaders, title, artist, art)
                }
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
                    MusicPlaybackService.instance?.playUrl(result.info.url, title, artist, art)
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
        val uri = track["uri"]?.toString() ?: return
        if (uri == currentStreamUri) return
        currentStreamUri = uri
        isStreamLoading.value = true
        isNextReady.value = false
        val startPositionMs = state.position_as_of_timestamp
        _playback.value = _playback.value.copy(positionMs = startPositionMs)
        positionJob?.cancel()

        val shouldPlay = state.isActuallyPlaying
        val trackId = uri.removePrefix("spotify:track:")
        val metadata = track["metadata"] as? Map<*, *>
        val title = metadata?.get("title")?.toString()
        val artist = metadata?.get("artist_name")?.toString()
        val searchQuery = listOfNotNull(artist, title).joinToString(" ").takeIf { it.isNotBlank() }

        viewModelScope.launch(Dispatchers.IO) {
            try {
                val art = metadata?.let {
                    it["image_xlarge_url"]?.toString()
                        ?: it["image_large_url"]?.toString()
                        ?: it["image_url"]?.toString()
                }

                val result = cdn.resolveStreamUrl(trackId, region = contentRegion.value, youtubeSearchQuery = searchQuery, preferredSource = preferredAudioSource.value)
                when (result) {
                    is StreamResult.Success -> {
                        // playUrl will buffer, then onReady fires → syncs Spotify
                        MusicPlaybackService.instance?.playUrl(
                            result.info.url,
                            title ?: "Unknown",
                            artist ?: "Unknown",
                            art,
                            startPlaying = shouldPlay
                        )
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
            val nextTracks = state.next_tracks as? List<Map<String, Any?>> ?: return
            val nextTrack = nextTracks.firstOrNull() ?: return
            val nextUri = nextTrack["uri"]?.toString() ?: return
            if (nextUri.startsWith("spotify:ad:")) {
                LokiLogger.i(TAG, "[AdSkip] Skipping ad URI in preResolveNextTrack: $nextUri")
                return
            }
            val nextId = nextUri.removePrefix("spotify:track:")

            val metadata = nextTrack["metadata"] as? Map<*, *>
            val title = metadata?.get("title")?.toString() ?: "Unknown"
            val artist = metadata?.get("artist_name")?.toString() ?: "Unknown"
            val art = metadata?.let {
                it["image_xlarge_url"]?.toString()
                    ?: it["image_large_url"]?.toString()
                    ?: it["image_url"]?.toString()
            }
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sess = session ?: return@launch
                val feed = Home(sess).getHomeFeed()
                _homeData.value = feed
                LokiLogger.i(TAG, "Home loaded: ${feed?.sections?.size} sections")
            } catch (e: Exception) { LokiLogger.e(TAG, "loadHome", e) }
            finally { isHomeLoading.value = false }
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
                val tracks = results["tracks"] as? Map<*, *>
                val trackList = parseSearchTracks(tracks?.get("items"))
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
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val sess = session ?: return@launch
                val data = Playlist(sess).getLibrary(limit = 50)
                _library.value = parseLibrary(data)
            } catch (e: Exception) { LokiLogger.e(TAG, "loadLibrary", e) }
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
                _detail.value = parseLikedSongsDetail(data, 0)
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
                    val more = parseLikedSongsDetail(data, offset)
                    _detail.value = current.copy(
                        tracks = current.tracks + more.tracks,
                        totalCount = more.totalCount,
                        loadedOffset = offset + more.tracks.size
                    )
                } else if (uri.startsWith("spotify:playlist:")) {
                    val id = uri.removePrefix("spotify:playlist:")
                    val data = Playlist(sess).getPlaylist(id, limit = 50, offset = offset)
                    val more = parsePlaylistTracks(data)
                    val total = parsePlaylistTotalCount(data)
                    _detail.value = current.copy(
                        tracks = current.tracks + more,
                        totalCount = total,
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
                val info = Playlist(sess).getPlaylistInfo(playlistId, limit = 50)
                _detail.value = DetailData(
                    name = info.name,
                    imageUrl = info.imageUrl,
                    description = info.description.takeIf { it.isNotBlank() },
                    uri = "spotify:playlist:$playlistId",
                    type = "playlist",
                    totalCount = info.totalTracks,
                    loadedOffset = info.tracks.size,
                    ownerName = info.owner.name,
                    followers = info.followers,
                    tracks = info.tracks.map { t ->
                        TrackInfo(
                            uri = t.uri,
                            name = t.name,
                            artist = t.artists.joinToString(", "),
                            albumArt = t.coverArtUrl,
                            durationMs = t.durationMs,
                            uid = t.uid
                        )
                    }
                )
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
                val data = Album(sess).getAlbum(albumId, limit = 50)
                _detail.value = parseAlbumDetail(data, albumId)
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
                val info = Artist(sess).getArtistInfo(artistId)
                _detail.value = DetailData(
                    name = info.name,
                    imageUrl = info.avatarUrl ?: info.headerImageUrl,
                    uri = "spotify:artist:$artistId",
                    type = "artist",
                    monthlyListeners = info.monthlyListeners,
                    biography = info.biography,
                    tracks = info.topTracks.map { t ->
                        TrackInfo(
                            uri = t.uri,
                            name = t.name,
                            artist = info.name,
                            albumArt = t.coverArtUrl,
                            durationMs = t.durationMs
                        )
                    },
                    topTrackPlaycounts = info.topTracks.map { it.playcount.toString() },
                    popularReleases = info.popularReleases.map { r ->
                        ch.snepilatch.app.data.RelatedAlbum(
                            uri = r.uri,
                            name = r.name,
                            imageUrl = r.coverArtUrl,
                            year = r.year?.toString(),
                            albumType = r.type
                        )
                    },
                    relatedArtists = info.relatedArtists.map { a ->
                        ch.snepilatch.app.data.RelatedArtist(
                            uri = a.uri,
                            name = a.name,
                            imageUrl = a.avatarUrl
                        )
                    }
                )
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
                _devices.value = devicesInfo.devices.values.toList()
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
                val loader = ImageLoader(ctx)
                val request = ImageRequest.Builder(ctx)
                    .data(imageUrl)
                    .allowHardware(false)
                    .build()
                val result = loader.execute(request)
                if (result is SuccessResult) {
                    val bitmap = (result.drawable as? BitmapDrawable)?.bitmap ?: return@launch
                    val palette = Palette.from(bitmap).generate()
                    val defaultGray = 0xFFB3B3B3.toInt()
                    val defaultDarkGray = 0xFF808080.toInt()

                    // Pick best color, filtering out too bright/dark/green
                    fun isUsable(color: Int): Boolean {
                        val r = (color shr 16) and 0xFF
                        val g = (color shr 8) and 0xFF
                        val b = color and 0xFF
                        val brightness = (r * 299 + g * 587 + b * 114) / 1000
                        if (brightness > 220 || brightness < 40) return false
                        // Reject any green-dominant hue (70-170 degrees)
                        val max = maxOf(r, g, b).toFloat()
                        val min = minOf(r, g, b).toFloat()
                        val sat = if (max == 0f) 0f else (max - min) / max
                        if (sat > 0.3f && g == max.toInt() && g > 80) return false
                        // Reject neon/overly saturated
                        if (sat > 0.85f && brightness > 150) return false
                        return true
                    }

                    val candidates = listOfNotNull(
                        palette.vibrantSwatch?.rgb,
                        palette.lightVibrantSwatch?.rgb,
                        palette.darkVibrantSwatch?.rgb,
                        palette.mutedSwatch?.rgb,
                        palette.lightMutedSwatch?.rgb,
                        palette.darkMutedSwatch?.rgb
                    )
                    val primary = candidates.firstOrNull { isUsable(it) } ?: defaultGray
                    LokiLogger.d(TAG, "Palette: ${candidates.size} swatches, picked=#${Integer.toHexString(primary)}, usable=${candidates.count { isUsable(it) }}")
                    val darkMuted = palette.getDarkMutedColor(0xFF282828.toInt())
                    val muted = palette.getMutedColor(0xFF282828.toInt())

                    themeColors.value = ThemeColors(
                        primary = Color(primary),
                        primaryDark = Color(primary).copy(alpha = 0.7f),
                        surface = Color(darkMuted),
                        gradientTop = Color(muted).copy(alpha = 0.8f),
                        gradientBottom = Color(0xFF121212)
                    )
                }
            } catch (e: Exception) {
                LokiLogger.e(TAG, "extractColors", e)
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        positionJob?.cancel()
        // Use runBlocking to ensure disconnect completes before the process dies.
        // viewModelScope is already cancelled at this point.
        val p = player
        if (p != null) {
            Thread {
                kotlinx.coroutines.runBlocking {
                    try { p.disconnect() }
                    catch (_: Exception) {}
                }
            }.start()
        }
    }
}
