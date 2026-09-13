package ch.snepilatch.app.logic.shared

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
 */
object JamHolder {
    val session = MutableStateFlow<JamSession?>(null)
    val shareToken = MutableStateFlow<String?>(null)

    /** The link to invite others with: the share token when known, else the join token, which the info route also resolves. */
    fun shareLink(session: JamSession?, token: String?): String? =
        (token ?: session?.joinSessionToken?.takeIf { it.isNotBlank() })?.let(::jamShareLink)

    fun apply(update: JamUpdate, tokenFromClient: String?) {
        session.value = update.session
        if (update.session == null) shareToken.value = null else tokenFromClient?.let { shareToken.value = it }
    }

    fun clear() {
        session.value = null
        shareToken.value = null
    }

    /**
     * Join by link (long or `spotify.link` short) or bare token. Null when it worked, otherwise the
     * error key the join sheet shows: [NOT_READY] or [FAILED].
     *
     * Joining without a registered Connect device returns 200 but does not stick: the membership is
     * silently dropped and every later command fails. [ch.snepilatch.app.viewmodel.PlaybackViewModel.initialize]
     * is what calls `ready()`, and a link opened on a cold start arrives before that, so wait for it.
     */
    suspend fun join(sess: Session, linkOrToken: String): String? {
        val input = linkOrToken.trim().takeIf { it.isNotBlank() } ?: return FAILED
        SessionHolder.awaitPlayer(PLAYER_WAIT_MS) ?: return NOT_READY
        val api = Jam(sess)
        // A pasted or scanned short link stands for the long one; the share token has to come from
        // the long one.
        val link = ShortLink.expand(sess, input)
        val joined = link?.let { api.joinFromLink(it) }
        if (joined == null) {
            LokiLogger.w(TAG, "Jam join failed for $input")
            return FAILED
        }
        shareToken.value = api.shareTokenOf(link)
        session.value = joined
        LokiLogger.i(TAG, "Joined jam ${joined.sessionId} (${joined.members.size} members)")
        return null
    }

    const val NOT_READY = "not_ready"
    private const val PLAYER_WAIT_MS = 20_000L
    const val FAILED = "failed"
    private const val TAG = "JamHolder"
}
