package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.playback.SessionHolder
import ch.snepilatch.app.playback.engine.SpotifyCdnResolver
import ch.snepilatch.app.playback.engine.SpotifyStream
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.mockk
import io.mockk.verify
import kotify.api.playerconnect.PlayerConnect
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * A transient ExoPlayer/DRM error (e.g. `ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED` — a throttled
 * Widevine license) must NOT leave playback silent until the user taps play. The recovery re-resolves
 * the SAME track and reloads it at its last position; it only hands back to Spotify once the retry
 * budget is spent. Regression guard for the "music stopped once and didn't continue" bug.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlaybackErrorRecoveryTest {

    private val rig = PlaybackTestRig()

    @Before
    fun setUp() { rig.install() }

    @After
    fun tearDown() {
        SessionHolder.cdnResolver = null
        SessionHolder.player = null
        rig.uninstall()
    }

    @Test
    fun transientError_reResolvesAndReloadsSameTrack_notSilent() = runBlocking {
        val resolver = mockk<SpotifyCdnResolver>(relaxed = true)
        coEvery { resolver.fetchFileIdFromMedia("spotify:track:test") } returns "fileXYZ"
        coEvery { resolver.resolveForFileId(eq("fileXYZ"), any()) } returns SpotifyStream(
            cdnUrl = "https://cdn.example/audio",
            licenseUrl = "https://license.example",
            licenseHeaders = emptyMap(),
            mirrorCount = 3,
            pssh = null,
        )
        SessionHolder.cdnResolver = resolver
        rig.seedStreaming(positionMs = 42_000L) // track uri = spotify:track:test

        rig.vm.recoverFromPlaybackError("spotify:track:test", 42_000L)

        // The failed track was re-resolved on a rotated mirror and reloaded — not paused into silence.
        coVerify(exactly = 1) { resolver.resolveForFileId(eq("fileXYZ"), any()) }
        verify {
            rig.service.playDrmUrl(
                "https://cdn.example/audio", "https://license.example", emptyMap(),
                any(), any(), any(), startPlaying = true, startPositionMs = 42_000L, pssh = null
            )
        }
    }

    @Test
    fun sameTrackReadyBetweenFailures_stillEscalatesMirrorsThenSkips() = runBlocking {
        // Regression for the livelock: a track that fails at the same spot every time reaches READY on
        // each recovery reload (which used to reset the retry budget), so it stayed pinned to mirror #1
        // forever — never escalating or skipping. It must now walk mirror #1 -> #2 -> #3, then skip.
        val resolver = mockk<SpotifyCdnResolver>(relaxed = true)
        coEvery { resolver.fetchFileIdFromMedia("spotify:track:test") } returns "fileXYZ"
        coEvery { resolver.resolveForFileId(eq("fileXYZ"), any()) } returns SpotifyStream(
            cdnUrl = "https://cdn.example/audio",
            licenseUrl = "https://license.example",
            licenseHeaders = emptyMap(),
            mirrorCount = 6,
            pssh = null,
        )
        SessionHolder.cdnResolver = resolver
        val pc = mockk<PlayerConnect>(relaxed = true)
        SessionHolder.player = pc
        rig.seedStreaming(positionMs = 9808L)

        // Each reload "succeeds" (reaches READY) for the SAME uri, then the track fails again.
        repeat(3) {
            rig.vm.recoverFromPlaybackError("spotify:track:test", 9808L)
            rig.vm.refillRetryBudgetOnReady("spotify:track:test") // same uri -> must NOT refill the budget
        }
        // The 4th failure has exhausted the budget -> skip forward instead of looping.
        rig.vm.recoverFromPlaybackError("spotify:track:test", 9808L)

        // Mirrors escalated across attempts rather than hammering #1 forever.
        coVerify { resolver.resolveForFileId("fileXYZ", 1) }
        coVerify { resolver.resolveForFileId("fileXYZ", 2) }
        coVerify { resolver.resolveForFileId("fileXYZ", 3) }
        coVerify(atLeast = 1) { pc.localNext() }
    }

    @Test
    fun repeatedFailures_skipToNextTrackInsteadOfSilence() = runBlocking {
        // Resolve never yields a usable file id, so every mirror/attempt fails.
        val resolver = mockk<SpotifyCdnResolver>(relaxed = true)
        coEvery { resolver.fetchFileIdFromMedia(any()) } returns null
        coEvery { resolver.fetchFileIdFromMetadata(any()) } returns null
        SessionHolder.cdnResolver = resolver
        val pc = mockk<PlayerConnect>(relaxed = true)
        SessionHolder.player = pc
        rig.seedStreaming(positionMs = 10_000L)

        // Must terminate (not loop forever) and, once the budget is spent, skip forward rather than
        // sit in silence on the dead track.
        rig.vm.recoverFromPlaybackError("spotify:track:test", 10_000L)

        verify(exactly = 0) { rig.service.playDrmUrl(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
        coVerify(atLeast = 1) { pc.localNext() }
    }
}
