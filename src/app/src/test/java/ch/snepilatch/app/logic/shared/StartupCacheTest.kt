package ch.snepilatch.app.logic.shared

import android.content.Context
import ch.snepilatch.app.data.PlaybackUiState
import ch.snepilatch.app.data.TrackInfo
import io.mockk.every
import io.mockk.mockk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/** #901: the screens a launch opens on are the signed-in account's, never another's. */
class StartupCacheTest {

    private val alice = mapOf("sp_dc" to "alice")
    private val bob = mapOf("sp_dc" to "bob")
    private val track = TrackInfo(uri = "spotify:track:a", name = "A", artist = "Artist", albumArt = null)

    @Before
    fun setUp() {
        val context = mockk<Context>()
        every { context.filesDir } returns Files.createTempDirectory("startup").toFile()
        StartupCache.init(context)
    }

    @Test
    fun theLastTrack_comesBackPaused_forTheSameAccount() {
        StartupCache.restore(alice, PlaybackUiState())
        StartupCache.savePlayback(PlaybackUiState(track = track, isPlaying = true, positionMs = 42_000))

        val restored = StartupCache.restore(alice, PlaybackUiState())

        assertEquals(track.uri, restored.track?.uri)
        assertEquals(42_000L, restored.positionMs)
        assertFalse(restored.isPlaying)
        assertTrue(restored.isPaused)
    }

    @Test
    fun anotherAccount_seesNothingOfIt() {
        StartupCache.restore(alice, PlaybackUiState())
        StartupCache.savePlayback(PlaybackUiState(track = track))

        assertNull(StartupCache.restore(bob, PlaybackUiState()).track)
    }
}
