package ch.snepilatch.app.logic.shared

import kotify.api.jam.Jam
import kotify.api.jam.JamSession
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Issue #783: the invite sheet shares the short links once known and the long link until then. */
class JamInviteLinksTest {

    private val long = "https://open.spotify.com/socialsession/3a7t8ObTd5rWaKSzQRZbfN"

    private fun jam(joinToken: String) = JamSession(
        sessionId = "s", joinSessionToken = joinToken, joinSessionUri = "spotify:socialsession:$joinToken",
        sessionOwnerId = "host", members = emptyList(), isSessionOwner = false, isListening = true, isControlling = true,
        active = true, queueOnlyMode = false, isPaused = false, hostActiveDeviceId = null, initialSessionType = null,
        hostDevice = null,
    )

    @Test
    fun withoutShortLinks_bothSidesAreTheLongLink() {
        val links = JamHolder.inviteSheetLinks(long, jam("join1"), cached = null)
        assertEquals(JamHolder.InviteSheetLinks(long, long), links)
    }

    @Test
    fun shortLinksForThisJam_areUsed() {
        val cached = JamHolder.InviteLinksFor("join1", Jam.InviteLinks(share = "https://spotify.link/a", qr = "https://spotify.link/b"))
        val links = JamHolder.inviteSheetLinks(long, jam("join1"), cached)
        assertEquals(JamHolder.InviteSheetLinks("https://spotify.link/a", "https://spotify.link/b"), links)
    }

    @Test
    fun shortLinksOfAnotherJam_areIgnored() {
        val cached = JamHolder.InviteLinksFor("old", Jam.InviteLinks(share = "https://spotify.link/a", qr = "https://spotify.link/b"))
        assertEquals(JamHolder.InviteSheetLinks(long, long), JamHolder.inviteSheetLinks(long, jam("join2"), cached))
    }

    @Test
    fun aMissingSide_fallsBackAlone() {
        val cached = JamHolder.InviteLinksFor("join1", Jam.InviteLinks(share = "https://spotify.link/a", qr = null))
        assertEquals(JamHolder.InviteSheetLinks("https://spotify.link/a", long), JamHolder.inviteSheetLinks(long, jam("join1"), cached))
    }

    @Test
    fun noLongLink_meansNoSheet() {
        assertNull(JamHolder.inviteSheetLinks(null, jam("join1"), cached = null))
    }
}
