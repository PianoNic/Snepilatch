package ch.snepilatch.app.viewmodel

import androidx.lifecycle.viewModelScope
import ch.snepilatch.app.logic.relay.RelayJam
import ch.snepilatch.app.logic.relay.RelayJamMapper
import ch.snepilatch.app.logic.relay.RelaySettings
import ch.snepilatch.app.logic.playback.JamListenAlong
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

    val starting = MutableStateFlow(false)

    val shareLink: StateFlow<String?> = combine(JamHolder.session, JamHolder.shareToken) { s, t -> linkFor(s, t) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, linkFor(JamHolder.session.value, JamHolder.shareToken.value))

    private fun linkFor(session: JamSession?, token: String?): String? =
        if (RelayJamMapper.isRelay(session)) {
            session?.let { RelayJamMapper.inviteLink(RelaySettings.url.value, it.joinSessionToken) }
        } else {
            JamHolder.shareLink(session, token)
        }

    /** The invite sheet's links: short ones once [loadInviteLinks] has them, the long link until then. */
    val inviteLinks: StateFlow<JamHolder.InviteSheetLinks?> =
        combine(shareLink, JamHolder.session, JamHolder.inviteLinks) { long, jam, cached -> JamHolder.inviteSheetLinks(long, jam, cached) }
            .stateIn(
                viewModelScope,
                SharingStarted.Eagerly,
                JamHolder.inviteSheetLinks(shareLink.value, JamHolder.session.value, JamHolder.inviteLinks.value),
            )

    /** Starts a jam this account hosts, through the relay. */
    fun start() {
        if (starting.value || RelayJam.active) return
        starting.value = true
        error.value = null
        launchWithSessionLoading("startJam", starting) { sess ->
            leaveSpfyJam(sess)
            RelayJam.create()?.let { error.value = START_FAILED }
        }.invokeOnCompletion { starting.value = false }
    }

    fun loadInviteLinks() {
        if (RelayJam.active) return
        launchWithSession("inviteLinks") { sess -> JamHolder.loadInviteLinks(sess) }
    }

    fun join(linkOrToken: String) {
        if (linkOrToken.isBlank()) return
        error.value = null
        RelayJamMapper.joinToken(linkOrToken)?.let { token ->
            launchWithSessionLoading("previewRelayJam", joining) {
                RelayJam.preview(token)?.let { error.value = JamHolder.FAILED }
            }
            return
        }
        launchWithSessionLoading("previewJam", joining) { sess ->
            error.value = JamHolder.preview(sess, linkOrToken)
        }
    }

    /** How the join sheet takes part in a jam. */
    enum class JoinChoice {
        /** Play the jam on this phone through Spfy; Premium only, the server refuses it otherwise. */
        LISTEN,

        /** Control the host's playback; the audio stays on the host's device. */
        CONTROL,

        /** Control on Spfy and play the jam on this phone from YouTube Music (#1026). */
        LISTEN_VIA_YOUTUBE,
    }

    fun confirmJoin(choice: JoinChoice) {
        error.value = null
        val pending = JamHolder.pending.value
        if (pending != null && RelayJamMapper.isRelay(pending.jam)) {
            launchWithSessionLoading("joinRelayJam", joining) { sess ->
                leaveSpfyJam(sess)
                error.value = RelayJam.join(pending.link, listen = choice == JoinChoice.LISTEN)?.let { JamHolder.FAILED }
                if (error.value == null) JamHolder.pending.value = null
            }
            return
        }
        launchWithSessionLoading("joinJam", joining) { sess ->
            if (RelayJam.active) RelayJam.leave()
            error.value = JamHolder.join(sess, listen = choice == JoinChoice.LISTEN)
            if (error.value == null && choice == JoinChoice.LISTEN_VIA_YOUTUBE) JamListenAlong.start()
        }
    }

    fun cancelJoin() {
        JamHolder.pending.value = null
    }

    /** Ask the server again; the pushes normally keep the holder current on their own. */
    fun refresh() {
        launchWithSession("refreshJam") { SessionHolder.player?.refreshJam() }
    }

    fun leave() {
        val current = jam.value ?: return
        if (RelayJamMapper.isRelay(current)) {
            launchWithSessionLoading("leaveRelayJam", joining) { RelayJam.leave() }
            return
        }
        launchWithSessionLoading("leaveJam", joining) { sess ->
            // The push that follows clears the holder too; clearing here keeps the sheet honest when
            // the socket is slow.
            if (Jam(sess).leave(current.sessionId)) JamHolder.clear()
        }
    }

    /**
     * One jam at a time: an account still in a Spfy jam has its playback routed to that jam's host, so a
     * relay jam's play commands would change someone else's music. Leaves it (ends it when hosting) first.
     */
    private suspend fun leaveSpfyJam(sess: kotify.session.Session) {
        val current = JamHolder.session.value?.takeUnless { RelayJamMapper.isRelay(it) } ?: return
        val jam = Jam(sess)
        if (if (current.isSessionOwner) jam.end(current.sessionId) else jam.leave(current.sessionId)) JamHolder.clear()
        JamListenAlong.stop()
    }

    /** Ends the jam for everyone. The server only lets the host do this. */
    fun end() {
        val current = jam.value ?: return
        if (RelayJamMapper.isRelay(current)) {
            launchWithSessionLoading("endRelayJam", joining) { RelayJam.end() }
            return
        }
        launchWithSessionLoading("endJam", joining) { sess ->
            if (Jam(sess).end(current.sessionId)) JamHolder.clear()
        }
    }

    companion object {
        const val START_FAILED = "start_failed"
    }
}
