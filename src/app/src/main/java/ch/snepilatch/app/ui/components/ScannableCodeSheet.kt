package ch.snepilatch.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.QrCode2
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import ch.snepilatch.app.R
import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.ui.theme.SnepilatchWhite

/**
 * The scannable code for [track], drawn the way the official app shows it: a black full screen
 * with a close button, the cover as a square, and the code strip flush under it in [accent] with
 * white bars and logo.
 *
 * The strip is fetched from the scannables image service, which is what the official apps use as
 * well. Its bars encode a media reference that only the server assigns to a uri, so the code cannot
 * be drawn locally; the colours are ours to pick through the url.
 */
@Composable
fun ScannableCodeSheet(track: TrackInfo, accent: Color, onDismiss: () -> Unit) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .clickable(onClick = onDismiss),
        ) {
            IconButton(onClick = onDismiss, Modifier.statusBarsPadding().padding(8.dp).size(48.dp)) {
                Icon(Icons.Rounded.Close, stringResource(R.string.close), tint = SnepilatchWhite, modifier = Modifier.size(28.dp))
            }
            Column(Modifier.align(Alignment.Center).fillMaxWidth(CARD_WIDTH_FRACTION)) {
                SpfyImage(track.albumArt, Modifier.fillMaxWidth().aspectRatio(1f), shape = RectangleShape)
                SpfyImage(
                    scannableCodeUrl(track.uri, accent),
                    Modifier.fillMaxWidth().aspectRatio(CODE_ASPECT),
                    shape = RectangleShape,
                    icon = Icons.Rounded.QrCode2,
                )
            }
        }
    }
}

/** The strip in [background] with white bars, at the service's largest size. */
internal fun scannableCodeUrl(uri: String, background: Color): String {
    val hex = "%06X".format(background.toArgb() and 0xFFFFFF)
    return "https://scannables.scdn.co/uri/plain/png/$hex/white/$CODE_WIDTH/$uri"
}

private const val CARD_WIDTH_FRACTION = 0.72f
private const val CODE_ASPECT = 4f
private const val CODE_WIDTH = 640
