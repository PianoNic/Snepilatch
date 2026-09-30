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
 * podcasts. [hasNew] is the dot: as in the web player it is first checked one [POLL_MS] after start,
 * then every [POLL_MS], and lights only when the answer turns from no to yes. Opening the feed clears
 * it, loads the first 50 items as the web player does, and marks every NEW one seen.
 */
class WhatsNewViewModel : SessionViewModel("WhatsNew") {

    val items = MutableStateFlow<List<WhatsNewItem>>(emptyList())
    val loading = MutableStateFlow(false)
    val failed = MutableStateFlow(false)
    val hasNew = MutableStateFlow(false)

    /** The last answer of the check; the dot lights only when it turns true. */
    private var lastHasNew = false

    /** The filter chip that is on, or null for everything. */
    val filter = MutableStateFlow<WhatsNewContentType?>(null)

    init {
        viewModelScope.launch(Dispatchers.IO) {
            while (isActive) {
                delay(POLL_MS)
                checkForNew()
            }
        }
        // A switched account has its own feed.
        viewModelScope.launch {
            SessionHolder.generation.drop(1).collect {
                items.value = emptyList()
                hasNew.value = false
                lastHasNew = false
            }
        }
    }

    private suspend fun checkForNew() {
        val sess = SessionHolder.session ?: return
        try {
            val now = WhatsNewFeed(sess).hasNewItems()
            if (now && !lastHasNew) hasNew.value = true
            lastHasNew = now
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
            LokiLogger.i(logTag, "Feed: ${page.items.size} of ${page.totalCount}")
            val fresh = page.items.filter { it.state == WhatsNewItemState.NEW }.map { it.id }
            if (fresh.isNotEmpty()) feed.markSeen(fresh)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            failed.value = true
            LokiLogger.w(logTag, "What's New failed to load: ${e.message}")
        }
    }

    private companion object {
        /** The web player's interval for whatsNewFeedNewItems: 144e5 ms, four hours. */
        const val POLL_MS = 14_400_000L
    }
}
