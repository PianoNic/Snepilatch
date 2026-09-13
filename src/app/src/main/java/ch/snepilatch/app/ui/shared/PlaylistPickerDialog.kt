package ch.snepilatch.app.ui.shared

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.R
import ch.snepilatch.app.data.LibraryItem
import ch.snepilatch.app.ui.theme.SnepilatchGray
import ch.snepilatch.app.ui.theme.SnepilatchLightGray
import ch.snepilatch.app.ui.theme.SnepilatchWhite

/** The add-to-playlist dialog: one row per playlist in [playlists], [onPick] with the chosen one. */
@Composable
fun PlaylistPickerDialog(playlists: List<LibraryItem>, onPick: (LibraryItem) -> Unit, onDismiss: () -> Unit) {
    TightAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.add_to_playlist), color = SnepilatchWhite) },
        containerColor = SnepilatchGray,
        text = {
            // TightAlertDialog wraps `text` in a height-bounded verticalScroll Box, which gives its
            // child infinite max height; a LazyColumn there throws "infinity maximum height". A plain
            // Column, with the dialog providing the scrolling.
            Column(Modifier.fillMaxWidth()) {
                playlists.forEach { playlist ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(playlist) }
                            .padding(vertical = 10.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SpfyImage(url = playlist.imageUrl, modifier = Modifier.size(44.dp), shape = RoundedCornerShape(4.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(playlist.name, color = SnepilatchWhite, fontSize = 14.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            playlist.owner?.let { Text(it, color = SnepilatchLightGray, fontSize = 12.sp, maxLines = 1) }
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = SnepilatchLightGray)
            }
        }
    )
}
