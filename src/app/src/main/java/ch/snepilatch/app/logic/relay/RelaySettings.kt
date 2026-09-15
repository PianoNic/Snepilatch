package ch.snepilatch.app.logic.relay

import android.content.Context
import android.content.SharedPreferences
import ch.snepilatch.app.logic.shared.AppSettings
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * The Snepirelay server jams run through; a self-hoster points it at their own. Always a full
 * address, [DEFAULT_URL] when the user leaves the field empty. Loaded with the rest of [AppSettings].
 */
object RelaySettings {

    const val DEFAULT_URL = "https://snepirelay.pianonic.ch"
    private const val KEY = "relay_url"

    val url = MutableStateFlow(DEFAULT_URL)

    fun load(prefs: SharedPreferences) {
        url.value = prefs.getString(KEY, DEFAULT_URL) ?: DEFAULT_URL
    }

    /** Blank goes back to [DEFAULT_URL]; a trailing slash is dropped so the socket path joins cleanly. */
    fun set(value: String, context: Context) {
        val cleaned = value.trim().trimEnd('/').ifBlank { DEFAULT_URL }
        url.value = cleaned
        context.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE).edit().putString(KEY, cleaned).apply()
    }
}
