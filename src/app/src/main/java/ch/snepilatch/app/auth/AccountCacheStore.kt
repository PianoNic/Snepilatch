package ch.snepilatch.app.auth

import android.content.Context
import ch.snepilatch.app.data.AccountInfo
import ch.snepilatch.app.util.LokiLogger
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Persists the last-known [AccountInfo] to disk so the home screen can show
 * the user's name and avatar instantly on cold start while the live profile
 * fetch runs in the background. Refreshed by [ch.snepilatch.app.viewmodel.SpotifyViewModel]
 * after every successful profile fetch.
 */
object AccountCacheStore {

    private const val TAG = "AccountCache"
    private const val FILE_NAME = "account_cache.json"

    private val json = Json { ignoreUnknownKeys = true }

    fun load(context: Context): AccountInfo? {
        val file = File(context.filesDir, FILE_NAME)
        if (!file.exists()) return null
        return try {
            json.decodeFromString<AccountInfo>(file.readText())
        } catch (e: Exception) {
            LokiLogger.w(TAG, "Failed to decode account cache: ${e.message?.take(120)}")
            null
        }
    }

    fun save(context: Context, info: AccountInfo) {
        val file = File(context.filesDir, FILE_NAME)
        try {
            file.writeText(json.encodeToString(AccountInfo.serializer(), info))
        } catch (e: Exception) {
            LokiLogger.w(TAG, "Failed to save account cache: ${e.message?.take(120)}")
        }
    }

    fun clear(context: Context) {
        val file = File(context.filesDir, FILE_NAME)
        if (file.exists()) file.delete()
    }
}
