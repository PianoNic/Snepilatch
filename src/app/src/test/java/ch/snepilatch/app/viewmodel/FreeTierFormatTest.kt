package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.playback.SessionHolder
import ch.snepilatch.app.playback.engine.SpotifyCdnResolver
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/**
 * A FREE account can only get a Widevine license for MP4_128 (format 10); the media endpoint often
 * offers MP4_256 (format 11), which fails with ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED mid-track.
 * safeMediaFileId must reject a premium-format media file id on a free account, but accept it on
 * premium (or when it's already the free-safe format 10). Regression guard for the 2.7.0 media-fallback
 * change that broke free-tier playback on some tracks.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FreeTierFormatTest {

    private val rig = PlaybackTestRig()

    @Before fun setUp() { rig.install() }

    @After fun tearDown() {
        SessionHolder.cdnResolver = null
        rig.uninstall()
    }

    @Test
    fun freeAccount_rejectsPremiumFormatMediaFileId() = runBlocking {
        val resolver = mockk<SpotifyCdnResolver>(relaxed = true)
        coEvery { resolver.resolveMediaEntry("spotify:track:x") } returns ("fileMP4_256" to "11")
        SessionHolder.cdnResolver = resolver
        rig.vm.setPremiumForTest(false)

        assertNull("free account must not use an unlicensable MP4_256 file id", rig.vm.safeMediaFileId("spotify:track:x"))
    }

    @Test
    fun freeAccount_acceptsFreeSafeMp4128() = runBlocking {
        val resolver = mockk<SpotifyCdnResolver>(relaxed = true)
        coEvery { resolver.resolveMediaEntry("spotify:track:y") } returns ("fileMP4_128" to "10")
        SessionHolder.cdnResolver = resolver
        rig.vm.setPremiumForTest(false)

        assertEquals("fileMP4_128", rig.vm.safeMediaFileId("spotify:track:y"))
    }

    @Test
    fun premiumAccount_acceptsPremiumFormat() = runBlocking {
        val resolver = mockk<SpotifyCdnResolver>(relaxed = true)
        coEvery { resolver.resolveMediaEntry("spotify:track:z") } returns ("fileMP4_256" to "11")
        SessionHolder.cdnResolver = resolver
        rig.vm.setPremiumForTest(true)

        assertEquals("fileMP4_256", rig.vm.safeMediaFileId("spotify:track:z"))
    }
}
