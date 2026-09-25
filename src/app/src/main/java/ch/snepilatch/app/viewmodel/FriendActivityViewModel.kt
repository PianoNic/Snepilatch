package ch.snepilatch.app.viewmodel

import androidx.lifecycle.viewModelScope
import ch.snepilatch.app.logic.shared.SessionHolder
import ch.snepilatch.app.logic.shared.SessionViewModel
import kotify.api.user.FriendActivity
import kotify.api.user.FriendFeed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/**
 * What the people you follow are listening to (#843), kept live by Kotify's [FriendFeed] (#887).
 * [feed] is playing-first then newest, already resolved to names and images.
 */
class FriendActivityViewModel : SessionViewModel("FriendActivity") {

    val feed = MutableStateFlow<List<FriendActivity>>(emptyList())
    val loading = MutableStateFlow(false)
    private var live: FriendFeed? = null

    fun load() = launchWithSessionLoading("load", loading) { sess ->
        val friends = live ?: FriendFeed(sess, SessionHolder.player ?: return@launchWithSessionLoading, viewModelScope).also {
            live = it
            viewModelScope.launch { it.feed.collect { list -> feed.value = list } }
        }
        friends.load()
    }

    override fun onCleared() {
        // The unsubscribe outlives this scope, which is already cancelled here.
        live?.let { CoroutineScope(Dispatchers.IO).launch { it.close() } }
    }
}
