package ch.snepilatch.app.viewmodel

import ch.snepilatch.app.R
import ch.snepilatch.app.logic.relay.RelayJam
import ch.snepilatch.app.logic.shared.AppMessages
import ch.snepilatch.app.logic.shared.JamHolder
import ch.snepilatch.app.logic.shared.LokiLogger
import ch.snepilatch.app.logic.shared.SessionHolder
import ch.snepilatch.app.logic.shared.launchWith
import ch.snepilatch.app.logic.shared.spfyId
import kotify.api.common.ShortLink
import kotify.session.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "DeepLink"

private fun PlaybackViewModel.sessionLaunch(op: String, block: suspend (Session) -> Unit) =
    launchWith(TAG, op, { SessionHolder.session }, block = block)

/**
 * Handle a deep link URI from open.spotify.com.
 * Supported paths: /track/{id}, /album/{id}, /playlist/{id}, /artist/{id}, /show/{id}, /episode/{id},
 * /socialsession/{token}, and a relay invite's /jam/{token}. A link opens its page and never plays:
 * a track opens its album, an episode its show, as there is no page for either alone.
 * A spotify.link short link is expanded first and then handled like the long one.
 */
fun PlaybackViewModel.handleDeepLink(uri: android.net.Uri) {
    if (ShortLink.isShortLink(uri.toString())) {
        sessionLaunch("expandShortLink") { sess ->
            val long = ShortLink.expand(sess, uri.toString())
            if (long == null) {
                LokiLogger.w(TAG, "Short link did not resolve: $uri")
                return@sessionLaunch
            }
            withContext(Dispatchers.Main) { handleDeepLink(android.net.Uri.parse(long)) }
        }
        return
    }
    val segments = uri.pathSegments ?: return
    if (segments.size < 2) return
    val type = segments[0]
    val id = segments[1]
    if (id.isBlank()) return

    LokiLogger.i(TAG, "Deep link: type=$type id=$id")

    when (type) {
        "track" -> DetailRoutes.openAlbumForTrack("spotify:track:$id")
        "show" -> DetailRoutes.openShow(id)
        "episode" -> sessionLaunch("openEpisodeLink") { sess ->
            val show = kotify.api.podcast.Podcast(sess).getEpisode(id)?.show?.uri
            if (show == null) {
                LokiLogger.w(TAG, "Episode $id has no show to open")
            } else {
                withContext(Dispatchers.Main) { DetailRoutes.openShow(spfyId(show)) }
            }
        }
        "album" -> DetailRoutes.openAlbum(id)
        "playlist" -> DetailRoutes.openPlaylist(id)
        "artist" -> DetailRoutes.openArtist(id)
        // Looked up only, as for a Spfy jam: the join sheet asks how to take part.
        "jam" -> sessionLaunch("previewRelayJam") {
            RelayJam.preview(id)?.let {
                LokiLogger.w(TAG, "Relay jam $id not opened: $it")
                AppMessages.show(R.string.jam_link_not_opened)
            }
        }
        "socialsession" -> sessionLaunch("joinJam") { sess ->
            // Looked up only; the join sheet asks how to take part before anything is joined.
            JamHolder.preview(sess, uri.toString())?.let {
                LokiLogger.w(TAG, "Jam link $id not opened: $it")
                AppMessages.show(R.string.jam_link_not_opened)
            }
        }
        else -> LokiLogger.i(TAG, "Unsupported deep link type: $type")
    }
}
