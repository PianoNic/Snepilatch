package ch.snepilatch.app.playback

import ch.snepilatch.app.playback.engine.SpotifyCdnResolver
import kotify.api.playerconnect.PlayerConnect
import kotify.cdn.SpotifyPlayback
import kotify.session.Session

/**
 * Process-scoped holder for the Kotify session and its derived objects.
 *
 * Ownership used to live on [ch.snepilatch.app.viewmodel.SpotifyViewModel] (which
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
    @Volatile var player: PlayerConnect? = null
    @Volatile var spotifyPlayback: SpotifyPlayback? = null
    @Volatile var cdnResolver: SpotifyCdnResolver? = null

    /** True if the holder has a ready-to-use session + player + resolver. */
    val isReady: Boolean
        get() = session != null && player != null && cdnResolver != null

    /** Called by the ViewModel once a new Kotify session is fully initialized. */
    fun set(
        session: Session,
        player: PlayerConnect,
        spotifyPlayback: SpotifyPlayback,
        cdnResolver: SpotifyCdnResolver
    ) {
        this.session = session
        this.player = player
        this.spotifyPlayback = spotifyPlayback
        this.cdnResolver = cdnResolver
    }

    /** Called on teardown — clears all references without disconnecting. */
    fun clear() {
        session = null
        player = null
        spotifyPlayback = null
        cdnResolver = null
    }
}
