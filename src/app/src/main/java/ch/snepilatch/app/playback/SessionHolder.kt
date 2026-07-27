package ch.snepilatch.app.playback

import ch.snepilatch.app.playback.engine.SpfyCdnResolver
import kotify.api.playerconnect.PlayerConnect
import kotify.cdn.SpfyPlayback
import kotify.session.Session

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
    @Volatile var player: PlayerConnect? = null
    @Volatile var spfyPlayback: SpfyPlayback? = null
    @Volatile var cdnResolver: SpfyCdnResolver? = null

    /** The signed-in user's Spfy username. Set during initialize once the profile loads; read by
     *  library mutations (create/delete/save playlist) that need it. */
    @Volatile var username: String = ""

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
        session = null
        player = null
        spfyPlayback = null
        cdnResolver = null
        username = ""
    }
}
