package ch.snepilatch.app.ui.screens

import ch.snepilatch.app.ui.theme.SpotifyWhite
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.IntOffset
import ch.snepilatch.app.ui.components.ShimmerBox
import ch.snepilatch.app.ui.components.SpotifyImage
import ch.snepilatch.app.ui.theme.SpotifyCardBg
import ch.snepilatch.app.ui.theme.SpotifyLightGray
import ch.snepilatch.app.viewmodel.SpotifyViewModel

// --- Home Screen ---

@Composable
fun HomeScreen(vm: SpotifyViewModel) {
    val homeData by vm.homeData.collectAsState()
    val isHomeLoading by vm.isHomeLoading.collectAsState()
    val account by vm.account.collectAsState()

    if (isHomeLoading && homeData == null) {
        HomeShimmer()
        return
    }

    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(top = 8.dp, bottom = LocalBottomOverlayHeight.current.value + 16.dp)
    ) {
        item {
            Text(
                homeData?.greeting ?: "Good evening",
                color = SpotifyWhite,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }

        // Quick-pick grid (first section as compact grid)
        val firstSection = homeData?.sections?.firstOrNull()
        if (firstSection != null && firstSection.items.isNotEmpty()) {
            item {
                QuickPickGrid(firstSection.items.take(8), vm)
            }
        }

        homeData?.sections?.drop(1)?.forEach { section ->
            if (section.items.isNotEmpty()) {
                item {
                    Text(
                        section.title,
                        color = SpotifyWhite,
                        fontSize = 20.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 10.dp)
                    )
                }
                item {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        itemsIndexed(section.items) { index, item ->
                            val alpha = remember { Animatable(0f) }
                            val offsetY = remember { Animatable(20f) }
                            LaunchedEffect(Unit) {
                                delay((index * 50L).coerceAtMost(250))
                                coroutineScope {
                                    launch { alpha.animateTo(1f, tween(250)) }
                                    launch { offsetY.animateTo(0f, tween(300)) }
                                }
                            }
                            HomeSectionCard(
                                item, vm,
                                modifier = Modifier
                                    .alpha(alpha.value)
                                    .offset { IntOffset(0, offsetY.value.toInt()) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QuickPickGrid(items: List<kotify.api.home.HomeSectionItem>, vm: SpotifyViewModel) {
    Column(
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.chunked(2).forEach { row ->
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                row.forEach { item ->
                    Card(
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp)
                            .clickable {
                                val id = item.uri.split(":").lastOrNull() ?: return@clickable
                                when (item.type) {
                                    "playlist" -> vm.openPlaylist(id)
                                    "album" -> vm.openAlbum(id)
                                    "artist" -> vm.openArtist(id)
                                    else -> vm.playTrack(item.uri)
                                }
                            },
                        shape = RoundedCornerShape(6.dp),
                        colors = CardDefaults.cardColors(containerColor = SpotifyCardBg)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            SpotifyImage(
                                url = item.imageUrl,
                                modifier = Modifier.size(48.dp),
                                shape = RoundedCornerShape(topStart = 6.dp, bottomStart = 6.dp)
                            )
                            Text(
                                item.name,
                                color = SpotifyWhite,
                                fontSize = 12.sp,
                                fontWeight = FontWeight.SemiBold,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(horizontal = 8.dp)
                            )
                        }
                    }
                }
                if (row.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}

@Composable
fun HomeShimmer() {
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        ShimmerBox(Modifier.width(180.dp).height(28.dp).clip(RoundedCornerShape(4.dp)))
        Spacer(Modifier.height(16.dp))
        repeat(3) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                repeat(2) {
                    ShimmerBox(
                        Modifier
                            .weight(1f)
                            .height(56.dp)
                            .clip(RoundedCornerShape(6.dp))
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        Spacer(Modifier.height(24.dp))
        ShimmerBox(Modifier.width(150.dp).height(22.dp).clip(RoundedCornerShape(4.dp)))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            repeat(3) {
                Column {
                    ShimmerBox(Modifier.size(140.dp).clip(RoundedCornerShape(8.dp)))
                    Spacer(Modifier.height(8.dp))
                    ShimmerBox(Modifier.width(100.dp).height(14.dp).clip(RoundedCornerShape(4.dp)))
                }
            }
        }
    }
}

@Composable
fun HomeSectionCard(item: kotify.api.home.HomeSectionItem, vm: SpotifyViewModel, modifier: Modifier = Modifier) {
    val isArtist = item.type == "artist"
    Column(
        modifier
            .width(140.dp)
            .clickable {
                val id = item.uri.split(":").lastOrNull() ?: return@clickable
                when (item.type) {
                    "playlist" -> vm.openPlaylist(id)
                    "album" -> vm.openAlbum(id)
                    "artist" -> vm.openArtist(id)
                    else -> vm.playTrack(item.uri)
                }
            },
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start
    ) {
        SpotifyImage(
            url = item.imageUrl,
            modifier = Modifier.size(140.dp),
            shape = if (isArtist) CircleShape else RoundedCornerShape(8.dp),
            icon = if (isArtist) Icons.Default.Person else Icons.Default.MusicNote
        )
        Spacer(Modifier.height(8.dp))
        Text(
            item.name,
            color = SpotifyWhite,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
            modifier = Modifier.fillMaxWidth()
        )
        val subtitle = item.artists?.joinToString(", ") ?: item.owner
        if (subtitle != null) {
            Text(
                subtitle,
                color = SpotifyLightGray,
                fontSize = 11.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}
