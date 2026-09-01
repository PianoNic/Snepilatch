package ch.snepilatch.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Downloading
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.R
import ch.snepilatch.app.download.DownloadFolder
import ch.snepilatch.app.download.DownloadQueue
import ch.snepilatch.app.download.Downloads
import ch.snepilatch.app.ui.components.SpfyImage
import ch.snepilatch.app.ui.theme.*
import ch.snepilatch.app.viewmodel.PlaybackViewModel

/**
 * What downloading is doing right now: tracks in flight, where the files go, and how much space they
 * take. Browsing what has already been downloaded belongs in the library, grouped by album or
 * playlist, rather than here as a flat list of tracks.
 */
@Composable
fun DownloadsScreen(vm: PlaybackViewModel) {
    val queue by DownloadQueue.queue.collectAsState()
    val folder by DownloadFolder.folder.collectAsState()
    // Downloads.rows, not a query: a remember{} body runs on the composition thread, and every
    // finished track re-emits, so reading the table here put a full SELECT on the UI thread per track.
    val stored by Downloads.rows.collectAsState()
    val totalMb = remember(stored) { (stored.sumOf { it.sizeBytes } / 1024 / 1024).toInt() }
    var confirmClearAll by remember { mutableStateOf(false) }

    if (confirmClearAll) {
        ClearAllDialog(
            onConfirm = {
                stored.forEach { vm.removeDownload(it.trackUri) }
                confirmClearAll = false
            },
            onDismiss = { confirmClearAll = false },
        )
    }

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
            if (stored.isNotEmpty()) {
                TextButton(onClick = { confirmClearAll = true }) {
                    Text(stringResource(R.string.remove_all), color = SpfyLightGray)
                }
            }
        }

        StorageSummary(folder = folder, count = stored.size, totalMb = totalMb)

        HorizontalDivider(color = SpfyLightGray.copy(alpha = 0.15f))
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                stringResource(R.string.downloads_active),
                color = SpfyLightGray,
                fontSize = 13.sp,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f).padding(vertical = 12.dp)
            )
            if (queue.any { !it.running }) {
                TextButton(onClick = { DownloadQueue.clearFinished() }) {
                    Text(stringResource(R.string.downloads_clear_finished), color = SpfyLightGray)
                }
            }
        }

        if (queue.isEmpty()) {
            Text(
                stringResource(R.string.downloads_idle),
                color = SpfyLightGray,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            return@Column
        }

        LazyColumn {
            items(queue, key = { it.id }) { entry ->
                QueuedDownload(entry, onCancel = { vm.cancelDownload(entry.id) })
            }
        }
    }
}

/** Where the files go and how much room they take. */
@Composable
private fun StorageSummary(folder: android.net.Uri?, count: Int, totalMb: Int) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.download_folder), color = SpfyWhite) },
        supportingContent = {
            Text(
                folder?.let { readableFolder(it) } ?: stringResource(R.string.download_folder_none),
                color = SpfyLightGray
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
    ListItem(
        headlineContent = {
            Text(stringResource(R.string.downloads_summary, count, totalMb), color = SpfyWhite)
        },
        leadingContent = { Icon(Icons.Rounded.CloudDone, null, tint = SpfyLightGray) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun QueuedDownload(job: DownloadQueue.QueueEntry, onCancel: () -> Unit) {
    // A re-encode keeps the recording that was just played; there is nothing to fetch, so it has no
    // percentage to report and spins rather than filling a ring stuck at zero.
    val reencode = job.type == DownloadQueue.TYPE_REENCODE
    ListItem(
        headlineContent = { Text(job.name, color = SpfyWhite, maxLines = 1) },
        supportingContent = {
            Text(
                when {
                    job.state == DownloadQueue.QueueEntry.State.Done ->
                        stringResource(R.string.downloads_finished)
                    job.state == DownloadQueue.QueueEntry.State.Failed ->
                        stringResource(R.string.downloads_failed)
                    job.state == DownloadQueue.QueueEntry.State.Cancelled ->
                        stringResource(R.string.downloads_cancelled)
                    job.paused -> stringResource(R.string.downloads_paused, job.done, job.total)
                    reencode -> stringResource(R.string.saving_listened)
                    job.total > 1 -> stringResource(R.string.downloading_count, job.done, job.total)
                    else -> stringResource(R.string.downloading)
                },
                color = SpfyLightGray
            )
        },
        leadingContent = {
            SpfyImage(
                url = job.imageUrl,
                modifier = Modifier.size(48.dp),
                shape = RoundedCornerShape(4.dp),
                icon = Icons.Rounded.Downloading,
            )
        },
        trailingContent = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when {
                    !job.running || job.paused -> Unit
                    reencode -> CircularProgressIndicator(
                        strokeWidth = 2.dp,
                        modifier = Modifier.padding(end = 12.dp).size(18.dp),
                    )
                    else -> CircularProgressIndicator(
                        progress = { job.trackPercent.coerceIn(0, 100) / 100f },
                        strokeWidth = 2.dp,
                        modifier = Modifier.padding(end = 12.dp).size(18.dp),
                    )
                }
                // Only a batch pauses: it takes effect between tracks, so a single track has nothing
                // to hold back.
                if (job.running && job.total > 1) {
                    IconButton(onClick = { if (job.paused) DownloadQueue.resume(job.id) else DownloadQueue.pause(job.id) }) {
                        Icon(
                            if (job.paused) Icons.Rounded.PlayArrow else Icons.Rounded.Pause,
                            stringResource(if (job.paused) R.string.resume else R.string.pause),
                            tint = SpfyLightGray,
                        )
                    }
                }
                if (job.running) {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.cancel), tint = SpfyLightGray)
                    }
                }
            }
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}

@Composable
private fun ClearAllDialog(onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SpfyGray,
        title = { Text(stringResource(R.string.remove_all), color = SpfyWhite) },
        text = { Text(stringResource(R.string.remove_all_confirm), color = SpfyLightGray) },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(stringResource(R.string.remove_all), color = SpfyWhite)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = SpfyLightGray)
            }
        }
    )
}
