package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.playback.SessionHolder
import ch.snepilatch.app.playback.engine.SpotifyCdnResolver
import ch.snepilatch.app.playback.engine.SpotifyStream
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotify.cdn.EpisodeResolveInfo
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * A passthrough episode (the show's original DRM-free url) must be streamed directly via playUrl —
 * never forced through Widevine/playDrmUrl. A hosted episode (no passthrough) must go through
 * playDrmUrl with the resolved PSSH. Guards the podcast DRM_LICENSE_ACQUISITION_FAILED fix.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EpisodePassthroughTest {

    private val rig = PlaybackTestRig()

    @Before fun setUp() { rig.install() }

    @After fun tearDown() {
        SessionHolder.cdnResolver = null
        rig.uninstall()
    }

    @Test
    fun passthroughEpisode_streamsDirectUrl_notDrm() = runBlocking {
        val resolver = mockk<SpotifyCdnResolver>(relaxed = true)
        coEvery { resolver.resolveEpisode("EP1") } returns EpisodeResolveInfo(
            fileId = "hostedFileId",
            format = "MP4_128",
            cdnUrls = listOf("https://cdn/enc"),
            passthrough = "ALLOWED",
            passthroughUrl = "https://mcdn.podbean.com/show/ep1.mp3",
        )
        SessionHolder.cdnResolver = resolver

        val handled = rig.vm.resolveEpisodeViaSoundfinder("spotify:episode:EP1", "Ep One", "Some Show", null, 0L)

        assert(handled)
        verify {
            rig.service.playUrl("https://mcdn.podbean.com/show/ep1.mp3", "Ep One", "Some Show", null, startPlaying = any(), headers = emptyMap())
        }
        verify(exactly = 0) { rig.service.playDrmUrl(any(), any(), any(), any(), any(), any(), any(), any(), any()) }
    }

    @Test
    fun hostedEpisode_goesThroughDrm() = runBlocking {
        val resolver = mockk<SpotifyCdnResolver>(relaxed = true)
        coEvery { resolver.resolveEpisode("EP2") } returns EpisodeResolveInfo(
            fileId = "wideFileId",
            format = "MP4_128",
            cdnUrls = listOf("https://cdn/enc"),
            passthrough = "NONE",
            passthroughUrl = null,
        )
        coEvery { resolver.resolveForFileId(eq("wideFileId"), any()) } returns SpotifyStream(
            cdnUrl = "https://cdn/enc", licenseUrl = "https://lic", licenseHeaders = emptyMap(),
            mirrorCount = 1, pssh = "PSSHBYTES",
        )
        SessionHolder.cdnResolver = resolver

        val handled = rig.vm.resolveEpisodeViaSoundfinder("spotify:episode:EP2", "Ep Two", "Show", null, 0L)

        assert(handled)
        verify {
            rig.service.playDrmUrl("https://cdn/enc", "https://lic", emptyMap(), any(), any(), any(), any(), any(), pssh = "PSSHBYTES")
        }
    }
}
