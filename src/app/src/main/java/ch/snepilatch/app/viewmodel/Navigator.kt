package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.data.Screen
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Single source of truth for which [Screen] is showing, plus the back stack.
 *
 * Process-scoped (like [ch.snepilatch.app.playback.SessionHolder]) so feature
 * ViewModels can drive navigation without routing through [PlaybackViewModel].
 * [PlaybackViewModel] delegates its nav methods here and calls [reset] on
 * construction — a freshly constructed ViewModel means a fresh app entry
 * (Activity finished + recreated, or cold process), which mirrors the old
 * behaviour where `currentScreen` lived on the ViewModel and reset to HOME.
 * Config changes retain the ViewModel, so navigation survives rotation.
 */
object Navigator {

    private val _currentScreen = MutableStateFlow(Screen.HOME)
    val currentScreen: StateFlow<Screen> = _currentScreen

    private var screenStack = mutableListOf<Screen>()

    /** The player and lyrics are overlays, not pages — they never sit on the back stack. */
    private fun isOverlay(s: Screen) = s == Screen.NOW_PLAYING || s == Screen.LYRICS

    fun navigateTo(screen: Screen) {
        val current = _currentScreen.value
        if (screen == current) return
        // Don't record an overlay we're leaving for a real page, else back re-opens the
        // player instead of returning to the page beneath it.
        if (!isOverlay(current) || isOverlay(screen)) screenStack.add(current)
        _currentScreen.value = screen
    }

    /** Tabs are roots: switching resets the stack so back returns to Home, not a stale page. */
    fun navigateToTab(screen: Screen) {
        screenStack.clear()
        if (screen != Screen.HOME) screenStack.add(Screen.HOME)
        _currentScreen.value = screen
    }

    fun goBack(): Boolean {
        if (screenStack.isNotEmpty()) {
            _currentScreen.value = screenStack.removeAt(screenStack.lastIndex)
            return true
        }
        return false
    }

    /** Return to the HOME root with an empty stack — called when a fresh ViewModel is created. */
    fun reset() {
        screenStack.clear()
        _currentScreen.value = Screen.HOME
    }
}
