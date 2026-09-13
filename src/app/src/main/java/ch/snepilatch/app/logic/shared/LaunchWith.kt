package ch.snepilatch.app.logic.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * The launch every ViewModel repeats: [block] runs on IO with what [get] provides, and not at all
 * when that is null. [before] runs once the target is there, cancellation passes through, any other
 * failure is logged against [tag] and [op] and handed to [onFailure], and [after] always runs last.
 */
fun <T> ViewModel.launchWith(
    tag: String,
    op: String,
    get: () -> T?,
    before: () -> Unit = {},
    onFailure: (Exception) -> Unit = {},
    after: () -> Unit = {},
    block: suspend (T) -> Unit,
): Job = viewModelScope.launch(Dispatchers.IO) {
    val target = get() ?: return@launch
    before()
    try {
        block(target)
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        LokiLogger.e(tag, op, e)
        onFailure(e)
    } finally {
        after()
    }
}
