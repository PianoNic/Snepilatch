package ch.snepilatch.app.logic.shared

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Whether the phone has a validated route to the internet, as the system reports it. Fed by the
 * playback service's default network callback, read by the playback view model to decide when the
 * offline engine takes over and when Connect gets playback back. Process-scoped like
 * [SessionHolder]. Starts optimistic: nothing is decided on it until the callback has spoken.
 */
object NetworkState {
    private val _online = MutableStateFlow(true)
    val online: StateFlow<Boolean> = _online.asStateFlow()

    /** When the route last came back, so a handover right after can be told from a real one. */
    @Volatile private var onlineSince = 0L

    fun set(online: Boolean) {
        if (_online.value != online) {
            LokiLogger.i("NetworkState", if (online) "network back" else "network gone")
            if (online) onlineSince = System.currentTimeMillis()
        }
        _online.value = online
    }

    /** True for a moment after the route came back, while the system may still be settling on a network. */
    fun justCameBack(): Boolean = _online.value && System.currentTimeMillis() - onlineSince < SETTLE_MS

    private const val SETTLE_MS = 15_000L
}
