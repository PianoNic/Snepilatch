package ch.snepilatch.app.viewmodel

import androidx.lifecycle.viewModelScope
import ch.snepilatch.app.logic.shared.LokiLogger
import ch.snepilatch.app.logic.shared.SessionHolder
import ch.snepilatch.app.logic.shared.SessionViewModel
import kotify.api.whatsnew.WhatsNewContentType
import kotify.api.whatsnew.WhatsNewFeed
import kotify.api.whatsnew.WhatsNewItem
import kotify.api.whatsnew.WhatsNewItemState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The web player's bell (#884): new releases from followed artists and new episodes of followed
 * podcasts. [hasNew] is the dot, checked every [POLL_MS] as the web player does; opening the feed
 * clears it and marks every NEW item seen, as the web player does once its feed is on screen.
 */
class WhatsNewViewModel : SessionViewModel("WhatsNew") {

    val items = MutableStateFlow<List<WhatsNewItem>>(emptyList())
    val loading = MutableStateFlow(false)
    val failed = MutableStateFlow(false)
    val hasNew = MutableStateFlow(false)

    /** Where the next page starts, or null once the last one is in. */
    private var nextOffset: Int? = null
    val loadingMore = MutableStateFlow(false)

    /** The filter chip that is on, or null for everything. */
    val filter = MutableStateFlow<WhatsNewContentType?>(null)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                checkForNew()
                delay(POLL_MS)
            }
        }
        // A switched account has its own feed.
        viewModelScope.launch {
            SessionHolder.generation.drop(1).collect {
                items.value = emptyList()
                hasNew.value = false
                launch(Dispatchers.IO) { checkForNew() }
            }
        }
    }

    private suspend fun checkForNew() {
        val sess = SessionHolder.session ?: return
        try {
            if (WhatsNewFeed(sess).hasNewItems()) hasNew.value = true
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            LokiLogger.d(logTag, "new items check failed: ${e.message}")
        }
    }

    /** The feed was opened: the dot goes, the list loads. */
    fun open() {
        hasNew.value = false
        load()
    }

    /** Turns a chip on, or off when it was already on; the web player's chips work the same way. */
    fun toggleFilter(type: WhatsNewContentType) {
        filter.value = if (filter.value == type) null else type
        load()
    }

    fun load() = launchWithSessionLoading("load", loading) { sess ->
        failed.value = false
        try {
            val feed = WhatsNewFeed(sess)
            val page = feed.getFeed(types = setOfNotNull(filter.value))
            // A chapter or a feed notification has nothing the app shows.
            items.value = page.items.filter { it.content != null }
            nextOffset = page.nextOffset
            LokiLogger.i(logTag, "Feed: ${page.items.size} of ${page.totalCount}, next page at ${page.nextOffset}")
            markSeen(page.items)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed.value = true
            LokiLogger.w(logTag, "What's New failed to load: ${e.message}")
        }
    }

    /**
     * The next page, appended, for scrolling past the first one. The web player shows only the first 50;
     * the feed pages on through older releases, as the desktop app does.
     */
    fun loadMore() {
        val offset = nextOffset ?: return
        if (loadingMore.value || loading.value) return
        launchWithSessionLoading("loadMore", loadingMore) { sess ->
            val feed = WhatsNewFeed(sess)
            val page = feed.getFeed(offset = offset, types = setOfNotNull(filter.value))
            val known = items.value.mapTo(HashSet()) { it.id }
            items.value = items.value + page.items.filter { it.content != null && it.id !in known }
            nextOffset = page.nextOffset
            LokiLogger.i(logTag, "Feed page at $offset: ${page.items.size} more of ${page.totalCount}, next at ${page.nextOffset}")
            markSeen(page.items)
        }
    }

    /**
     * Outside the loading flag: holding it for this second request turned away a scroll to the end that
     * came in meanwhile, and the list then never loaded its next page.
     */
    private fun markSeen(page: List<WhatsNewItem>) {
        val fresh = page.filter { it.state == WhatsNewItemState.NEW }.map { it.id }
        if (fresh.isNotEmpty()) launchWithSession("markSeen") { sess -> WhatsNewFeed(sess).markSeen(fresh) }
    }

    private companion object {
        /** The web player's interval for whatsNewFeedNewItems. */
        const val POLL_MS = 144_000L
    }
}
