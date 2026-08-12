package ch.snepilatch.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Downloading
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
    val job by Downloads.activeJob.collectAsState()
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
                Text(stringResource(R.string.downloads_summary, stored.size, totalMb), color = SpfyWhite)
            },
            leadingContent = { Icon(Icons.Rounded.CloudDone, null, tint = SpfyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        HorizontalDivider(color = SpfyLightGray.copy(alpha = 0.15f))
        Text(
            stringResource(R.string.downloads_active),
            color = SpfyLightGray,
            fontSize = 13.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp)
        )

        val active = job
        if (active == null) {
            Text(
                stringResource(R.string.downloads_idle),
                color = SpfyLightGray,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            return@Column
        }

        ActiveDownload(active)
    }
}

/**
 * What is downloading, named and with its artwork. The in-flight uri set cannot say more than
 * "something is downloading": it carries no title, so this read the base62 id off the uri. The job
 * knows the album or track the user asked for, how far through the list it is, and how far through
 * the current track.
 */
@Composable
private fun ActiveDownload(job: Downloads.ActiveJob) {
    // A re-encode keeps the recording that was just played; there is nothing to fetch, so it has no
    // percentage to report and spins rather than filling a ring stuck at zero.
    val reencode = job.type == Downloads.TYPE_REENCODE
    ListItem(
        headlineContent = { Text(job.name, color = SpfyWhite, maxLines = 1) },
        supportingContent = {
            Text(
                when {
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
            if (reencode) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
            } else {
                CircularProgressIndicator(
                    progress = { job.trackPercent.coerceIn(0, 100) / 100f },
                    strokeWidth = 2.dp,
                    modifier = Modifier.size(18.dp),
                )
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
