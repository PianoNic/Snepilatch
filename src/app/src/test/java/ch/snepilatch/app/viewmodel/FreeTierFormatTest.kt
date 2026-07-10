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
    fun freeAccount_picksMp4128WhenBothOffered() = runBlocking {
        // The real regression case: the manifest offers BOTH 256 and 128 — a free account must pick
        // the licensable 128, not the (default first) 256.
        val resolver = mockk<SpotifyCdnResolver>(relaxed = true)
        coEvery { resolver.resolveMediaEntries("spotify:track:x") } returns
            listOf("fileMP4_256" to "11", "fileMP4_128" to "10", "fileDual256" to "13", "fileDual128" to "12")
        SessionHolder.cdnResolver = resolver
        rig.vm.setPremiumForTest(false)

        assertEquals("fileMP4_128", rig.vm.safeMediaFileId("spotify:track:x"))
    }

    @Test
    fun freeAccount_returnsNullWhenOnlyPremiumOffered() = runBlocking {
        val resolver = mockk<SpotifyCdnResolver>(relaxed = true)
        coEvery { resolver.resolveMediaEntries("spotify:track:y") } returns listOf("fileMP4_256" to "11")
        SessionHolder.cdnResolver = resolver
        rig.vm.setPremiumForTest(false)

        assertNull("free account can't license MP4_256; must defer", rig.vm.safeMediaFileId("spotify:track:y"))
    }

    @Test
    fun premiumAccount_takesHighestQuality() = runBlocking {
        val resolver = mockk<SpotifyCdnResolver>(relaxed = true)
        coEvery { resolver.resolveMediaEntries("spotify:track:z") } returns
            listOf("fileMP4_256" to "11", "fileMP4_128" to "10")
        SessionHolder.cdnResolver = resolver
        rig.vm.setPremiumForTest(true)

        assertEquals("fileMP4_256", rig.vm.safeMediaFileId("spotify:track:z"))
    }
}
