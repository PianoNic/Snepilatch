package ch.snepilatch.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.North
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.ui.components.SpotifyImage
import ch.snepilatch.app.ui.components.TrackRow
import ch.snepilatch.app.ui.theme.SpotifyBlack
import ch.snepilatch.app.ui.theme.SpotifyLightGray
import ch.snepilatch.app.ui.theme.SpotifyWhite
import ch.snepilatch.app.viewmodel.SearchViewModel
import ch.snepilatch.app.viewmodel.SpotifyViewModel
import kotify.api.song.SearchAlbum
import kotify.api.song.SearchArtist
import kotify.api.song.SearchPlaylist
import kotify.api.song.SearchPodcast
import kotify.api.song.SearchSuggestion
import kotify.api.song.SearchTrack
import kotify.api.song.SearchUser

private data class BrowseCategory(val name: String, val color: Color)
private val browseCategories = listOf(
    BrowseCategory("Pop", Color(0xFF8C67AB)),
    BrowseCategory("Hip-Hop", Color(0xFFBA5D07)),
    BrowseCategory("Rock", Color(0xFFE61E32)),
    BrowseCategory("R&B", Color(0xFF503750)),
    BrowseCategory("Latin", Color(0xFF148A08)),
    BrowseCategory("EDM", Color(0xFF0D73EC)),
    BrowseCategory("Indie", Color(0xFF8D67AB)),
    BrowseCategory("Chill", Color(0xFF1E3264)),
    BrowseCategory("Workout", Color(0xFF777777)),
    BrowseCategory("Party", Color(0xFFAF2896)),
    BrowseCategory("Focus", Color(0xFF509BF5)),
    BrowseCategory("Jazz", Color(0xFFF49B23)),
    BrowseCategory("Classical", Color(0xFF477D95)),
    BrowseCategory("Country", Color(0xFFDC8F2C)),
    BrowseCategory("Metal", Color(0xFF1E1E1E)),
    BrowseCategory("Acoustic", Color(0xFF6B9E3C)),
    BrowseCategory("K-Pop", Color(0xFFE13300)),
    BrowseCategory("Anime", Color(0xFF7358FF)),
)

@Composable
fun SearchScreen(vm: SpotifyViewModel, searchVm: SearchViewModel = viewModel()) {
    val query by searchVm.query.collectAsState()
    val submittedQuery by searchVm.submittedQuery.collectAsState()
    val suggestions by searchVm.suggestions.collectAsState()
    val results by searchVm.results.collectAsState()
    val isSearching by searchVm.isSearching.collectAsState()

    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    Column(Modifier.fillMaxSize().padding(top = 12.dp)) {
        Text(
            "Search",
            color = SpotifyWhite,
            fontSize = 26.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        TextField(
            value = query,
            onValueChange = { searchVm.updateQuery(it) },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(8.dp))
                .focusRequester(focusRequester),
            placeholder = { Text("Songs, artists, albums, podcasts...", color = SpotifyLightGray.copy(alpha = 0.7f)) },
            leadingIcon = { Icon(Icons.Default.Search, null, tint = SpotifyBlack) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { searchVm.updateQuery("") }) {
                        Icon(Icons.Default.Close, "Clear", tint = SpotifyBlack)
                    }
                }
            },
            colors = TextFieldDefaults.colors(
                focusedTextColor = SpotifyBlack,
                unfocusedTextColor = SpotifyBlack,
                cursorColor = SpotifyBlack,
                focusedContainerColor = SpotifyWhite,
                unfocusedContainerColor = SpotifyWhite,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent
            ),
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            keyboardActions = KeyboardActions(onSearch = {
                searchVm.submitQuery(query)
                focusManager.clearFocus()
            })
        )

        Spacer(Modifier.height(8.dp))

        when {
            // STATE 1: idle — show browse categories
            query.isEmpty() -> BrowseCategoriesGrid(onCategoryTap = { searchVm.submitQuery(it) })

            // STATE 2: typing — show suggestions
            submittedQuery.isEmpty() -> SuggestionsList(
                suggestions = suggestions,
                onPick = {
                    searchVm.submitQuery(it.text)
                    focusManager.clearFocus()
                }
            )

            // STATE 3: submitted — show categorized results
            else -> {
                AnimatedVisibility(visible = isSearching, enter = fadeIn(), exit = fadeOut()) {
                    Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            color = SpotifyWhite,
                            modifier = Modifier.size(24.dp),
                            strokeWidth = 2.dp
                        )
                    }
                }
                CategorizedResults(results, vm)
            }
        }
    }
}

