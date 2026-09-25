package ch.snepilatch.app.logic.shared

import android.content.Context
import android.content.SharedPreferences
import ch.snepilatch.app.data.PlayerShortcut
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the swipe gestures do: the actions on a track row's two swipe directions, and whether a
 * swipe down on the mini player opens the queue. Loaded with the rest of [AppSettings].
 */
object GestureSettings {

    val swipeLeftAction = MutableStateFlow(PlayerShortcut.ADD_TO_PLAYLIST)
    val swipeRightAction = MutableStateFlow(PlayerShortcut.ADD_TO_QUEUE)
    val swipeDownOpensQueue = MutableStateFlow(false)

    fun load(prefs: SharedPreferences) {
        swipeLeftAction.value = PlayerShortcut.perTrackFromId(prefs.getString("swipe_left_action", null), PlayerShortcut.ADD_TO_PLAYLIST)
        swipeRightAction.value = PlayerShortcut.perTrackFromId(prefs.getString("swipe_right_action", null), PlayerShortcut.ADD_TO_QUEUE)
        swipeDownOpensQueue.value = prefs.getBoolean("swipe_down_opens_queue", false)
    }

    /** One setter for both swipe directions; pass only the side that changed. */
    fun setSwipeActions(context: Context, left: PlayerShortcut = swipeLeftAction.value, right: PlayerShortcut = swipeRightAction.value) {
        swipeLeftAction.value = left
        swipeRightAction.value = right
        prefs(context).edit().putString("swipe_left_action", left.id).putString("swipe_right_action", right.id).apply()
    }

    fun setSwipeDownOpensQueue(enabled: Boolean, context: Context) {
        swipeDownOpensQueue.value = enabled
        prefs(context).edit().putBoolean("swipe_down_opens_queue", enabled).apply()
    }

    private fun prefs(context: Context) = context.getSharedPreferences(AppSettings.PREFS, Context.MODE_PRIVATE)
}
