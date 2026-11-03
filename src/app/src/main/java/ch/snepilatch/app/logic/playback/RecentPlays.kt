package ch.snepilatch.app.logic.playback

import android.content.Context
import android.content.SharedPreferences
import ch.snepilatch.app.logic.shared.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * What was played last, newest first, kept on this phone and sent nowhere, for the offline home. The server's
 * own history needs the network, which is exactly what that page does not have.
 */
object RecentPlays {
    private const val KEEP = 50
    private const val KEY_TRACKS = "recent_tracks"
    private const val KEY_CONTEXTS = "recent_contexts"

    private val _tracks = MutableStateFlow<List<String>>(emptyList())
    private val _contexts = MutableStateFlow<List<String>>(emptyList())
    val tracks: StateFlow<List<String>> = _tracks
    val contexts: StateFlow<List<String>> = _contexts
    private var prefs: SharedPreferences? = null

    fun load(context: Context) {
        val stored = context.applicationContext.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)
        prefs = stored
        _tracks.value = stored.getString(KEY_TRACKS, null)?.lines().orEmpty()
        _contexts.value = stored.getString(KEY_CONTEXTS, null)?.lines().orEmpty()
    }

    fun trackPlayed(uri: String) = push(_tracks, KEY_TRACKS, uri)

    fun contextPlayed(uri: String) = push(_contexts, KEY_CONTEXTS, uri)

    private fun push(list: MutableStateFlow<List<String>>, key: String, uri: String) {
        if (list.value.firstOrNull() == uri) return
        val next = moveToFront(list.value, uri, KEEP)
        list.value = next
        prefs?.edit()?.putString(key, next.joinToString("\n"))?.apply()
    }
}

/** [uri] first, once, and at most [keep] entries. */
internal fun moveToFront(list: List<String>, uri: String, keep: Int): List<String> =
    (listOf(uri) + list.filterNot { it == uri }).take(keep)
