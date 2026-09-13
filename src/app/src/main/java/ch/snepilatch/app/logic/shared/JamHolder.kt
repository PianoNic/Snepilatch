package ch.snepilatch.app.logic.shared

import kotify.api.jam.JamSession
import kotify.api.jam.JamUpdate
import kotify.api.jam.jamShareLink
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The jam this account is in, process-scoped like [SessionHolder]: written from the player client's
 * updates, read by the banner, the sheet and the transport controls. [shareToken] is what the invite
 * link is built from; the client only learns it from a broadcast push, so the link the user joined
 * through is kept as well.
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
}
