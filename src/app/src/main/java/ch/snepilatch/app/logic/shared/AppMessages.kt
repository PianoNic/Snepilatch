package ch.snepilatch.app.logic.shared

import androidx.annotation.StringRes
import ch.snepilatch.app.data.UiMessage
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow

/**
 * The app's one-line messages, shown as Android's own toast. Process-scoped like [SessionHolder], so
 * any ViewModel can confirm an action or report a failure without a reference to another (#899).
 */
object AppMessages {
    internal val sink = MutableSharedFlow<UiMessage>(extraBufferCapacity = 4)
    val flow: SharedFlow<UiMessage> = sink

    fun show(@StringRes id: Int, vararg args: Any) {
        sink.tryEmit(UiMessage(id, args.toList()))
    }
}
