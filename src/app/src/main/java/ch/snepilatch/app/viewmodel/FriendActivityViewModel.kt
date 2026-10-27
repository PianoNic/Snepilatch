package ch.snepilatch.app.viewmodel

import androidx.lifecycle.viewModelScope
import ch.snepilatch.app.logic.shared.SessionHolder
import ch.snepilatch.app.logic.shared.SessionViewModel
import kotify.api.user.FriendActivity
import kotify.api.user.FriendFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * What the people you follow are listening to (#843), kept live by Kotify's [FriendFeed] (#887).
 * [feed] is playing-first then newest, already resolved to names and images.
 *
 * Live only while something shows it (#1022): the open profile menu or the friend screen [watch] it,
 * the first one fetches and subscribes, and the last to [unwatch] unsubscribes, as the desktop client
 * does with its friend panel. The last list stays in [feed] meanwhile, so a reopened menu is not blank.
 */
class FriendActivityViewModel : SessionViewModel("FriendActivity") {

    val feed = MutableStateFlow<List<FriendActivity>>(emptyList())
    val loading = MutableStateFlow(false)
    private var live: FriendFeed? = null
    private var liveFeed: Job? = null
    private var watchers = 0

    fun watch() {
        watchers++
        if (watchers == 1) load()
    }

    fun unwatch() {
        watchers = (watchers - 1).coerceAtLeast(0)
        if (watchers > 0) return
        val friends = live ?: return
        live = null
        liveFeed?.cancel()
        // ponytail: a FriendFeed cannot be reopened, so the next watch builds a new one; its two client
        // listeners stay registered but ignored once closed. Add a reopen to Kotify if that ever adds up.
        CoroutineScope(Dispatchers.IO).launch { friends.close() }
    }

    fun load() = launchWithSessionLoading("load", loading) { sess ->
        // Waits for the player rather than giving up: the profile menu asks for the feed while the app is
        // still starting, before the player has registered (#976).
        val friends = live ?: FriendFeed(sess, SessionHolder.nextPlayer(), viewModelScope).also {
            live = it
            liveFeed = viewModelScope.launch { it.feed.collect { list -> feed.value = list } }
        }
        friends.load()
    }

    override fun onCleared() {
        // The unsubscribe outlives this scope, which is already cancelled here.
        live?.let { CoroutineScope(Dispatchers.IO).launch { it.close() } }
    }
}
