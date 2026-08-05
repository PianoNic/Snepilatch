package ch.snepilatch.app.playback

import androidx.media3.common.util.UnstableApi
import ch.snepilatch.app.viewmodel.AppSettings
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * The playback cache holds encoded bytes, and what those bytes are depends on where they came from:
 * the same track is Opus-in-WebM from YouTube Music and FLAC from Qobuz. Keying on the track uri
 * alone let one source's cached copy be served — and written out as a download — under the other's
 * name, with no failure anywhere to notice it.
 */
@UnstableApi
class PlaybackCacheKeyTest {

    private val track = "spotify:track:2Foc5Q5nqNiosCNqttzHof"

    @Test
    fun twoSourcesDoNotShareAnEntryForTheSameTrack() {
        assertNotEquals(
            PlaybackCache.keyFor(track, AppSettings.SOURCE_YTM),
            PlaybackCache.keyFor(track, "lossless"),
        )
    }

    @Test
    fun theSpfyCdnGetsItsOwnEntryRatherThanSharingTheUnkeyedOne() {
        assertNotEquals(
            PlaybackCache.keyFor(track, null),
            PlaybackCache.keyFor(track, AppSettings.SOURCE_YTM),
        )
    }

    @Test
    fun twoTracksOnOneSourceDoNotCollide() {
        assertNotEquals(
            PlaybackCache.keyFor(track, AppSettings.SOURCE_YTM),
            PlaybackCache.keyFor("spotify:track:0DiWol3AO6WpXZgp0goxAV", AppSettings.SOURCE_YTM),
        )
    }

    @Test
    fun theKeyCarriesTheTrackUriSoAnEntryIsStillIdentifiable() {
        assert(PlaybackCache.keyFor(track, AppSettings.SOURCE_YTM).contains(track))
    }
}
