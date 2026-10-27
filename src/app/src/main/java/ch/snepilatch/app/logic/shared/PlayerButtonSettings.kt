package ch.snepilatch.app.logic.shared

import android.content.Context
import android.content.SharedPreferences
import ch.snepilatch.app.data.PlayerShortcut
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the full-screen player's top right, share and queue buttons do. Loaded with the rest of
 * [AppSettings], kept apart like [GestureSettings].
 */
object PlayerButtonSettings {

    val topRight = MutableStateFlow(PlayerShortcut.EQUALIZER)
    val share = MutableStateFlow(PlayerShortcut.SHARE)
    val queue = MutableStateFlow(PlayerShortcut.QUEUE)

    fun load(prefs: SharedPreferences) {
        topRight.value = PlayerShortcut.fromId(prefs.getString("player_top_right_shortcut", null), PlayerShortcut.EQUALIZER)
        share.value = PlayerShortcut.fromId(prefs.getString("player_share_shortcut", null), PlayerShortcut.SHARE)
        queue.value = PlayerShortcut.fromId(prefs.getString("player_queue_shortcut", null), PlayerShortcut.QUEUE)
    }

    /** One setter for the three buttons; pass only the one that changed. */
    fun set(
        context: Context,
        topRight: PlayerShortcut = this.topRight.value,
        share: PlayerShortcut = this.share.value,
        queue: PlayerShortcut = this.queue.value,
    ) {
        this.topRight.value = topRight
        this.share.value = share
        this.queue.value = queue
        context.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE).edit()
            .putString("player_top_right_shortcut", topRight.id)
            .putString("player_share_shortcut", share.id)
            .putString("player_queue_shortcut", queue.id)
            .apply()
    }
}
