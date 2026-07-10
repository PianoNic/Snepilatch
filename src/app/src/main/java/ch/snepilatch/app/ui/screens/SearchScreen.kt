@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ch.snepilatch.app.ui.screens

import android.content.Context
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.North
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LoadingIndicator
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.snepilatch.app.R
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
import kotify.api.song.SearchTopResult
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
            stringResource(R.string.search_title),
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
            placeholder = { Text(stringResource(R.string.search_field_placeholder), color = SpotifyLightGray.copy(alpha = 0.7f)) },
            leadingIcon = { Icon(Icons.Rounded.Search, null, tint = SpotifyBlack) },
            trailingIcon = {
                if (query.isNotEmpty()) {
                    IconButton(onClick = { searchVm.updateQuery("") }) {
                        Icon(Icons.Rounded.Close, stringResource(R.string.search_clear), tint = SpotifyBlack)
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
                        LoadingIndicator(
                            color = SpotifyWhite,
                            modifier = Modifier.size(24.dp)
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
            stringResource(R.string.search_browse_all),
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
                    Icons.Rounded.Search,
                    null,
                    tint = SpotifyLightGray,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(Modifier.width(16.dp))
                Text(sug.text, color = SpotifyWhite, fontSize = 15.sp, modifier = Modifier.weight(1f))
                Icon(
                    Icons.Rounded.North,
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

private fun SearchTrack.toUnified(vm: SpotifyViewModel, ctx: Context) = UnifiedResult(
    uri = uri,
    title = name,
    subtitle = ctx.getString(R.string.search_subtitle_song, artists.joinToString(", ") { it.name }),
    imageUrl = album.coverArtUrl,
    circular = false,
    onClick = { vm.playTrack(uri) }
)

private fun SearchArtist.toUnified(vm: SpotifyViewModel, ctx: Context) = UnifiedResult(
    uri = uri,
    title = name,
    subtitle = ctx.getString(R.string.search_subtitle_artist),
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

private fun SearchPlaylist.toUnified(vm: SpotifyViewModel, ctx: Context) = UnifiedResult(
    uri = uri,
    title = name,
    subtitle = listOfNotNull(ctx.getString(R.string.search_subtitle_playlist), ownerName).joinToString(" · "),
    imageUrl = coverArtUrl,
    circular = false,
    onClick = { vm.openPlaylist(idFromUri(uri)) }
)

private fun SearchPodcast.toUnified(vm: SpotifyViewModel, ctx: Context) = UnifiedResult(
    uri = uri,
    title = name,
    subtitle = listOfNotNull(ctx.getString(R.string.search_subtitle_podcast), publisher).joinToString(" · "),
    imageUrl = coverArtUrl,
    circular = false,
    // Carry publisher + cover art into the show screen — the queryPodcastEpisodes payload lacks them.
    onClick = { vm.openShow(idFromUri(uri), publisher, coverArtUrl) }
)

private fun SearchUser.toUnified(ctx: Context) = UnifiedResult(
    uri = uri,
    title = displayName,
    subtitle = ctx.getString(R.string.search_subtitle_profile),
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
    val ctx = LocalContext.current

    // Jump back to the top whenever the filter changes (e.g. tapping "Show all").
    val listState = rememberLazyListState()
    LaunchedEffect(selectedFilter) { listState.scrollToItem(0) }

    Column(Modifier.fillMaxSize()) {
        FilterChipRow(
            selected = selectedFilter,
            onSelect = onFilterChange,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
        )

        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = LocalBottomOverlayHeight.current.value + 16.dp)
        ) {
            if (selectedFilter == SearchViewModel.SearchFilter.ALL) {
                // Relevance layout (web-player style): the Top Result hero, then each
                // section in Spotify's per-query chipOrder, capped with a "Show all".
                results.topResult?.let { top ->
                    val unified = top.toUnified(vm, ctx)
                    item(key = "top") { TopResultCard(unified) }
                }
                results.chipOrder.ifEmpty { DEFAULT_CHIP_ORDER }.forEach { type ->
                    val (filter, rows) = sectionFor(type, results, vm, ctx) ?: return@forEach
                    if (rows.isEmpty()) return@forEach
                    item(key = "h_$type") {
                        SectionHeader(stringResource(labelFor(filter))) { onFilterChange(filter) }
                    }
                    items(rows.take(SECTION_PREVIEW), key = { "${type}_${it.uri}" }) { row ->
                        ResultRow(row.title, row.subtitle, row.imageUrl, row.circular, row.onClick)
                    }
                }
            } else {
                val rows = singleFilterRows(results, vm, ctx, selectedFilter)
                items(rows, key = { it.uri }) { row ->
                    ResultRow(row.title, row.subtitle, row.imageUrl, row.circular, row.onClick)
                }
            }
        }
    }
}

private const val SECTION_PREVIEW = 4
private val DEFAULT_CHIP_ORDER = listOf("TRACKS", "ARTISTS", "ALBUMS", "PLAYLISTS", "PODCASTS", "USERS")

/** Maps a chipOrder type name to its filter chip + the section's rows, or null if unsupported. */
private fun sectionFor(
    type: String,
    results: kotify.api.song.SearchResult,
    vm: SpotifyViewModel,
    ctx: Context
): Pair<SearchViewModel.SearchFilter, List<UnifiedResult>>? = when (type) {
    "TRACKS" -> SearchViewModel.SearchFilter.SONGS to results.tracks.items.map { it.toUnified(vm, ctx) }
    "ARTISTS" -> SearchViewModel.SearchFilter.ARTISTS to results.artists.items.map { it.toUnified(vm, ctx) }
    "ALBUMS" -> SearchViewModel.SearchFilter.ALBUMS to results.albums.items.map { it.toUnified(vm) }
    "PLAYLISTS" -> SearchViewModel.SearchFilter.PLAYLISTS to results.playlists.items.map { it.toUnified(vm, ctx) }
    "PODCASTS" -> SearchViewModel.SearchFilter.PODCASTS to results.podcasts.items.map { it.toUnified(vm, ctx) }
    "USERS" -> SearchViewModel.SearchFilter.PROFILES to results.users.items.map { it.toUnified(ctx) }
    else -> null
}

private fun singleFilterRows(
    results: kotify.api.song.SearchResult,
    vm: SpotifyViewModel,
    ctx: Context,
    filter: SearchViewModel.SearchFilter
): List<UnifiedResult> = when (filter) {
    SearchViewModel.SearchFilter.ARTISTS -> results.artists.items.map { it.toUnified(vm, ctx) }
    SearchViewModel.SearchFilter.ALBUMS -> results.albums.items.map { it.toUnified(vm) }
    SearchViewModel.SearchFilter.SONGS -> results.tracks.items.map { it.toUnified(vm, ctx) }
    SearchViewModel.SearchFilter.PLAYLISTS -> results.playlists.items.map { it.toUnified(vm, ctx) }
    SearchViewModel.SearchFilter.PODCASTS -> results.podcasts.items.map { it.toUnified(vm, ctx) }
    SearchViewModel.SearchFilter.PROFILES -> results.users.items.map { it.toUnified(ctx) }
    SearchViewModel.SearchFilter.ALL -> emptyList()
}

private fun SearchTopResult.toUnified(vm: SpotifyViewModel, ctx: Context): UnifiedResult = when (this) {
    is SearchTopResult.Track -> track.toUnified(vm, ctx)
    is SearchTopResult.Artist -> artist.toUnified(vm, ctx)
    is SearchTopResult.Album -> album.toUnified(vm)
    is SearchTopResult.Playlist -> playlist.toUnified(vm, ctx)
    is SearchTopResult.Podcast -> podcast.toUnified(vm, ctx)
    is SearchTopResult.User -> user.toUnified(ctx)
}

@Composable
private fun TopResultCard(r: UnifiedResult) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(
            stringResource(R.string.search_top_result),
            color = SpotifyWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(bottom = 8.dp)
        )
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .background(SpotifyLightGray.copy(alpha = 0.10f))
                .clickable { r.onClick() }
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            SpotifyImage(
                url = r.imageUrl,
                modifier = Modifier.size(80.dp),
                shape = if (r.circular) CircleShape else RoundedCornerShape(6.dp)
            )
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    r.title, color = SpotifyWhite, fontSize = 20.sp, fontWeight = FontWeight.Bold,
                    maxLines = 2, overflow = TextOverflow.Ellipsis
                )
                if (r.subtitle.isNotBlank()) {
                    Text(
                        r.subtitle, color = SpotifyLightGray, fontSize = 13.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String, onShowAll: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, color = SpotifyWhite, fontSize = 18.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
        Text(
            stringResource(R.string.search_show_all),
            color = SpotifyLightGray, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.clickable { onShowAll() }
        )
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
                label = stringResource(labelFor(f)),
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

private fun labelFor(filter: SearchViewModel.SearchFilter): Int = when (filter) {
    SearchViewModel.SearchFilter.ALL -> R.string.search_filter_all
    SearchViewModel.SearchFilter.ARTISTS -> R.string.search_filter_artists
    SearchViewModel.SearchFilter.ALBUMS -> R.string.search_filter_albums
    SearchViewModel.SearchFilter.SONGS -> R.string.search_filter_songs
    SearchViewModel.SearchFilter.PLAYLISTS -> R.string.search_filter_playlists
    SearchViewModel.SearchFilter.PODCASTS -> R.string.search_filter_podcasts
    SearchViewModel.SearchFilter.PROFILES -> R.string.search_filter_profiles
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
