package ch.snepilatch.app.logic.shared

import androidx.lifecycle.ViewModel
import kotify.session.Session
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Base for the feature ViewModels that only read the Kotify [Session] from [SessionHolder]
 * (Search / Lyrics / Detail / Library / Home). Centralizes the "launch on IO, null-check the
 * session, rethrow cancellation, log everything else against [logTag]" boilerplate each of them
 * was duplicating.
 *
 * Not used by [PlaybackViewModel], which owns the session lifecycle and also needs player-scoped
 * launches.
 */
abstract class SessionViewModel(protected val logTag: String) : ViewModel() {

    /** Launch [block] on IO with a non-null session; rethrows cancellation, logs other failures to [onFailure]. */
    protected fun launchWithSession(
        op: String,
        onFailure: (Exception) -> Unit = {},
        block: suspend (Session) -> Unit
    ): Job = launchWith(logTag, op, { SessionHolder.session }, onFailure = onFailure, block = block)

    /** [launchWithSession] that flips [loading] true around the block and always resets it. */
    protected fun launchWithSessionLoading(
        op: String,
        loading: MutableStateFlow<Boolean>,
        block: suspend (Session) -> Unit
    ): Job =
        launchWith(
            logTag,
            op,
            { SessionHolder.session },
            before = { loading.value = true },
            after = { loading.value = false },
            block = block,
        )
}
