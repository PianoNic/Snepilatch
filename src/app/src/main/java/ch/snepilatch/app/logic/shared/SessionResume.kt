package ch.snepilatch.app.logic.shared

import android.content.Context
import kotify.session.Session
import kotify.session.SessionSnapshot
import kotlinx.serialization.json.Json

/**
 * The live session's state, kept the way a browser keeps a tab's: the cookies the server last set and
 * the tokens it last minted. A cold start within the token's life resumes from it instead of minting
 * again (every app restart minted, and a run of them drew the anti-abuse 400). Kotify ignores an
 * expired snapshot; this only makes sure it is the same account's.
 */
object SessionResume {
    private const val KEY = "session_snapshot"
    private var appContext: Context? = null

    // The session being built or running. A torn down one may still finish a mint; it must not
    // overwrite the cookies of the account that replaced it.
    @Volatile private var active: Session? = null

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    /** The saved snapshot when it belongs to the account these [cookies] open, else null. */
    fun forCookies(cookies: Map<String, String>): SessionSnapshot? {
        val raw = prefs()?.getString(KEY, null) ?: return null
        val snap = runCatching { Json.decodeFromString<SessionSnapshot>(raw) }.getOrNull()
        return snap?.takeIf { it.cookies["sp_dc"] != null && it.cookies["sp_dc"] == cookies["sp_dc"] }
    }

    /** Saves [session]'s state whenever Kotify reports a new token or changed cookies. */
    fun track(session: Session) {
        active = session
        session.onStateChanged = { save(session) }
    }

    fun clear() {
        active = null
        prefs()?.edit()?.remove(KEY)?.apply()
    }

    private fun save(session: Session) {
        if (session !== active) return
        val snap = runCatching { session.snapshot() }.getOrNull() ?: return
        prefs()?.edit()?.putString(KEY, Json.encodeToString(SessionSnapshot.serializer(), snap))?.apply()
        appContext?.let { saveCookies(it, snap.cookies) }
        AccountStore.updateCookies(snap.cookies)
    }

    private fun prefs() = appContext?.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)
}
