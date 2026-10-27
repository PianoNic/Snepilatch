package ch.snepilatch.app.logic.download

import io.mockk.coEvery
import io.mockk.every
import io.mockk.mockk
import kotify.client.BaseClient
import kotify.session.Session
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** Issue #1034: a refused lookup, or a session that cannot start, stops the run instead of reporting every file as not found. */
class CatalogueLookupTest {

    @Test
    fun aRefusedLookup_stopsTheRest() = runBlocking {
        var asked = 0
        val base = mockk<BaseClient>(relaxed = true) {
            coEvery { pathfinderQuery(any(), any(), any()) } answers {
                asked++
                error("400 not permitted")
            }
        }
        val session = mockk<Session>(relaxed = true) { every { baseClient } returns base }
        val lookup = CatalogueLookup { session }

        assertNull(lookup.find("September", "Earth, Wind & Fire", 215_000))
        assertNotNull(lookup.refused)
        val afterFirst = asked
        assertNull(lookup.find("Children", "Robert Miles", 250_000))
        assertNull(lookup.info("2grjqo0Frpf2okIBiifQKs"))
        assertEquals("nothing asked after the refusal", afterFirst, asked)
    }

    @Test
    fun aSessionThatCannotStart_stopsTheRun() = runBlocking {
        var starts = 0
        val lookup = CatalogueLookup {
            starts++
            error("400 not permitted")
        }
        assertNull(lookup.find("September", "Earth, Wind & Fire", 215_000))
        assertNull(lookup.find("Children", "Robert Miles", 250_000))
        assertNotNull(lookup.refused)
        assertEquals("the session is not started again", 1, starts)
    }
}
