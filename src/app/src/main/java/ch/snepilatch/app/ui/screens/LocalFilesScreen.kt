package ch.snepilatch.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.R
import ch.snepilatch.app.data.Screen
import ch.snepilatch.app.logic.download.LocalFiles
import ch.snepilatch.app.logic.download.LocalTrack
import ch.snepilatch.app.ui.shared.SpfyImage
import ch.snepilatch.app.ui.theme.SnepilatchLightGray
import ch.snepilatch.app.ui.theme.SnepilatchWhite
import ch.snepilatch.app.viewmodel.PlaybackViewModel

/** The files of the folders picked in the settings (#1070), with what their own tags say. */
@Composable
fun LocalFilesScreen(vm: PlaybackViewModel) {
    val tracks by LocalFiles.tracks.collectAsState()
    val scanning by LocalFiles.scanning.collectAsState()
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { vm.goBack() }) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = SnepilatchWhite)
            }
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.local_files), color = SnepilatchWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                Text(
                    if (scanning) {
                        stringResource(R.string.local_files_scanning)
                    } else {
                        pluralStringResource(R.plurals.local_files_count, tracks.size, tracks.size)
                    },
                    color = SnepilatchLightGray, fontSize = 13.sp,
                )
            }
            if (tracks.isNotEmpty()) {
                IconButton(onClick = { vm.playTrack(tracks.first().toTrackInfo(), LocalFiles.LIBRARY_URI) }) {
                    Icon(Icons.Rounded.PlayArrow, stringResource(R.string.local_files), tint = SnepilatchWhite)
                }
            }
        }
        if (tracks.isEmpty() && !scanning) {
            Empty(vm)
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomOverlayHeight.current.value + 16.dp)) {
            items(tracks, key = { it.documentUri }) { track -> Row(track, vm) }
        }
    }
}

@Composable
private fun Empty(vm: PlaybackViewModel) {
    Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.local_files_empty_header), color = SnepilatchWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.local_files_empty_description), color = SnepilatchLightGray, textAlign = TextAlign.Center)
            TextButton(onClick = { vm.navigateTo(Screen.ACCOUNT) }) {
                Text(stringResource(R.string.local_files_empty_button), color = SnepilatchWhite)
            }
        }
    }
}

// Not TrackRow: its menu offers liking, sharing and the album page, none of which a file has.
@Composable
private fun Row(track: LocalTrack, vm: PlaybackViewModel) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { vm.playTrack(track.toTrackInfo(), LocalFiles.LIBRARY_URI) }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpfyImage(url = track.coverPath, modifier = Modifier.size(48.dp), shape = RoundedCornerShape(4.dp), icon = Icons.Rounded.Folder)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(track.title, color = SnepilatchWhite, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val line = listOf(track.artist, track.album).filter { it.isNotBlank() }.joinToString(" · ")
            if (line.isNotEmpty()) Text(line, color = SnepilatchLightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
