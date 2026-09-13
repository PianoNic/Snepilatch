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

    fun set(online: Boolean) {
        if (_online.value != online) LokiLogger.i("NetworkState", if (online) "network back" else "network gone")
        _online.value = online
    }
}
