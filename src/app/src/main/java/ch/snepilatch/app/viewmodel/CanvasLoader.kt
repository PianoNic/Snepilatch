package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.logic.download.CanvasStore
import ch.snepilatch.app.logic.shared.AppSettings
import ch.snepilatch.app.logic.shared.LokiLogger
import ch.snepilatch.app.logic.shared.SessionHolder
import kotify.api.canvas.Canvas
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * The current track's canvas video url, playback-derived state; the on/off toggle is
 * AppSettings.canvasEnabled.
 */
internal class CanvasLoader(private val scope: CoroutineScope) {
    val url = MutableStateFlow<String?>(null)
    private var lastTrackUri: String? = null

    fun fetch(trackUri: String) {
        if (!AppSettings.canvasEnabled.value) return
        if (trackUri == lastTrackUri) return
        lastTrackUri = trackUri
        // Canvas is a track-only visual; podcast episodes have none. Skip the futile lookup + clear.
        if (!trackUri.startsWith("spotify:track:")) {
            url.value = null
            return
        }
        // A downloaded track's clip is on the phone: no lookup, no fetch, and it works offline (#565).
        CanvasStore.localUri(trackUri)?.let {
            url.value = it
            return
        }
        val trackId = trackUri.removePrefix("spotify:track:")
        scope.launch(Dispatchers.IO) {
            try {
                val sess = SessionHolder.session ?: return@launch
                val data = Canvas(sess).getCanvas(trackId)
                // Only update if this is still the current track (avoid race with rapid skips)
                if (lastTrackUri == trackUri) {
                    url.value = data?.url
                    if (data != null) LokiLogger.i(TAG, "Canvas: ${data.url.take(URL_LOGGED)}...")
                }
            } catch (e: Exception) {
                if (lastTrackUri == trackUri) url.value = null
                LokiLogger.d(TAG, "Canvas failed: ${e.message}")
            }
        }
    }

    private companion object {
        const val TAG = "PlaybackVM"
        const val URL_LOGGED = 60
    }
}
