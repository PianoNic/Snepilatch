package ch.snepilatch.app.logic.shared

import android.content.Context
import ch.snepilatch.app.BuildConfig
import ch.snepilatch.app.data.PlaybackUiState
import com.google.gson.Gson
import com.google.gson.JsonObject
import java.io.File
import java.lang.reflect.Type

/**
 * What the screens last showed, so a launch draws them at once and the network only replaces them:
 * the home feed, the library and the track in the mini player. Gone the moment it could be wrong: it
 * belongs to one account and one app version, since Gson fills a field a newer build added with null.
 */
object StartupCache {
    private val gson = Gson()
    private var dir: File? = null

    /** Whose cache is read and written: the account the session was built from. */
    @Volatile private var owner: String? = null

    fun init(context: Context) {
        dir = File(context.filesDir, "startup")
    }

    fun <T> read(name: String, type: Type): T? = runCatching {
        val file = File(dir ?: return null, "$name.json").takeIf { it.exists() } ?: return null
        val box = gson.fromJson(file.readText(), JsonObject::class.java)
        val current = box["owner"]?.asString == (owner ?: return null) && box["version"]?.asString == BuildConfig.VERSION_NAME
        if (current) gson.fromJson<T>(box["value"], type) else null
    }.onFailure { LokiLogger.w(TAG, "$name unreadable: ${it.message}") }.getOrNull()

    /** Call off the main thread: it writes a file. */
    fun write(name: String, value: Any) {
        val who = owner ?: return
        val d = dir ?: return
        runCatching {
            d.mkdirs()
            val box = JsonObject().apply {
                addProperty("owner", who)
                addProperty("version", BuildConfig.VERSION_NAME)
                add("value", gson.toJsonTree(value))
            }
            // Written aside and moved, so a process killed mid-write leaves the old file, not half a new one.
            val tmp = File(d, "$name.tmp").apply { writeText(box.toString()) }
            tmp.renameTo(File(d, "$name.json"))
        }.onFailure { LokiLogger.w(TAG, "$name not saved: ${it.message}") }
    }

    /**
     * Makes [cookies]' account the owner, and returns [shown] or, when it has no track, the one the mini
     * player last showed, paused: what plays now is only known once the player is up.
     */
    fun restore(cookies: Map<String, String>, shown: PlaybackUiState): PlaybackUiState {
        owner = cookies["sp_dc"]?.hashCode()?.toString()
        if (shown.track != null) return shown
        return read<PlaybackUiState>(PLAYBACK_FILE, PlaybackUiState::class.java)?.copy(isPlaying = false, isPaused = true) ?: shown
    }

    fun savePlayback(state: PlaybackUiState) {
        if (state.track != null && !state.isAd) write(PLAYBACK_FILE, state)
    }

    private const val TAG = "StartupCache"
    private const val PLAYBACK_FILE = "playback"
}