@Composable
private fun BrowseCategoriesGrid(onCategoryTap: (String) -> Unit) {
    Column {
        Text(
            "Browse All",
            color = SpotifyWhite,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(
                start = 16.dp, end = 16.dp,
                top = 4.dp, bottom = LocalBottomOverlayHeight.current.value + 16.dp
            ),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            modifier = Modifier.fillMaxSize()
        ) {
            items(browseCategories.size) { i ->
                val cat = browseCategories[i]
                Box(
                    Modifier
                        .fillMaxWidth()
                        .height(90.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(cat.color)
                        .clickable { onCategoryTap(cat.name) }
                        .padding(14.dp),
                    contentAlignment = Alignment.BottomStart
                ) {
                    Text(cat.name, color = SpotifyWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun SuggestionsList(
    suggestions: List<SearchSuggestion>,
    onPick: (SearchSuggestion) -> Unit
) {
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = LocalBottomOverlayHeight.current.value + 16.dp)
    ) {
        items(suggestions, key = { it.uri }) { sug ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable { onPick(sug) }
                    .padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    Icons.Default.Search,
                    null,
                    tint = SpotifyLightGray,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(16.dp))
                Text(sug.text, color = SpotifyWhite, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Default.North,
                    null,
                    tint = SpotifyLightGray.copy(alpha = 0.6f),
                    modifier = Modifier.size(16.dp)
                )
            }
        }
    }
}

@Composable
private fun CategorizedResults(
    results: kotify.api.song.SearchResult?,
    vm: SpotifyViewModel
) {
    if (results == null) return
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = LocalBottomOverlayHeight.current.value + 16.dp)
    ) {
        if (results.tracks.items.isNotEmpty()) {
            item { SectionHeader("Songs") }
            itemsIndexed(results.tracks.items) { _, t ->
                TrackRow(t.toUiTrackInfo(), vm)
            }
        }
        if (results.artists.items.isNotEmpty()) {
            item { SectionHeader("Artists") }
            items(results.artists.items, key = { it.uri }) {
                ResultRow(
                    title = it.name,
                    subtitle = "Artist",
                    imageUrl = it.avatarUrl,
                    circular = true,
                    onClick = { vm.openArtist(idFromUri(it.uri)) }
                )
            }
        }
        if (results.albums.items.isNotEmpty()) {
            item { SectionHeader("Albums") }
            items(results.albums.items, key = { it.uri }) {
                val subtitle = listOfNotNull(
                    it.type.lowercase().replaceFirstChar { c -> c.uppercase() }.takeIf { s -> s.isNotBlank() },
                    it.releaseYear?.toString(),
                    it.artists.firstOrNull()?.name
                ).joinToString(" • ")
                ResultRow(
                    title = it.name,
                    subtitle = subtitle,
                    imageUrl = it.coverArtUrl,
                    onClick = { vm.openAlbum(idFromUri(it.uri)) }
                )
            }
        }
        if (results.playlists.items.isNotEmpty()) {
            item { SectionHeader("Playlists") }
            items(results.playlists.items, key = { it.uri }) {
                ResultRow(
                    title = it.name,
                    subtitle = listOfNotNull("Playlist", it.ownerName).joinToString(" • "),
                    imageUrl = it.coverArtUrl,
                    onClick = { vm.openPlaylist(idFromUri(it.uri)) }
                )
            }
        }
        if (results.podcasts.items.isNotEmpty()) {
            item { SectionHeader("Podcasts & Shows") }
            items(results.podcasts.items, key = { it.uri }) {
                ResultRow(
                    title = it.name,
                    subtitle = listOfNotNull("Podcast", it.publisher).joinToString(" • "),
                    imageUrl = it.coverArtUrl,
                    onClick = { /* podcast detail not implemented yet */ }
                )
            }
        }
        if (results.users.items.isNotEmpty()) {
            item { SectionHeader("Profiles") }
            items(results.users.items, key = { it.uri }) {
                ResultRow(
                    title = it.displayName,
                    subtitle = "Profile",
                    imageUrl = it.avatarUrl,
                    circular = true,
                    onClick = { /* user profile not implemented yet */ }
                )
            }
        }
    }
}

@Composable
private fun SectionHeader(label: String) {
    Text(
        label,
        color = SpotifyWhite,
        fontSize = 18.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp)
    )
}

@Composable
private fun ResultRow(
    title: String,
    subtitle: String,
    imageUrl: String?,
    circular: Boolean = false,
    onClick: () -> Unit
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        SpotifyImage(
            url = imageUrl,
            modifier = Modifier.size(56.dp),
            shape = if (circular) CircleShape else RoundedCornerShape(6.dp)
        )
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                title,
                color = SpotifyWhite,
                fontSize = 15.sp,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (subtitle.isNotBlank()) {
                Text(
                    subtitle,
                    color = SpotifyLightGray,
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

private fun SearchTrack.toUiTrackInfo(): TrackInfo = TrackInfo(
    uri = uri,
    name = name,
    artist = artists.joinToString(", ") { it.name },
    albumArt = album.coverArtUrl,
    durationMs = durationMs,
    albumName = album.name
)

private fun idFromUri(uri: String): String = uri.substringAfterLast(':')
