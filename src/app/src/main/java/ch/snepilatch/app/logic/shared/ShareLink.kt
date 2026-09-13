package ch.snepilatch.app.logic.shared

import android.content.Context
import android.content.Intent

/** The public open.spotify.com link of a spfy uri such as spotify:track:id. */
fun spfyLink(uri: String): String = "https://open.spotify.com/" + uri.removePrefix("spotify:").replace(':', '/')

/** Hands [link] to the system share chooser titled [chooserLabel]. */
fun shareLink(context: Context, link: String, chooserLabel: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, link)
    }
    context.startActivity(Intent.createChooser(intent, chooserLabel))
}

/** Shares the link of a spfy [uri]. */
fun shareSpfyUri(context: Context, uri: String, chooserLabel: String) = shareLink(context, spfyLink(uri), chooserLabel)
