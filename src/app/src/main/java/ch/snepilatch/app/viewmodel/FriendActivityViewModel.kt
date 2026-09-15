package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.logic.shared.SessionViewModel
import kotify.api.user.FriendActivity
import kotify.api.user.ListeningActivity
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * What the people you follow are listening to (#843): the web player's buddy feed, read through
 * Kotify. [feed] is playing-first then newest, already resolved to names and images.
 */
class FriendActivityViewModel : SessionViewModel("FriendActivity") {

    val feed = MutableStateFlow<List<FriendActivity>>(emptyList())
    val loading = MutableStateFlow(false)

    // ponytail: pull-only; the web player also gets live pushes over the dealer socket. Add when
    // Kotify subscribes to hm://listening-activity/.
    fun load() = launchWithSessionLoading("load", loading) { sess -> feed.value = ListeningActivity(sess).getFeed() }
}
