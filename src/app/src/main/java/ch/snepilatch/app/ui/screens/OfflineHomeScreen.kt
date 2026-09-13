package ch.snepilatch.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.R
import ch.snepilatch.app.logic.download.Downloads
import ch.snepilatch.app.logic.download.toTrackInfo
import ch.snepilatch.app.ui.shared.TrackRow
import ch.snepilatch.app.ui.theme.SnepilatchLightGray
import ch.snepilatch.app.ui.theme.SnepilatchWhite
import ch.snepilatch.app.viewmodel.PlaybackViewModel

@Composable
fun OfflineHomeScreen(vm: PlaybackViewModel) {
    val stored by Downloads.rows.collectAsState()
    val tracks = stored.map { it.toTrackInfo() }

    if (tracks.isEmpty()) {
        Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
            Text(
                stringResource(R.string.offline_no_downloads),
                color = SnepilatchLightGray,
                fontSize = 15.sp,
                textAlign = TextAlign.Center
            )
        }
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = LocalBottomOverlayHeight.current.value + 16.dp)
    ) {
        item {
            Text(
                stringResource(R.string.offline_while_youre_offline),
                color = SnepilatchWhite,
                fontSize = 22.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)
            )
        }
        items(tracks, key = { it.uri }) { track -> TrackRow(track, vm) }
    }
}
