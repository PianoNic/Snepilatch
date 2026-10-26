package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.logic.shared.LokiLogger
import kotify.api.home.Home
import kotify.api.home.HomeChip
import kotify.api.home.HomeData
import androidx.lifecycle.viewModelScope
import ch.snepilatch.app.logic.shared.SessionHolder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.StateFlow
import ch.snepilatch.app.logic.shared.SessionViewModel

/**
 * ViewModel for the Home feed. Loads the feed in [init] — the old eager load lived in
 * `PlaybackViewModel.initialize`, which runs before any composable exists; loading here fires as soon
 * as the Home screen composes (the default post-login screen), with [isLoading] driving the shimmer
 * until the first feed arrives.
 *
 * HomeScreen keeps [PlaybackViewModel] (for `playTrack`) and [DetailViewModel] (for opening items);
 * this VM only owns the feed data.
 */
class HomeViewModel : SessionViewModel("HomeVM") {

    private val _homeData = MutableStateFlow<HomeData?>(null)
    val homeData: StateFlow<HomeData?> = _homeData
    val isLoading = MutableStateFlow(true)

    /** The chip whose feed is shown, or "" for the whole feed, the way the web player's facet works. */
    private val _facet = MutableStateFlow("")
    val facet: StateFlow<String> = _facet

    /** The chips as the whole feed listed them, so the row stays the same while a chip's feed loads. */
    private val _chips = MutableStateFlow<List<HomeChip>>(emptyList())
    val chips: StateFlow<List<HomeChip>> = _chips

    init {
        loadHome()
        // A switched account gets a fresh feed instead of the old account's (#847).
        viewModelScope.launch {
            SessionHolder.generation.drop(1).collect {
                _homeData.value = null
                _facet.value = ""
                _chips.value = emptyList()
                isLoading.value = true
                loadHome()
            }
        }
    }

    /** Shows [chipId]'s feed; the chip already shown, or null, goes back to the whole feed. */
    fun selectChip(chipId: String?) {
        _facet.value = if (chipId == null || chipId == _facet.value) "" else chipId
        // The old feed goes at once: the official app shows nothing under the row until the new one is in.
        _homeData.value = null
        loadHome()
    }

    fun loadHome() {
        val facet = _facet.value
        launchWithSession("loadHome") { sess ->
            try {
                var shown = facet
                val feed = try {
                    Home(sess).getHomeFeed(facet)
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Exception) {
                    // A chip's feed that fails drops the chip, as the web player does with its facet.
                    if (facet.isEmpty()) throw e
                    LokiLogger.w(logTag, "Home feed for $facet failed, showing the whole feed: ${e.message}")
                    shown = ""
                    if (_facet.value == facet) _facet.value = ""
                    Home(sess).getHomeFeed()
                }
                // A chip tapped while this loaded owns the screen now.
                if (shown != _facet.value) return@launchWithSession
                _homeData.value = feed
                if (feed?.chips?.isNotEmpty() == true) _chips.value = feed.chips
                LokiLogger.i(logTag, "Home loaded: ${feed?.sections?.size} sections, facet=${facet.ifEmpty { "all" }}")
            } finally {
                isLoading.value = false
            }
        }
    }
}
