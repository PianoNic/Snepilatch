package ch.snepilatch.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.ui.components.SpotifyImage
import ch.snepilatch.app.ui.components.itemAppearModifier
import ch.snepilatch.app.ui.theme.*
import ch.snepilatch.app.viewmodel.SpotifyViewModel

@Composable
fun QueueScreen(vm: SpotifyViewModel) {
    val queue by vm.queue.collectAsState()
    val playback by vm.playback.collectAsState()

    Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = { vm.goBack() }) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back", tint = SpotifyWhite)
            }
            Text("Queue", color = SpotifyWhite, fontSize = 22.sp, fontWeight = FontWeight.Bold)
        }

        // Now playing
        playback.track?.let { track ->
            Text(
                "Now playing",
                color = SpotifyLightGray,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(SpotifyGray.copy(alpha = 0.5f))
                    .padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SpotifyImage(track.albumArt, Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
                Column(Modifier.padding(start = 12.dp).weight(1f)) {
                    Text(track.name, color = SpotifyWhite, fontSize = 15.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(track.artist, color = SpotifyLightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        // Next up
        if (queue.isNotEmpty()) {
            Text(
                "Next up",
                color = SpotifyLightGray,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        LazyColumn(Modifier.fillMaxSize(), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = LocalBottomOverlayHeight.current.value + 16.dp)) {
            itemsIndexed(queue) { index, track ->
                Box(itemAppearModifier(index)) {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { vm.skipToQueueIndex(index) }
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        SpotifyImage(track.albumArt, Modifier.size(48.dp).clip(RoundedCornerShape(4.dp)))
                        Column(Modifier.padding(start = 12.dp).weight(1f)) {
                            Text(track.name, color = SpotifyWhite, fontSize = 15.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text(track.artist, color = SpotifyLightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        if (queue.isEmpty() && playback.track == null) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("Queue is empty", color = SpotifyLightGray, fontSize = 16.sp)
            }
        }
    }
}
