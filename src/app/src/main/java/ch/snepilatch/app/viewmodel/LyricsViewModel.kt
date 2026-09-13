package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.TrackInfo
import kotify.api.lyrics.Lyrics
import kotify.api.lyrics.LyricsData
import kotify.api.lyrics.LyricsHint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import ch.snepilatch.app.logic.shared.SessionViewModel

/**
 * ViewModel for the lyrics overlay's content.
 *
 * Owns only the lyrics *data* concern (fetch + result + loading flag). The
 * [ch.snepilatch.app.ui.screens.LyricsScreen] still reads playback state,
 * transport controls and theme from [PlaybackViewModel]; the two ViewModels
 * sit side by side. Navigation to the overlay stays on
 * [PlaybackViewModel.openLyrics] — this class never navigates.
 */
class LyricsViewModel : SessionViewModel("LyricsVM") {

    private val _lyrics = MutableStateFlow<LyricsData?>(null)
    val lyrics: StateFlow<LyricsData?> = _lyrics
    val isLoading = MutableStateFlow(false)

    /** The track whose lyrics we last fetched, so re-entering the screen is a no-op. */
    private var lastTrackUri: String? = null

    /**
     * Fetch lyrics for [track]. De-duplicated: a repeat call for the same track that already has
     * lyrics does nothing. The track's title, artist and length go along as the hint the
     * title-matched providers need, so a free account gets synced lyrics where spfy has none (#808).
     */
    fun fetch(track: TrackInfo) {
        val trackUri = track.uri
        if (trackUri == lastTrackUri && _lyrics.value != null) return
        lastTrackUri = trackUri
        launchWithSessionLoading("fetch", isLoading) { sess ->
            try {
                val trackId = trackUri.removePrefix("spotify:track:")
                _lyrics.value = Lyrics(sess).getLyrics(trackId, lyricsHintFor(track))
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

/**
 * What the title-matched lyrics providers get. The artist is the first one only: a row's artist
 * text lists every featured name, and a lookup for "A, B, C" misses what a lookup for "A" finds.
 */
internal fun lyricsHintFor(track: TrackInfo): LyricsHint = LyricsHint(
    title = track.name,
    artist = track.artist.substringBefore(", ").trim(),
    album = track.albumName?.takeIf { it.isNotBlank() },
    durationMs = track.durationMs,
)
