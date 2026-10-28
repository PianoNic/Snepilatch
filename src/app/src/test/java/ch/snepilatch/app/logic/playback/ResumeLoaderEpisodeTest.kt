package ch.snepilatch.app.logic.playback

import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.logic.playback.engine.SpfyCdnResolver
import ch.snepilatch.app.logic.playback.engine.SpfyStream
import ch.snepilatch.app.viewmodel.PlaybackTestRig
import io.mockk.coEvery
import io.mockk.mockk
import io.mockk.verify
import kotify.cdn.EpisodeResolveInfo
import kotify.cdn.StreamInfo
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Test

/**
 * #1047: an episode resumed after the app was closed loads from its own uri at the saved position,
 * because an external episode never brings the state machine file id the cold start waited for.
 */
class ResumeLoaderEpisodeTest {

    private val rig = PlaybackTestRig()
    private val resolver = mockk<SpfyCdnResolver>(relaxed = true)
    private val loader = ResumeLoader(object : ResumeLoader.Hooks {
        override val cdnResolver = resolver
        override fun markPlayUrl() = Unit
        override fun commitStream(uri: String, provider: String?) = Unit
        override fun cacheKeyFor(uri: String, info: StreamInfo): String? = null
    })
    private val episode = TrackInfo(uri = "spotify:episode:EP1", name = "Ep One", artist = "Show", albumArt = null)

    @Before fun setUp() { rig.install() }

    @After fun tearDown() { rig.uninstall() }

    @Test
    fun passthroughEpisode_resumesTheDirectUrlAtThePosition() = runBlocking {
        coEvery { resolver.resolveEpisode("EP1") } returns EpisodeResolveInfo(
            fileId = null, format = "DO_NOT_USE", cdnUrls = emptyList(),
            passthrough = "MANDATORY", passthroughUrl = "https://proxycast.radiofrance.fr/ep1.mp3",
        )

        loader.loadEpisodeAt(episode, positionMs = 13_000, tag = "ColdStart", startPlaying = false)

        verify {
            rig.service.playUrl(
                "https://proxycast.radiofrance.fr/ep1.mp3", "Ep One", "Show", null,
                startPlaying = false, startPositionMs = 13_000, headers = emptyMap(),
            )
        }
    }

    @Test
    fun hostedEpisode_resumesThroughDrmAtThePosition() = runBlocking {
        coEvery { resolver.resolveEpisode("EP1") } returns EpisodeResolveInfo(
            fileId = "wideFileId", format = "MP4_128", cdnUrls = listOf("https://cdn/enc"),
            passthrough = "NONE", passthroughUrl = null,
        )
        coEvery { resolver.resolveForFileId(eq("wideFileId"), any()) } returns SpfyStream(
            cdnUrl = "https://cdn/enc", mirrorCount = 1, pssh = "PSSH",
        )

        loader.loadEpisodeAt(episode, positionMs = 13_000, tag = "ColdStart")

        verify {
            rig.service.playDrmUrl(
                "https://cdn/enc", null, "Ep One", "Show", null,
                startPlaying = true, startPositionMs = 13_000, pssh = "PSSH",
            )
        }
    }
}
