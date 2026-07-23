package ch.snepilatch.app.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import ch.snepilatch.app.playback.SessionHolder
import ch.snepilatch.app.util.LokiLogger
import kotify.api.home.Home
import kotify.api.home.HomeData
import kotify.session.Session
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * ViewModel for the Home feed. Loads the feed in [init] — the old eager load lived in
 * `SpotifyViewModel.initialize`, which runs before any composable exists; loading here fires as soon
 * as the Home screen composes (the default post-login screen), with [isLoading] driving the shimmer
 * until the first feed arrives.
 *
 * HomeScreen keeps [SpotifyViewModel] (for `playTrack`) and [DetailViewModel] (for opening items);
 * this VM only owns the feed data.
 */
class HomeViewModel : ViewModel() {

    private val tag = "HomeVM"

    private val _homeData = MutableStateFlow<HomeData?>(null)
    val homeData: StateFlow<HomeData?> = _homeData
    val isLoading = MutableStateFlow(true)

    init { loadHome() }

    fun loadHome() {
        launchSession("loadHome") { sess ->
            try {
                val feed = Home(sess).getHomeFeed()
                _homeData.value = feed
                LokiLogger.i(tag, "Home loaded: ${feed?.sections?.size} sections")
            } finally {
                isLoading.value = false
            }
        }
    }

    private fun launchSession(tag: String, block: suspend (Session) -> Unit): Job =
        viewModelScope.launch(Dispatchers.IO) {
            val sess = SessionHolder.session ?: return@launch
            try {
                block(sess)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                LokiLogger.e(this@HomeViewModel.tag, tag, e)
            }
        }
}
