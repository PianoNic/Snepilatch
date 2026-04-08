package ch.snepilatch.app.ui.screens

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.rememberScrollState
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
import ch.snepilatch.app.ui.components.SpotifyImage
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
            placeholder = { Text("Search", color = SpotifyLightGray.copy(alpha = 0.7f)) },
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
                val selectedFilter by searchVm.selectedFilter.collectAsState()
                CategorizedResults(
                    results = results,
                    vm = vm,
                    selectedFilter = selectedFilter,
                    onFilterChange = { searchVm.setFilter(it) }
                )
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

/**
 * One unified row representing any search result. Used for songs, albums,
 * playlists, podcasts, profiles — same template throughout, only the image
 * shape (circular for artists/users) and the right-side action vary.
 */
private data class UnifiedResult(
    val uri: String,
    val title: String,
    val subtitle: String,
    val imageUrl: String?,
    val circular: Boolean,
    val onClick: () -> Unit
)

private fun SearchTrack.toUnified(vm: SpotifyViewModel) = UnifiedResult(
    uri = uri,
    title = name,
    subtitle = "Song · ${artists.joinToString(", ") { it.name }}",
    imageUrl = album.coverArtUrl,
    circular = false,
    onClick = { vm.playTrack(uri) }
)

private fun SearchArtist.toUnified(vm: SpotifyViewModel) = UnifiedResult(
    uri = uri,
    title = name,
    subtitle = "Artist",
    imageUrl = avatarUrl,
    circular = true,
    onClick = { vm.openArtist(idFromUri(uri)) }
)

private fun SearchAlbum.toUnified(vm: SpotifyViewModel): UnifiedResult {
    val typeLabel = type.lowercase().replaceFirstChar { it.uppercase() }
    val artistName = artists.firstOrNull()?.name
    return UnifiedResult(
        uri = uri,
        title = name,
        subtitle = listOfNotNull(typeLabel.takeIf { it.isNotBlank() }, artistName).joinToString(" · "),
        imageUrl = coverArtUrl,
        circular = false,
        onClick = { vm.openAlbum(idFromUri(uri)) }
    )
}

private fun SearchPlaylist.toUnified(vm: SpotifyViewModel) = UnifiedResult(
    uri = uri,
    title = name,
    subtitle = listOfNotNull("Playlist", ownerName).joinToString(" · "),
    imageUrl = coverArtUrl,
    circular = false,
    onClick = { vm.openPlaylist(idFromUri(uri)) }
)

private fun SearchPodcast.toUnified() = UnifiedResult(
    uri = uri,
    title = name,
    subtitle = listOfNotNull("Podcast", publisher).joinToString(" · "),
    imageUrl = coverArtUrl,
    circular = false,
    onClick = { /* podcast detail not implemented yet */ }
)

private fun SearchUser.toUnified() = UnifiedResult(
    uri = uri,
    title = displayName,
    subtitle = "Profile",
    imageUrl = avatarUrl,
    circular = true,
    onClick = { /* user profile not implemented yet */ }
)

@Composable
private fun CategorizedResults(
    results: kotify.api.song.SearchResult?,
    vm: SpotifyViewModel,
    selectedFilter: SearchViewModel.SearchFilter,
    onFilterChange: (SearchViewModel.SearchFilter) -> Unit
) {
    if (results == null) return

    Column(Modifier.fillMaxSize()) {
        FilterChipRow(
            selected = selectedFilter,
            onSelect = onFilterChange,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        // Build the unified list based on the selected filter.
        val rows: List<UnifiedResult> = when (selectedFilter) {
            SearchViewModel.SearchFilter.ALL -> buildList {
                // Top result is the first artist (Spotify usually features an
                // artist as the top result for entity queries). Falls back to
                // the first available item if there's no artist.
                results.artists.items.forEach { add(it.toUnified(vm)) }
                results.tracks.items.forEach { add(it.toUnified(vm)) }
                results.albums.items.forEach { add(it.toUnified(vm)) }
                results.playlists.items.forEach { add(it.toUnified(vm)) }
                results.podcasts.items.forEach { add(it.toUnified()) }
                results.users.items.forEach { add(it.toUnified()) }
            }
            SearchViewModel.SearchFilter.ARTISTS -> results.artists.items.map { it.toUnified(vm) }
            SearchViewModel.SearchFilter.ALBUMS -> results.albums.items.map { it.toUnified(vm) }
            SearchViewModel.SearchFilter.SONGS -> results.tracks.items.map { it.toUnified(vm) }
            SearchViewModel.SearchFilter.PLAYLISTS -> results.playlists.items.map { it.toUnified(vm) }
            SearchViewModel.SearchFilter.PODCASTS -> results.podcasts.items.map { it.toUnified() }
            SearchViewModel.SearchFilter.PROFILES -> results.users.items.map { it.toUnified() }
        }

        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalBottomOverlayHeight.current.value + 16.dp)
        ) {
            items(rows, key = { it.uri }) { row ->
                ResultRow(
                    title = row.title,
                    subtitle = row.subtitle,
                    imageUrl = row.imageUrl,
                    circular = row.circular,
                    onClick = row.onClick
                )
            }
        }
    }
}

@Composable
private fun FilterChipRow(
    selected: SearchViewModel.SearchFilter,
    onSelect: (SearchViewModel.SearchFilter) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        SearchViewModel.SearchFilter.entries.forEach { f ->
            FilterChip(
                label = labelFor(f),
                isSelected = selected == f,
                onClick = { onSelect(f) }
            )
        }
    }
}

@Composable
private fun FilterChip(label: String, isSelected: Boolean, onClick: () -> Unit) {
    val bg = if (isSelected) Color(0xFF1DB954) else SpotifyLightGray.copy(alpha = 0.18f)
    val fg = if (isSelected) SpotifyBlack else SpotifyWhite
    Box(
        Modifier
            .clip(RoundedCornerShape(50))
            .background(bg)
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp)
    ) {
        Text(label, color = fg, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
    }
}

private fun labelFor(filter: SearchViewModel.SearchFilter): String = when (filter) {
    SearchViewModel.SearchFilter.ALL -> "All"
    SearchViewModel.SearchFilter.ARTISTS -> "Artists"
    SearchViewModel.SearchFilter.ALBUMS -> "Albums"
    SearchViewModel.SearchFilter.SONGS -> "Songs"
    SearchViewModel.SearchFilter.PLAYLISTS -> "Playlists"
    SearchViewModel.SearchFilter.PODCASTS -> "Podcasts"
    SearchViewModel.SearchFilter.PROFILES -> "Profiles"
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

private fun idFromUri(uri: String): String = uri.substringAfterLast(':')
