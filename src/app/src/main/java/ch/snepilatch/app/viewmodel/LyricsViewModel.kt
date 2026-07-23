package ch.snepilatch.app.viewmodel

import kotify.api.lyrics.Lyrics
import kotify.api.lyrics.LyricsData
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel for the lyrics overlay's content.
 *
 * Owns only the lyrics *data* concern (fetch + result + loading flag). The
 * [ch.snepilatch.app.ui.screens.LyricsScreen] still reads playback state,
 * transport controls and theme from [SpotifyViewModel]; the two ViewModels
 * sit side by side. Navigation to the overlay stays on
 * [SpotifyViewModel.openLyrics] — this class never navigates.
 */
class LyricsViewModel : SessionViewModel("LyricsVM") {

    private val _lyrics = MutableStateFlow<LyricsData?>(null)
    val lyrics: StateFlow<LyricsData?> = _lyrics
    val isLoading = MutableStateFlow(false)

    /** The track whose lyrics we last fetched, so re-entering the screen is a no-op. */
    private var lastTrackUri: String? = null

    /**
     * Fetch lyrics for [trackUri] (a `spotify:track:<id>` URI). De-duplicated:
     * a repeat call for the same track that already has lyrics does nothing.
     */
    fun fetch(trackUri: String) {
        if (trackUri == lastTrackUri && _lyrics.value != null) return
        lastTrackUri = trackUri
        launchWithSessionLoading("fetch", isLoading) { sess ->
            try {
                val trackId = trackUri.removePrefix("spotify:track:")
                _lyrics.value = Lyrics(sess).getLyrics(trackId)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Clear stale lyrics on failure, then rethrow so the helper logs it.
                _lyrics.value = null
                throw e
            }
        }
    }
}
