package ch.snepilatch.app.logic.podcast

import kotify.api.podcast.Podcast
import kotify.session.Session
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

/**
 * Which episodes are in Your Episodes, read by every + so an episode row and the episode menu agree.
 * A page of episodes is looked up in one request, the way the web player fills its curation buttons.
 */
object YourEpisodes {
    private val _saved = MutableStateFlow<Set<String>>(emptySet())
    val saved: StateFlow<Set<String>> = _saved

    /** Learn which of [uris] are saved. Others keep what is known about them. */
    suspend fun refresh(session: Session, uris: List<String>) {
        if (uris.isEmpty()) return
        val saved = Podcast(session).savedEpisodes(uris)
        _saved.update { it - uris.toSet() + saved }
    }

    /** Save or remove [uri]. The + flips before the request lands and flips back if it fails. */
    suspend fun toggle(session: Session, uri: String) {
        val wasSaved = uri in _saved.value
        _saved.update { if (wasSaved) it - uri else it + uri }
        try {
            if (wasSaved) Podcast(session).removeEpisode(uri) else Podcast(session).saveEpisode(uri)
        } catch (e: Exception) {
            _saved.update { if (wasSaved) it + uri else it - uri }
            throw e
        }
    }
}
