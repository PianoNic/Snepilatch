package ch.snepilatch.app.auth

import android.content.Context
import ch.snepilatch.app.util.LokiLogger
import kotify.session.SessionSnapshot
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists a Kotify [SessionSnapshot] to a single JSON file in
 * `filesDir/session_snapshot.json`. Pair with [kotify.session.Session.hydrate]
 * on cold start to skip the apresolve / token / client-token network calls.
 *
 * The snapshot is rewritten after every successful `Session.load()` and after
 * every successful `BaseClient.refreshAccessToken()`, so the on-disk copy
 * tracks the latest `accessTokenExpirationTimestampMs`.
 */
object SessionSnapshotStore {

    private const val TAG = "SnapshotStore"
    private const val FILE_NAME = "session_snapshot.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): SessionSnapshot? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return try {
            json.decodeFromString<SessionSnapshot>(file.readText())
        } catch (e: Exception) {
            LokiLogger.w(TAG, "Failed to decode session snapshot: ${e.message?.take(120)}")
            null
        }
    }

    fun save(context: Context, snapshot: SessionSnapshot) {
        val file = File(context.filesDir, FILE_NAME)
        try {
            file.writeText(json.encodeToString(SessionSnapshot.serializer(), snapshot))
            LokiLogger.i(TAG, "Saved session snapshot (token expires at ${snapshot.baseClient.accessTokenExpirationTimestampMs})")
        } catch (e: Exception) {
            LokiLogger.w(TAG, "Failed to save session snapshot: ${e.message?.take(120)}")
        }
    }

    fun clear(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) file.delete()
    }

    /**
     * True if [snapshot] has an access token whose expiry is at least
     * [graceMs] ms in the future — meaning it's safe to hydrate from this
     * snapshot without an immediate `refreshAccessToken()` call.
     */
    fun isTokenValid(snapshot: SessionSnapshot, graceMs: Long = 5 * 60_000L): Boolean {
        val expiresAt = snapshot.baseClient.accessTokenExpirationTimestampMs ?: return false
        return expiresAt - System.currentTimeMillis() > graceMs
    }
}
