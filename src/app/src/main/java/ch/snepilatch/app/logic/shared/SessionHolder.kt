package ch.snepilatch.app.logic.shared

import ch.snepilatch.app.logic.playback.engine.SpfyCdnResolver
import kotify.api.playerconnect.PlayerConnect
import kotify.api.playerstatus.PlayerStateData
import kotify.api.playlist.PlaylistStore
import kotify.cdn.SpfyPlayback
import kotify.session.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Process-scoped holder for the Kotify session and its derived objects.
 *
 * Ownership used to live on [ch.snepilatch.app.viewmodel.PlaybackViewModel] (which
 * ties lifetime to the Activity) and was duplicated onto
 * [MusicPlaybackService] static fields for the service to reach. Neither
 * location works when we need to start playback from a cold process — e.g.
 * when the user presses the play button on their headphones with the app
 * fully closed. Lifting these references to a process-level object means
 * the service and future entry points (MediaButtonReceiver, Tiles, Widgets)
 * can reach them without requiring an Activity.
 *
 * The ViewModel is still the only writer — it drives initialization and
 * teardown. Everything else is a reader.
 */
object SessionHolder {
    @Volatile var session: Session? = null

    // A flow underneath, so an entry point that runs before the device is registered can wait for
    // it (a jam link on a cold start lands between the session loading and the player's ready()).
    private val playerFlow = MutableStateFlow<PlayerConnect?>(null)
    var player: PlayerConnect?
        get() = playerFlow.value
        set(value) { playerFlow.value = value }

    /** The player once it is registered, or null when that takes longer than [timeoutMs]. */
    suspend fun awaitPlayer(timeoutMs: Long): PlayerConnect? =
        withTimeoutOrNull(timeoutMs) { playerFlow.filterNotNull().first() }
    @Volatile var spfyPlayback: SpfyPlayback? = null
    @Volatile var cdnResolver: SpfyCdnResolver? = null

    /**
     * Playlist pages, kept until the dealer says they are stale. Process scoped like the session so
     * a playlist read once stays read across screens rather than per ViewModel.
     */
    @Volatile var playlistStore: PlaylistStore? = null

    /** The signed-in user's Spfy username. Set during initialize once the profile loads; read by
     *  library mutations (create/delete/save playlist) that need it. */
    @Volatile var username: String = ""

    /** Bumped when a session finishes initialising, so a feature ViewModel built for an earlier
     *  account reloads for the new one (#847). */
    val generation = MutableStateFlow(0)

    /** The last player state from the cluster, as Kotify parsed it; play buttons ask it what they are on. */
    val playerState = MutableStateFlow<PlayerStateData?>(null)

    /** True if the holder has a ready-to-use session + player + resolver. */
    val isReady: Boolean
        get() = session != null && player != null && cdnResolver != null

    /** Called by the ViewModel once a new Kotify session is fully initialized. */
    fun set(
        session: Session,
        player: PlayerConnect,
        spfyPlayback: SpfyPlayback,
        cdnResolver: SpfyCdnResolver
    ) {
        this.session = session
        this.player = player
        this.spfyPlayback = spfyPlayback
        this.cdnResolver = cdnResolver
    }

    /** Called on teardown — clears all references without disconnecting. */
    fun clear() {
        playlistStore?.clear()
        playlistStore = null
        session = null
        player = null
        spfyPlayback = null
        cdnResolver = null
        username = ""
        playerState.value = null
    }
}
