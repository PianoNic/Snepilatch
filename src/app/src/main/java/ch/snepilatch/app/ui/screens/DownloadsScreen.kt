package ch.snepilatch.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ch.snepilatch.app.R
import ch.snepilatch.app.download.DownloadedTrack
import ch.snepilatch.app.download.Downloads
import ch.snepilatch.app.ui.theme.*
import ch.snepilatch.app.viewmodel.PlaybackViewModel

/** Lists what has been downloaded and lets the user delete individual tracks or the lot. */
@Composable
fun DownloadsScreen(vm: PlaybackViewModel) {
    val downloadedUris by Downloads.downloaded.collectAsState()
    // downloadedUris changes whenever a row is added or removed, which is the cue to re-read.
    val tracks by remember(downloadedUris) { mutableStateOf(Downloads.all()) }
    var confirmClearAll by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.goBack() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = SpfyWhite)
            }
            Text(
                stringResource(R.string.downloads),
                color = SpfyWhite,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f)
            )
            if (tracks.isNotEmpty()) {
                TextButton(onClick = { confirmClearAll = true }) {
                    Text(stringResource(R.string.remove_all), color = SpfyLightGray)
                }
            }
        }

        Text(
            stringResource(R.string.downloads_summary, tracks.size, totalMegabytes(tracks)),
            color = SpfyLightGray,
            fontSize = 13.sp,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 4.dp)
        )

        if (tracks.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(stringResource(R.string.downloads_empty), color = SpfyLightGray, fontSize = 14.sp)
            }
            return@Column
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalBottomOverlayHeight.current.value + 16.dp)
        ) {
            items(tracks, key = { it.trackUri }) { track -> DownloadRow(track, vm) }
        }
    }

    if (confirmClearAll) {
        TightAlertDialogHost(
            onDismiss = { confirmClearAll = false },
            onConfirm = {
                tracks.forEach { vm.removeDownload(it.trackUri) }
                confirmClearAll = false
            }
        )
    }
}

@Composable
private fun DownloadRow(track: DownloadedTrack, vm: PlaybackViewModel) {
    ListItem(
        headlineContent = { Text(track.title, color = SpfyWhite, maxLines = 1) },
        supportingContent = {
            Text(
                "${track.artist} · ${track.sizeBytes / 1024 / 1024} MB · ${track.provider ?: track.source}",
                color = SpfyLightGray,
                maxLines = 1
            )
        },
        leadingContent = {
            if (track.coverUrl != null) {
                AsyncImage(
                    model = track.coverUrl,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.size(48.dp).clip(RoundedCornerShape(4.dp))
                )
            } else {
                Icon(Icons.Rounded.MusicNote, null, tint = SpfyLightGray)
            }
        },
        trailingContent = {
            IconButton(onClick = { vm.removeDownload(track.trackUri) }) {
                Icon(Icons.Rounded.Delete, stringResource(R.string.remove_download), tint = SpfyLightGray)
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { vm.playTrack(track.trackUri) }
    )
}

@Composable
private fun TightAlertDialogHost(onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SpfyGray,
        title = { Text(stringResource(R.string.remove_all), color = SpfyWhite) },
        text = { Text(stringResource(R.string.remove_all_confirm), color = SpfyLightGray) },
        confirmButton = {
            TextButton(onClick = onConfirm) { Text(stringResource(R.string.remove_all), color = SpfyWhite) }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = SpfyLightGray) }
        }
    )
}

private fun totalMegabytes(tracks: List<DownloadedTrack>): Int =
    (tracks.sumOf { it.sizeBytes } / 1024 / 1024).toInt()
