package ch.snepilatch.app.viewmodel

import androidx.lifecycle.viewModelScope
import ch.snepilatch.app.logic.shared.JamHolder
import ch.snepilatch.app.logic.shared.LokiLogger
import ch.snepilatch.app.logic.shared.SessionHolder
import ch.snepilatch.app.logic.shared.SessionViewModel
import kotify.api.common.ShortLink
import kotify.api.jam.Jam
import kotify.api.jam.JamSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The jam sheet's model. The session itself lives in [JamHolder], fed by the player client's pushes;
 * this only joins, leaves and ends, and derives the invite link.
 */
class JamViewModel : SessionViewModel("JamVM") {

    val jam: StateFlow<JamSession?> = JamHolder.session
    val joining = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    val shareLink: StateFlow<String?> = combine(JamHolder.session, JamHolder.shareToken) { s, t -> JamHolder.shareLink(s, t) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, JamHolder.shareLink(JamHolder.session.value, JamHolder.shareToken.value))

    init { JamRoutes.register(this) }

    override fun onCleared() {
        JamRoutes.unregister(this)
        super.onCleared()
    }

    /**
     * Joining without a registered Connect device returns 200 but does not stick — the membership is
     * silently dropped and every later command fails. [PlaybackViewModel.initialize] is what calls
     * `ready()`, so refuse until it has.
     */
    fun join(linkOrToken: String) {
        val token = linkOrToken.trim().takeIf { it.isNotBlank() } ?: return
        if (SessionHolder.player == null) {
            error.value = "not_ready"
            return
        }
        error.value = null
        launchWithSessionLoading("joinJam", joining) { sess ->
            val api = Jam(sess)
            // A pasted or scanned spotify.link short link stands for the long one; the share
            // token has to come from the long one.
            val link = ShortLink.expand(sess, token)
            val joined = link?.let { api.joinFromLink(it) }
            if (joined == null) {
                error.value = "failed"
                LokiLogger.w(logTag, "Jam join failed for $token")
            } else {
                JamHolder.shareToken.value = api.shareTokenOf(link)
                JamHolder.session.value = joined
                LokiLogger.i(logTag, "Joined jam ${joined.sessionId} (${joined.members.size} members)")
            }
        }
    }

    /** Ask the server again; the pushes normally keep the holder current on their own. */
    fun refresh() {
        launchWithSession("refreshJam") { SessionHolder.player?.refreshJam() }
    }

    fun leave() {
        val current = jam.value ?: return
        launchWithSessionLoading("leaveJam", joining) { sess ->
            // The push that follows clears the holder too; clearing here keeps the sheet honest when
            // the socket is slow.
            if (Jam(sess).leave(current.sessionId)) JamHolder.clear()
        }
    }

    /** Ends the jam for everyone. The server only lets the host do this. */
    fun end() {
        val current = jam.value ?: return
        launchWithSessionLoading("endJam", joining) { sess ->
            if (Jam(sess).end(current.sessionId)) JamHolder.clear()
        }
    }
}

/** Process-scoped hop so the deep-link handler can reach the live [JamViewModel]. */
object JamRoutes {
    @Volatile private var target: JamViewModel? = null
    fun register(vm: JamViewModel) { target = vm }
    fun unregister(vm: JamViewModel) { if (target === vm) target = null }
    fun join(linkOrToken: String) { target?.join(linkOrToken) }
}
