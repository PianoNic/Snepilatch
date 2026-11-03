package ch.snepilatch.app.viewmodel

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

/** A start that cannot reach the server opens on the downloads, not a retry screen. */
class InitFailureTest {
    private val refusal = IllegalStateException("""{"error":{"code":400,"message":"Unauthorized request"}}""")

    @Test
    fun noRouteOpensOffline() {
        assertTrue(startsOffline(IllegalStateException("anything"), noRoute = true, hasDownloads = false))
    }

    /** A connection that is up but answers nothing, as on weak Wi-Fi: the phone still says it is online. */
    @Test
    fun noAnswerOpensOfflineEvenWithARoute() {
        assertTrue(startsOffline(IOException("Timeout was reached"), noRoute = false, hasDownloads = false))
    }

    @Test
    fun aRefusalOpensOfflineOnlyWithSomethingToPlay() {
        assertTrue(startsOffline(refusal, noRoute = false, hasDownloads = true))
        assertFalse(startsOffline(refusal, noRoute = false, hasDownloads = false))
    }

    @Test
    fun otherFailuresKeepTheirScreen() {
        assertFalse(startsOffline(IllegalStateException("Unexpected"), noRoute = false, hasDownloads = true))
    }
}
