package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.logic.shared.LoadError
import ch.snepilatch.app.logic.shared.LokiLogger
import ch.snepilatch.app.logic.shared.loadErrorOf
import kotify.api.home.Home
import kotify.api.home.HomeChip
import kotify.api.home.HomeData
import kotify.api.whatsnew.WhatsNewContentType
import kotify.api.whatsnew.WhatsNewFeed
import kotify.api.whatsnew.WhatsNewItem
import androidx.lifecycle.viewModelScope
import ch.snepilatch.app.logic.shared.SessionHolder
import ch.snepilatch.app.logic.shared.Debouncer
import kotify.api.playerconnect.PlayerConnect
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

    /** Why the feed failed with nothing to show, for the error state in place of a blank Home. */
    val loadError = MutableStateFlow<LoadError?>(null)

    /** The chip whose feed is shown, or "" for the whole feed, the way the web player's facet works. */
    private val _facet = MutableStateFlow("")
    val facet: StateFlow<String> = _facet

    /** The chips as the whole feed listed them, so the row stays the same while a chip's feed loads. */
    private val _chips = MutableStateFlow<List<HomeChip>>(emptyList())
    val chips: StateFlow<List<HomeChip>> = _chips

    /** A feed as it was loaded, by facet, so a chip already seen shows at once. */
    private class Cached(val data: HomeData, val episodes: List<WhatsNewItem>, val at: Long)
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Cached>()

    init {
        loadHome()
        subscribeToPushes()
        // A switched account gets a fresh feed instead of the old account's (#847).
        viewModelScope.launch {
            SessionHolder.generation.drop(1).collect {
                subscribeToPushes()
                cache.clear()
                _homeData.value = null
                _facet.value = ""
                _chips.value = emptyList()
                isLoading.value = true
                loadHome()
            }
        }
    }

    /**
     * Shows [chipId]'s feed; the chip already shown, or null, goes back to the whole feed. A feed seen in
     * the last half hour shows straight from the cache and is only asked for again once it is five
     * minutes old, the web player's gcTime and staleTime for the home query.
     */
    fun selectChip(chipId: String?) {
        val facet = if (chipId == null || chipId == _facet.value) "" else chipId
        _facet.value = facet
        val now = System.currentTimeMillis()
        val hit = cache[facet]?.takeIf { now - it.at < CACHE_KEEP_MS }
        if (hit != null) {
            _homeData.value = hit.data
            _episodes.value = hit.episodes
            if (now - hit.at >= CACHE_FRESH_MS) loadHome()
            return
        }
        // Nothing to show yet: the screen draws the skeleton under the row until the feed is in.
        _homeData.value = null
        loadHome()
    }

    /**
     * Asks for the shown feed again once it is older than the staleTime, and only then: the web player's
     * home query is refetched on mount and on window focus with React Query's defaults, which skip a
     * feed still fresh. The old feed stays on screen while the new one loads.
     */
    fun refreshIfStale() {
        val hit = cache[_facet.value] ?: return
        if (System.currentTimeMillis() - hit.at >= CACHE_FRESH_MS) loadHome()
    }

    /**
     * The followed podcasts' new episodes, for the podcasts "Following" chip. The web player does not ask
     * the home query for that chip, which answers it with the music releases; it lists
     * `spotify:list:whats-new:podcasts`, the What's New feed's episodes.
     */
    private val _episodes = MutableStateFlow<List<WhatsNewItem>>(emptyList())
    val episodes: StateFlow<List<WhatsNewItem>> = _episodes

    private var subscribedTo: PlayerConnect? = null

    /** Bursts of pushes, a playlist edited in several steps, collapse into one load. */
    private val refresh = Debouncer(
        scope = viewModelScope,
        waitMs = REFRESH_WAIT_MS,
        maxWaitMs = REFRESH_MAX_WAIT_MS,
    ) { loadHome() }

    /**
     * A playlist the feed shows changed: the daylist renames itself and changes its cover through the day.
     * The web player reloads a shown playlist on its `hm://playlist/v2/playlist` push, so the feed is asked
     * for again rather than showing the old name until it goes stale (#1057).
     */
    private fun subscribeToPushes() {
        viewModelScope.launch {
            val player = SessionHolder.awaitPlayer(PLAYER_WAIT_MS) ?: return@launch
            if (player === subscribedTo) return@launch
            subscribedTo = player
            player.onPlaylistChange { change ->
                val shown = _homeData.value?.sections.orEmpty().any { s -> s.items.any { it.uri == change.uri } }
                if (shown) {
                    LokiLogger.i(logTag, "${change.uri} changed, loading the feed again")
                    viewModelScope.launch { refresh.request() }
                }
            }
        }
    }

    /** "Try again" on the error state. */
    fun retry() {
        isLoading.value = true
        loadHome()
    }

    fun loadHome() {
        val facet = _facet.value
        if (facet == PODCASTS_FOLLOWING_CHIP) {
            loadFollowedEpisodes()
            return
        }
        loadError.value = null
        launchWithSession("loadHome", onFailure = { if (_homeData.value == null) loadError.value = loadErrorOf(it) }) { sess ->
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
                if (feed != null) cache[shown] = Cached(feed, emptyList(), System.currentTimeMillis())
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

    private fun loadFollowedEpisodes() {
        launchWithSession("loadFollowedEpisodes") { sess ->
            try {
                val page = WhatsNewFeed(sess).getFeed(types = setOf(WhatsNewContentType.EPISODES))
                // An empty feed stands in for the home data, so the screen stops waiting for one.
                val stand = HomeData(greeting = "", sections = emptyList(), chips = _chips.value)
                cache[PODCASTS_FOLLOWING_CHIP] = Cached(stand, page.items, System.currentTimeMillis())
                if (_facet.value != PODCASTS_FOLLOWING_CHIP) return@launchWithSession
                _episodes.value = page.items
                _homeData.value = stand
                LokiLogger.i(logTag, "Followed episodes loaded: ${page.items.size}")
            } finally {
                isLoading.value = false
            }
        }
    }

    companion object {
        const val PODCASTS_FOLLOWING_CHIP = "podcasts-following-chip"

        private const val REFRESH_WAIT_MS = 200L
        private const val REFRESH_MAX_WAIT_MS = 1000L

        /** As long as the library waits for the player to subscribe to its pushes. */
        private const val PLAYER_WAIT_MS = 30_000L

        /** The web player's staleTime for the home query: younger than this a cached feed is not asked for again. */
        private const val CACHE_FRESH_MS = 5 * 60 * 1000L

        /** Its gcTime: older than this a cached feed is not shown at all. */
        private const val CACHE_KEEP_MS = 30 * 60 * 1000L
    }
}
