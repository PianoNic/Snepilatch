package ch.snepilatch.app.ui.screens

import ch.snepilatch.app.ui.theme.SpfyWhite
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.List
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.R
import ch.snepilatch.app.download.Downloads
import ch.snepilatch.app.data.LIKED_SONGS_COVER_URL
import ch.snepilatch.app.data.LibraryItem
import ch.snepilatch.app.data.TrackInfo
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import ch.snepilatch.app.ui.components.SpfyImage
import ch.snepilatch.app.ui.components.TightAlertDialog
import ch.snepilatch.app.ui.theme.SpfyBlack
import ch.snepilatch.app.ui.theme.SpfyElevated
import ch.snepilatch.app.ui.theme.SpfyGray
import ch.snepilatch.app.ui.theme.SpfyLightGray
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.snepilatch.app.viewmodel.DetailViewModel
import ch.snepilatch.app.viewmodel.LibraryViewModel

private const val PREFS_NAME = "kotify_prefs"

// --- Library Screen ---

@Composable
fun LibraryScreen() {
    val libraryVm: LibraryViewModel = viewModel()
    val library by libraryVm.library.collectAsState()
    val libraryTotal by libraryVm.libraryTotal.collectAsState()
    val libraryHasMore = libraryTotal < 0 || library.size < libraryTotal
    var showCreateDialog by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    var gridView by remember { mutableStateOf(prefs.getBoolean("library_grid_view", false)) }
    var selectedFilter by remember { mutableStateOf<String?>(null) }
    var sortMode by remember { mutableStateOf(prefs.getString("library_sort", "recent") ?: "recent") }
    var showSortMenu by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    // Recompute the filter/search/sort pipeline only when an input actually changes, not on every
    // recomposition (e.g. a position tick or unrelated state update).
    // Downloaded content is grouped by the album or playlist it came from, so it browses like the
    // rest of the library rather than as a flat list of tracks.
    // Keyed on the published rows rather than querying: groups() reads them out of memory, so this
    // stays a grouping of a list the store already loaded off the main thread.
    val downloadedRows by Downloads.rows.collectAsState()
    val downloadedItems = remember(downloadedRows) {
        Downloads.groups().map { LibraryItem(it.uri, it.name, it.imageUrl, it.type) }
    }
    val sortedLibrary = remember(library, downloadedItems, selectedFilter, searchQuery, sortMode) {
        val source = if (selectedFilter == "Downloaded") downloadedItems else library
        val filteredLibrary = when (selectedFilter) {
            "Playlists" -> source.filter { it.type == "playlist" || it.type == "collection" }
            "Artists" -> source.filter { it.type == "artist" }
            "Albums" -> library.filter { it.type == "album" }
            else -> source
        }
        val searchedLibrary = if (searchQuery.isBlank()) filteredLibrary
        else filteredLibrary.filter {
            it.name.contains(searchQuery, ignoreCase = true) ||
            (it.owner?.contains(searchQuery, ignoreCase = true) == true)
        }
        when (sortMode) {
            "alpha" -> searchedLibrary.sortedBy { it.name.lowercase() }
            "type" -> searchedLibrary.sortedBy { it.type }
            else -> searchedLibrary
        }
    }

    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        // Header row: avatar + title + search + add
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(stringResource(R.string.library_title), color = SpfyWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            IconButton(onClick = { searchActive = !searchActive; if (!searchActive) searchQuery = "" }) {
                Icon(Icons.Rounded.Search, stringResource(R.string.search), tint = SpfyWhite, modifier = Modifier.size(24.dp))
            }
            IconButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Rounded.Add, stringResource(R.string.library_create), tint = SpfyWhite, modifier = Modifier.size(26.dp))
            }
        }

        // Search field
        androidx.compose.animation.AnimatedVisibility(visible = searchActive) {
            TextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp)
                    .clip(RoundedCornerShape(8.dp)),
                placeholder = {
                    Text(
                        stringResource(R.string.library_search_placeholder),
                        color = SpfyLightGray.copy(alpha = 0.7f)
                    )
                },
                leadingIcon = { Icon(Icons.Rounded.Search, null, tint = SpfyLightGray) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Rounded.Close, stringResource(R.string.clear), tint = SpfyLightGray)
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedTextColor = SpfyWhite,
                    unfocusedTextColor = SpfyWhite,
                    cursorColor = SpfyWhite,
                    focusedContainerColor = SpfyGray,
                    unfocusedContainerColor = SpfyGray,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                singleLine = true
            )
        }

        // Filter chips row
        val filters = listOf(
            "Playlists" to stringResource(R.string.library_filter_playlists),
            "Artists" to stringResource(R.string.library_filter_artists),
            "Albums" to stringResource(R.string.library_filter_albums)
        )
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            // The library lists playlists, artists and albums; downloads are individual tracks, so this
            // opens the downloads manager rather than filtering the list in place.
            item {
                if (downloadedItems.isNotEmpty()) {
                    FilterChip(
                        selected = selectedFilter == "Downloaded",
                        onClick = {
                            selectedFilter = if (selectedFilter == "Downloaded") null else "Downloaded"
                        },
                        label = {
                            Text(
                                stringResource(R.string.library_filter_downloaded, downloadedItems.size),
                                fontSize = 13.sp
                            )
                        },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = SpfyWhite,
                            selectedLabelColor = SpfyBlack,
                            containerColor = SpfyGray,
                            labelColor = SpfyWhite
                        ),
                        border = null
                    )
                }
            }
            items(filters.size) { i ->
                FilterChip(
                    selected = selectedFilter == filters[i].first,
                    onClick = {
                        selectedFilter = if (selectedFilter == filters[i].first) null else filters[i].first
                    },
                    label = { Text(filters[i].second, fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SpfyWhite,
                        selectedLabelColor = SpfyBlack,
                        containerColor = SpfyGray,
                        labelColor = SpfyWhite
                    ),
                    border = null
                )
            }
        }

        // Sort row: sort label on left, grid/list toggle on right
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box {
                Row(
                    Modifier.clickable { showSortMenu = true }.padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(Icons.Rounded.SwapVert, stringResource(R.string.library_sort), tint = SpfyLightGray, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    val sortLabel = when (sortMode) {
                        "alpha" -> stringResource(R.string.library_sort_alpha)
                        "type" -> stringResource(R.string.library_sort_by_type)
                        else -> stringResource(R.string.library_sort_recent)
                    }
                    Text(sortLabel, color = SpfyLightGray, fontSize = 13.sp)
                }
                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false },
                    containerColor = SpfyGray
                ) {
                    listOf(
                        "recent" to stringResource(R.string.library_sort_recent),
                        "alpha" to stringResource(R.string.library_sort_alpha),
                        "type" to stringResource(R.string.library_sort_by_type)
                    ).forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label, color = if (sortMode == value) SpfyWhite else SpfyLightGray) },
                            onClick = { sortMode = value; prefs.edit().putString("library_sort", value).apply(); showSortMenu = false }
                        )
                    }
                }
            }
            IconButton(
                onClick = { val newVal = !gridView; gridView = newVal; prefs.edit().putBoolean("library_grid_view", newVal).apply() },
                modifier = Modifier.size(32.dp)
            ) {
                Icon(
                    if (gridView) Icons.AutoMirrored.Rounded.List else Icons.Rounded.GridView,
                    stringResource(R.string.library_toggle_view),
                    tint = SpfyLightGray,
                    modifier = Modifier.size(20.dp)
                )
            }
        }

        Spacer(Modifier.height(4.dp))

        if (gridView) {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = LocalBottomOverlayHeight.current.value + 16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                itemsIndexed(sortedLibrary, key = { _, item -> item.uri }) { index, item ->
                    LibraryGridCard(item, downloadedGroup = selectedFilter == "Downloaded")
                    // Key the near-end trigger on the VISIBLE (filtered/searched) list, not the raw
                    // library — otherwise a filter that shrinks the list below library.size - 10 never
                    // reaches the threshold and pagination silently stops.
                    if (libraryHasMore && index >= sortedLibrary.size - 10) {
                        LaunchedEffect(sortedLibrary.size) { libraryVm.loadMoreLibrary() }
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(top = 4.dp, bottom = LocalBottomOverlayHeight.current.value + 16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                itemsIndexed(sortedLibrary, key = { _, item -> item.uri }) { index, item ->
                    LibraryListItem(item, downloadedGroup = selectedFilter == "Downloaded")
                    // See the grid branch: trigger on the visible list size, not the raw library, so
                    // pagination still fires when a filter/search shrinks the list.
                    if (libraryHasMore && index >= sortedLibrary.size - 10) {
                        LaunchedEffect(sortedLibrary.size) { libraryVm.loadMoreLibrary() }
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { libraryVm.createPlaylist(it); showCreateDialog = false }
        )
    }
}

fun libraryItemClick(item: LibraryItem, detailVm: DetailViewModel, vm: PlaybackViewModel? = null) {
    when (item.type) {
        // A one-off download has no album or playlist to open, so it is the track itself: play it.
        "single" -> {
            val row = Downloads.find(item.uri) ?: return
            vm?.playTrack(
                TrackInfo(uri = item.uri, name = row.title, artist = row.artist, albumArt = row.coverUrl)
            )
        }
        "collection" -> detailVm.openLikedSongs()
        "playlist" -> {
            val id = item.uri.split(":").lastOrNull() ?: return
            detailVm.openPlaylist(id)
        }
        "album" -> {
            val id = item.uri.split(":").lastOrNull() ?: return
            detailVm.openAlbum(id)
        }
        "artist" -> {
            val id = item.uri.split(":").lastOrNull() ?: return
            detailVm.openArtist(id)
        }
        "show" -> {
            val id = item.uri.split(":").lastOrNull() ?: return
            detailVm.openShow(id, item.owner, item.imageUrl)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryGridCard(item: LibraryItem, downloadedGroup: Boolean = false) {
    val detailVm: DetailViewModel = viewModel()
    val playbackVm: PlaybackViewModel = viewModel()
    val isArtist = item.type == "artist"
    val removable = item.type != "collection"
    var showRemove by remember { mutableStateOf(false) }
    if (showRemove && removable) {
        if (downloadedGroup) {
            DownloadRemoveDialog(item, onDismiss = { showRemove = false })
        } else {
            LibraryRemoveDialog(item, onDismiss = { showRemove = false })
        }
    }
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { libraryItemClick(item, detailVm, playbackVm) },
                onLongClick = { if (removable) showRemove = true }
            ),
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start
    ) {
        if (item.type == "collection") {
            SpfyImage(
                url = LIKED_SONGS_COVER_URL,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                shape = RoundedCornerShape(8.dp)
            )
        } else {
            SpfyImage(
                url = item.imageUrl,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                shape = if (isArtist) CircleShape else RoundedCornerShape(8.dp),
                icon = when (item.type) {
                    "artist" -> Icons.Rounded.Person
                    "album" -> Icons.Rounded.Album
                    else -> Icons.AutoMirrored.Rounded.QueueMusic
                }
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(item.name, color = SpfyWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${item.type.replaceFirstChar { it.uppercase() }}${if (item.owner != null) " \u00B7 ${item.owner}" else ""}",
            color = SpfyLightGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryListItem(item: LibraryItem, downloadedGroup: Boolean = false) {
    val detailVm: DetailViewModel = viewModel()
    val playbackVm: PlaybackViewModel = viewModel()
    val isArtist = item.type == "artist"
    val removable = item.type != "collection"
    var showRemove by remember { mutableStateOf(false) }
    if (showRemove && removable) {
        if (downloadedGroup) {
            DownloadRemoveDialog(item, onDismiss = { showRemove = false })
        } else {
            LibraryRemoveDialog(item, onDismiss = { showRemove = false })
        }
    }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { libraryItemClick(item, detailVm, playbackVm) },
                onLongClick = { if (removable) showRemove = true }
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item.type == "collection") {
            SpfyImage(
                url = LIKED_SONGS_COVER_URL,
                modifier = Modifier.size(56.dp),
                shape = RoundedCornerShape(4.dp),
                icon = Icons.Rounded.Favorite
            )
        } else {
            SpfyImage(
                url = item.imageUrl,
                modifier = Modifier.size(56.dp),
                shape = if (isArtist) CircleShape else RoundedCornerShape(4.dp),
                icon = when (item.type) {
                    "artist" -> Icons.Rounded.Person
                    "album" -> Icons.Rounded.Album
                    else -> Icons.AutoMirrored.Rounded.QueueMusic
                }
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, color = SpfyWhite, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${item.type.replaceFirstChar { it.uppercase() }}${if (item.owner != null) " \u00B7 ${item.owner}" else ""}",
                color = SpfyLightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun CreatePlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    TightAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SpfyElevated,
        title = { Text(stringResource(R.string.library_create_playlist), color = SpfyWhite, fontWeight = FontWeight.Bold) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text(stringResource(R.string.library_playlist_name_hint), color = SpfyLightGray) },
                colors = TextFieldDefaults.colors(
                    focusedTextColor = SpfyWhite,
                    unfocusedTextColor = SpfyWhite,
                    cursorColor = SpfyWhite,
                    focusedContainerColor = SpfyGray,
                    unfocusedContainerColor = SpfyGray,
                    focusedIndicatorColor = SpfyWhite,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onCreate(name) }) {
                Text(stringResource(R.string.library_create_button), color = SpfyWhite, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(stringResource(R.string.cancel), color = SpfyLightGray) }
        }
    )
}

/**
 * Long-pressing a downloaded group drops its files. The same gesture on a normal library entry
 * removes it from the Spfy library, which is not what is wanted while browsing downloads.
 */
@Composable
private fun DownloadRemoveDialog(item: LibraryItem, onDismiss: () -> Unit) {
    val vm: PlaybackViewModel = viewModel()
    val tracks = remember(item.uri) { Downloads.tracksInGroup(item.uri) }
    TightAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.remove_download), color = SpfyWhite) },
        text = {
            Text(
                pluralStringResource(R.plurals.remove_download_message, tracks.size, item.name, tracks.size),
                color = SpfyLightGray
            )
        },
        containerColor = SpfyGray,
        confirmButton = {
            TextButton(onClick = {
                tracks.forEach { vm.removeDownload(it.trackUri) }
                onDismiss()
            }) {
                Text(stringResource(R.string.library_remove_button), color = Color.Red)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = SpfyLightGray)
            }
        }
    )
}

@Composable
private fun LibraryRemoveDialog(item: LibraryItem, onDismiss: () -> Unit) {
    val libraryVm: LibraryViewModel = viewModel()
    TightAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.library_remove_title), color = SpfyWhite) },
        text = { Text(stringResource(R.string.library_remove_message, item.name), color = SpfyLightGray) },
        containerColor = SpfyGray,
        confirmButton = {
            TextButton(onClick = {
                libraryVm.removeFromLibrary(item)
                onDismiss()
            }) {
                Text(stringResource(R.string.library_remove_button), color = Color.Red)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = SpfyLightGray)
            }
        }
    )
}
