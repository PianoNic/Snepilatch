package ch.snepilatch.app.logic.shared

import android.content.Context
import android.content.SharedPreferences
import kotify.session.Session
import kotify.session.SessionSnapshot
import kotlinx.serialization.json.Json

/**
 * The last session's tokens, kept so a cold start within the token's life resumes it the way an open
 * tab would, instead of minting a new one (every app restart minted, and a run of them drew the
 * anti-abuse 400). Kotify ignores an expired snapshot; this only makes sure it is the same account's.
 */
object SessionResume {
    private const val KEY = "session_snapshot"
    private var prefs: SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)
    }

    /** The saved snapshot when it belongs to the account these [cookies] open, else null. */
    fun forCookies(cookies: Map<String, String>): SessionSnapshot? {
        val snap = prefs?.getString(KEY, null)?.let { runCatching { Json.decodeFromString<SessionSnapshot>(it) }.getOrNull() }
        return snap?.takeIf { it.cookies["sp_dc"] != null && it.cookies["sp_dc"] == cookies["sp_dc"] }
    }

    fun save(session: Session) {
        val json = runCatching { Json.encodeToString(SessionSnapshot.serializer(), session.snapshot()) }.getOrNull() ?: return
        prefs?.edit()?.putString(KEY, json)?.apply()
    }
}
