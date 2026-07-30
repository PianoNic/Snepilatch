package ch.snepilatch.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import ch.snepilatch.app.ui.theme.*
import ch.snepilatch.app.viewmodel.PlaybackViewModel

/**
 * What downloading is doing right now: tracks in flight, where the files go, and how much space they
 * take. Browsing what has already been downloaded belongs in the library, grouped by album or
 * playlist, rather than here as a flat list of tracks.
 */
@Composable
fun DownloadsScreen(vm: PlaybackViewModel) {
    val inFlight by Downloads.inProgress.collectAsState()
    val downloadedUris by Downloads.downloaded.collectAsState()
    val folder by DownloadFolder.folder.collectAsState()
    val stored = remember(downloadedUris) { Downloads.all() }
    val totalMb = remember(stored) { (stored.sumOf { it.sizeBytes } / 1024 / 1024).toInt() }

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
                fontWeight = FontWeight.Bold
            )
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

        if (inFlight.isEmpty()) {
            Text(
                stringResource(R.string.downloads_idle),
                color = SpfyLightGray,
                fontSize = 14.sp,
                modifier = Modifier.padding(horizontal = 20.dp)
            )
            return@Column
        }

        LazyColumn(
            contentPadding = PaddingValues(bottom = LocalBottomOverlayHeight.current.value + 16.dp)
        ) {
            items(inFlight.toList(), key = { it }) { trackUri ->
                ListItem(
                    headlineContent = {
                        Text(trackUri.substringAfterLast(':'), color = SpfyWhite, maxLines = 1)
                    },
                    supportingContent = {
                        Text(stringResource(R.string.downloading), color = SpfyLightGray)
                    },
                    leadingContent = { Icon(Icons.Rounded.Downloading, null, tint = SpfyLightGray) },
                    trailingContent = {
                        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                    },
                    colors = ListItemDefaults.colors(containerColor = Color.Transparent)
                )
            }
        }
    }
}
