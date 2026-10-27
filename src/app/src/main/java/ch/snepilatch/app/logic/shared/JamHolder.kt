package ch.snepilatch.app.logic.shared

import ch.snepilatch.app.logic.relay.RelayJam
import kotify.api.common.ShortLink
import kotify.api.jam.Jam
import kotify.api.jam.JamSession
import kotify.api.jam.JamUpdate
import kotify.api.jam.jamShareLink
import kotify.session.Session
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The jam this account is in, process-scoped like [SessionHolder]: written from the player client's
 * updates, read by the banner, the sheet and the transport controls. [shareToken] is what the invite
 * link is built from; the client only learns it from a broadcast push, so the link the user joined
 * through is kept as well. Joining lives here too, so a scanned or tapped link joins without any
 * sheet being open.
 *
 * [inviteLinks] are the `spotify.link` short links the invite sheet shows, asked for once per jam
 * and kept with the join token they belong to.
 */
object JamHolder {
    val session = MutableStateFlow<JamSession?>(null)
    val shareToken = MutableStateFlow<String?>(null)
    val inviteLinks = MutableStateFlow<InviteLinksFor?>(null)

    /** Short links and the join token they were made for, so a new jam does not reuse the old ones. */
    data class InviteLinksFor(val joinSessionToken: String, val links: Jam.InviteLinks)

    /** What the invite sheet shares and draws: the short links when known, else the long [longLink]. */
    data class InviteSheetLinks(val share: String, val qr: String)

    fun inviteSheetLinks(longLink: String?, jam: JamSession?, cached: InviteLinksFor?): InviteSheetLinks? {
        val long = longLink ?: return null
        val links = cached?.takeIf { it.joinSessionToken == jam?.joinSessionToken }?.links
        return InviteSheetLinks(share = links?.share ?: long, qr = links?.qr ?: long)
    }

    /** Ask the url-dispenser once for the current jam's short links; a repeat for the same jam is free. */
    suspend fun loadInviteLinks(sess: Session) {
        val token = session.value?.joinSessionToken?.takeIf { it.isNotBlank() } ?: return
        if (inviteLinks.value?.joinSessionToken == token) return
        val links = Jam(sess).inviteLinks(token)
        if (links.share == null && links.qr == null) {
            LokiLogger.w(TAG, "No short invite links for $token, sharing the long link")
            return
        }
        inviteLinks.value = InviteLinksFor(token, links)
    }

    /** The link to invite others with: the share token when known, else the join token, which the info route also resolves. */
    fun shareLink(session: JamSession?, token: String?): String? =
        (token ?: session?.joinSessionToken?.takeIf { it.isNotBlank() })?.let(::jamShareLink)

    fun apply(update: JamUpdate, tokenFromClient: String?) {
        if (RelayJam.active) {
            // A jam held through the relay is not the account's jam as the player client sees it.
            val spfyJam = update.session ?: return
            // In a Spfy jam as well, joined somewhere else: one jam at a time, since the account's
            // playback goes to that jam's host and the relay's commands would change their music.
            // The newer one wins, so the relay jam is left.
            LokiLogger.i(TAG, "In Spfy jam ${spfyJam.sessionId} while in a relay jam, leaving the relay jam")
            RelayJam.leaveInBackground()
        }
        session.value = update.session
        if (update.session == null) {
            shareToken.value = null
            inviteLinks.value = null
        } else {
            tokenFromClient?.let { shareToken.value = it }
        }
    }

    fun clear() {
        session.value = null
        shareToken.value = null
        inviteLinks.value = null
    }

    /** A jam link opened but not joined yet: the join sheet asks how to take part, as the official app does. */
    data class PendingJam(val link: String, val jam: JamSession)

    val pending = MutableStateFlow<PendingJam?>(null)

    /**
     * Look up the jam behind a link (long or `spotify.link` short) or bare token and hold it in
     * [pending] for the join sheet. Null when it worked, otherwise the error key the sheets show:
     * [NOT_READY] or [FAILED].
     *
     * Joining without a registered Connect device returns 200 but does not stick: the membership is
     * silently dropped and every later command fails. [ch.snepilatch.app.viewmodel.PlaybackViewModel.initialize]
     * is what calls `ready()`, and a link opened on a cold start arrives before that, so wait for it.
     */
    suspend fun preview(sess: Session, linkOrToken: String): String? {
        val input = linkOrToken.trim().takeIf { it.isNotBlank() } ?: return FAILED
        SessionHolder.awaitPlayer(PLAYER_WAIT_MS) ?: return NOT_READY
        // A pasted or scanned short link stands for the long one; the share token has to come from
        // the long one.
        val link = ShortLink.expand(sess, input)
        val jam = link?.let { Jam(sess).getSession(it) }
        if (link == null || jam == null) {
            LokiLogger.w(TAG, "Jam lookup failed for $input")
            return FAILED
        }
        pending.value = PendingJam(link, jam)
        return null
    }

    /**
     * Join the [pending] jam. [listen] plays it on this phone ("listen along"), which the server allows
     * Premium accounts only; without it this account controls the host's playback. Null when it
     * worked, otherwise [FAILED].
     */
    suspend fun join(sess: Session, listen: Boolean): String? {
        val wanted = pending.value ?: return FAILED
        val joined = Jam(sess).joinFromLink(wanted.link, listen, SessionHolder.player?.ourDeviceId())
        if (joined == null) {
            LokiLogger.w(TAG, "Jam join failed for ${wanted.link} (listen=$listen)")
            return FAILED
        }
        pending.value = null
        shareToken.value = Jam(sess).shareTokenOf(wanted.link)
        session.value = joined
        LokiLogger.i(TAG, "Joined jam ${joined.sessionId} (${joined.members.size} members, listen=$listen)")
        return null
    }

    const val NOT_READY = "not_ready"
    private const val PLAYER_WAIT_MS = 20_000L
    const val FAILED = "failed"
    private const val TAG = "JamHolder"
}
