package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.PlaybackUiState
import ch.snepilatch.app.data.TrackInfo
import kotify.api.playerstatus.PlayerTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * Local-first skip: when the upcoming track is pre-resolved, skipNext must play it on ExoPlayer
 * immediately instead of waiting for the WS onTrackChange echo (the web player's _streamer.nextTrack()).
 * buildPreResolvedNextEvent is the gate — it only yields an event when a CDN URL, file id, and track URI
 * are all cached — and the echo then enriches whatever metadata the fast path couldn't know yet.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class LocalFirstSkipTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() {
        rig.install()
    }

    @After
    fun tearDown() {
        rig.uninstall()
    }

    @Test
    fun noPreResolvedNext_buildsNoFastEvent() {
        // Nothing cached → skipNext must fall back to report-then-await-echo.
        assertNull(rig.vm.buildPreResolvedNextEvent())
    }

    @Test
    fun missingUri_buildsNoFastEvent() {
        // A URL + file id without a URI can't be played locally (we wouldn't know what loaded).
        rig.vm.nextCdnUrl = "https://cdn.example/audio.mp4"
        rig.vm.nextCdnFileId = "file123"
        rig.vm.nextCdnUri = null
        assertNull(rig.vm.buildPreResolvedNextEvent())
    }

    @Test
    fun fullyPreResolved_buildsEventWiringFileIdForCacheHit() {
        rig.vm.nextCdnUrl = "https://cdn.example/audio.mp4"
        rig.vm.nextCdnFileId = "file123"
        rig.vm.nextCdnUri = "spotify:track:next"
        rig.vm.nextCdnName = "Next Song"

        val event = rig.vm.buildPreResolvedNextEvent()!!
        assertEquals("spotify:track:next", event.current?.uri)
        // currentFileId must equal the cached file id so resolveAndPlay takes the cached-CDN branch.
        assertEquals("file123", event.currentFileId)
        // No queue metadata → fall back to the name pushed alongside the file id.
        assertEquals("Next Song", event.current?.name)
    }

    @Test
    fun echoEnrichesMetadataForAlreadyLoadedTrack() {
        // Fast path loaded the track with name-only metadata; the echo fills artist + album.
        rig.vm._playback.value = PlaybackUiState(
            track = TrackInfo(uri = "spotify:track:next", name = "Next Song", artist = "Unknown", albumArt = null),
        )
        val echo = PlayerTrack(
            uri = "spotify:track:next", uid = null, provider = "context", name = "Next Song",
            artistName = "Real Artist", artistUri = null, albumName = "Real Album", albumUri = null,
            durationMs = 180_000, isExplicit = false,
            imageUrl = null, imageSmallUrl = null, imageLargeUrl = null, contextUri = null,
        )

        rig.vm.enrichCurrentTrackMetadata(echo, "spotify:track:next")

        val track = rig.vm.playback.value.track!!
        assertEquals("Real Artist", track.artist)
        assertEquals("Real Album", track.albumName)
    }

    @Test
    fun echoForDifferentTrack_doesNotClobber() {
        rig.vm._playback.value = PlaybackUiState(
            track = TrackInfo(uri = "spotify:track:current", name = "Current", artist = "Artist A", albumArt = null),
        )
        val echo = PlayerTrack(
            uri = "spotify:track:other", uid = null, provider = "context", name = "Other",
            artistName = "Artist B", artistUri = null, albumName = "Album B", albumUri = null,
            durationMs = 1, isExplicit = false,
            imageUrl = null, imageSmallUrl = null, imageLargeUrl = null, contextUri = null,
        )

        rig.vm.enrichCurrentTrackMetadata(echo, "spotify:track:other")

        // Mismatched URI must leave the current track untouched.
        assertEquals("Artist A", rig.vm.playback.value.track!!.artist)
    }
}
