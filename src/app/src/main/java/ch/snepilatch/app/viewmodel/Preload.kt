package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.logic.shared.LokiLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch

/**
 * The track shown at launch, loaded paused where it stands so the first tap only starts it (#901). Not a
 * stream until played: while it loads, [loading] tells the view model to keep its stream state out of
 * it, so nothing treats it as playing and Connect is not claimed. One load at a time: a tap waits for
 * it with [join], any other load stops it first with [cancel], so two never race into the one player.
 */
internal class Preload(
    private val scope: CoroutineScope,
    private val load: suspend (track: TrackInfo, fileId: String, positionMs: Long) -> Unit,
) {
    @Volatile var loading = false
        private set

    /** The track loaded and waiting for its tap, and the source it came from. */
    @Volatile var uri: String? = null
    var provider: String? = null
    private var job: Job? = null

    val holds: Boolean get() = loading || uri != null

    fun start(track: TrackInfo, fileId: String, positionMs: Long) {
        if (job?.isActive == true || uri == track.uri) return
        loading = true
        job = scope.launch(Dispatchers.IO) {
            LokiLogger.i(TAG, "[Warm] preloading ${track.uri} at ${positionMs}ms")
            try {
                load(track, fileId, positionMs)
                uri = track.uri
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LokiLogger.w(TAG, "[Warm] preload failed, the play button cold starts it: ${e.message}")
            } finally {
                loading = false
            }
        }
    }

    /** Waits for a load in flight, so a tap plays it rather than starting a second one beside it. */
    suspend fun join() {
        job?.join()
    }

    /** Stops a load in flight before another one takes the player, and forgets what was loaded. */
    suspend fun cancel() {
        job?.cancelAndJoin()
        job = null
        uri = null
    }

    private companion object {
        const val TAG = "PlaybackVM"
    }
}
