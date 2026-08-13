package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.playback.SessionHolder
import ch.snepilatch.app.util.LokiLogger
import kotify.api.jam.Jam
import kotify.api.jam.JamSession
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class JamViewModel : SessionViewModel("JamVM") {

    private val _jam = MutableStateFlow<JamSession?>(null)
    val jam: StateFlow<JamSession?> = _jam

    val joining = MutableStateFlow(false)
    val error = MutableStateFlow<String?>(null)

    /** The link we joined through — [JamSession.sessionId] is not stable, so leaving re-resolves it. */
    private var shareToken: String? = null

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
            val joined = Jam(sess).joinFromLink(token)
            if (joined == null) {
                error.value = "failed"
                LokiLogger.w(logTag, "Jam join failed for $token")
            } else {
                shareToken = token
                _jam.value = joined
                LokiLogger.i(logTag, "Joined jam ${joined.sessionId} (${joined.members.size} members)")
            }
        }
    }

    fun refresh() {
        val token = shareToken ?: return
        launchWithSession("refreshJam") { sess ->
            Jam(sess).getSession(token)?.let { _jam.value = it }
        }
    }

    fun leave() {
        val token = shareToken ?: return
        launchWithSessionLoading("leaveJam", joining) { sess ->
            val api = Jam(sess)
            // Re-read rather than reusing the joined id: a host restarting the jam rotates it.
            val current = api.getSession(token)
            val id = current?.sessionId ?: _jam.value?.sessionId
            if (id != null) api.leave(id)
            shareToken = null
            _jam.value = null
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
