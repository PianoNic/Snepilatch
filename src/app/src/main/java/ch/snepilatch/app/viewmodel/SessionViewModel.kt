package ch.snepilatch.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.snepilatch.app.playback.SessionHolder
import ch.snepilatch.app.util.LokiLogger
import kotify.session.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * Base for the feature ViewModels that only read the Kotify [Session] from [SessionHolder]
 * (Search / Lyrics / Detail / Library / Home). Centralizes the "launch on IO, null-check the
 * session, rethrow cancellation, log everything else against [logTag]" boilerplate each of them
 * was duplicating.
 *
 * Not used by [SpotifyViewModel], which owns the session lifecycle and also needs player-scoped
 * launches.
 */
abstract class SessionViewModel(protected val logTag: String) : ViewModel() {

    /** Launch [block] on IO with a non-null session; rethrows cancellation, logs other failures. */
    protected fun launchWithSession(op: String, block: suspend (Session) -> Unit): Job =
        viewModelScope.launch(Dispatchers.IO) {
            val sess = SessionHolder.session ?: return@launch
            try {
                block(sess)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LokiLogger.e(logTag, op, e)
            }
        }

    /** [launchWithSession] that flips [loading] true around the block and always resets it. */
    protected fun launchWithSessionLoading(
        op: String,
        loading: MutableStateFlow<Boolean>,
        block: suspend (Session) -> Unit
    ): Job =
        viewModelScope.launch(Dispatchers.IO) {
            val sess = SessionHolder.session ?: return@launch
            loading.value = true
            try {
                block(sess)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LokiLogger.e(logTag, op, e)
            } finally {
                loading.value = false
            }
        }
}
