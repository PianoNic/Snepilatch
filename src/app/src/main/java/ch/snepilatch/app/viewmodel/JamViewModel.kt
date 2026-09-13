package ch.snepilatch.app.viewmodel

import androidx.lifecycle.viewModelScope
import ch.snepilatch.app.logic.shared.JamHolder
import ch.snepilatch.app.logic.shared.SessionHolder
import ch.snepilatch.app.logic.shared.SessionViewModel
import kotify.api.jam.Jam
import kotify.api.jam.JamSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn

/**
 * The jam sheet's model. The session itself lives in [JamHolder], fed by the player client's pushes,
 * and so does the join; this keeps the sheet's loading and error state, leaves and ends, and derives
 * the invite link.
 */
class JamViewModel : SessionViewModel("JamVM") {

    val jam: StateFlow<JamSession?> = JamHolder.session
    val joining = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    val shareLink: StateFlow<String?> = combine(JamHolder.session, JamHolder.shareToken) { s, t -> JamHolder.shareLink(s, t) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, JamHolder.shareLink(JamHolder.session.value, JamHolder.shareToken.value))

    fun join(linkOrToken: String) {
        if (linkOrToken.isBlank()) return
        error.value = null
        launchWithSessionLoading("joinJam", joining) { sess ->
            error.value = JamHolder.join(sess, linkOrToken)
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
