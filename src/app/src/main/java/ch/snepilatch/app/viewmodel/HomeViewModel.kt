package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.util.LokiLogger
import kotify.api.home.Home
import kotify.api.home.HomeData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * ViewModel for the Home feed. Loads the feed in [init] — the old eager load lived in
 * `SpotifyViewModel.initialize`, which runs before any composable exists; loading here fires as soon
 * as the Home screen composes (the default post-login screen), with [isLoading] driving the shimmer
 * until the first feed arrives.
 *
 * HomeScreen keeps [SpotifyViewModel] (for `playTrack`) and [DetailViewModel] (for opening items);
 * this VM only owns the feed data.
 */
class HomeViewModel : SessionViewModel("HomeVM") {

    private val _homeData = MutableStateFlow<HomeData?>(null)
    val homeData: StateFlow<HomeData?> = _homeData
    val isLoading = MutableStateFlow(true)

    init { loadHome() }

    fun loadHome() {
        launchWithSession("loadHome") { sess ->
            try {
                val feed = Home(sess).getHomeFeed()
                _homeData.value = feed
                LokiLogger.i(logTag, "Home loaded: ${feed?.sections?.size} sections")
            } finally {
                isLoading.value = false
            }
        }
    }
}
