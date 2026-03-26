package ch.snepilatch.app.ui.screens

import ch.snepilatch.app.ui.theme.SpotifyWhite
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
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
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.automirrored.filled.QueueMusic
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.GridView
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.data.LibraryItem
import ch.snepilatch.app.ui.components.SpotifyImage
import ch.snepilatch.app.ui.components.itemAppearModifier
import ch.snepilatch.app.ui.theme.SpotifyBlack
import ch.snepilatch.app.ui.theme.SpotifyElevated
import ch.snepilatch.app.ui.theme.SpotifyGray
import ch.snepilatch.app.ui.theme.SpotifyLightGray
import ch.snepilatch.app.viewmodel.SpotifyViewModel
import coil.compose.AsyncImage

private const val PREFS_NAME = "kotify_prefs"
private const val LIKED_SONGS_IMAGE = "https://image-cdn-ak.spotifycdn.com/image/ab67706c0000da84587ecba4a27774b2f6f07174"

// --- Library Screen ---

@Composable
fun LibraryScreen(vm: SpotifyViewModel) {
    val library by vm.library.collectAsState()
    val account by vm.account.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val prefs = remember { context.getSharedPreferences(PREFS_NAME, android.content.Context.MODE_PRIVATE) }
    var gridView by remember { mutableStateOf(prefs.getBoolean("library_grid_view", false)) }
    var selectedFilter by remember { mutableStateOf<String?>(null) }
    var sortMode by remember { mutableStateOf(prefs.getString("library_sort", "recent") ?: "recent") }
    var showSortMenu by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }
    var searchQuery by remember { mutableStateOf("") }

    val filteredLibrary = when (selectedFilter) {
        "Playlists" -> library.filter { it.type == "playlist" || it.type == "collection" }
        "Artists" -> library.filter { it.type == "artist" }
        "Albums" -> library.filter { it.type == "album" }
        else -> library
    }
    val searchedLibrary = if (searchQuery.isBlank()) filteredLibrary
    else filteredLibrary.filter {
        it.name.contains(searchQuery, ignoreCase = true) ||
        (it.owner?.contains(searchQuery, ignoreCase = true) == true)
    }
    val sortedLibrary = when (sortMode) {
        "alpha" -> searchedLibrary.sortedBy { it.name.lowercase() }
        "type" -> searchedLibrary.sortedBy { it.type }
        else -> searchedLibrary
    }

    Column(Modifier.fillMaxSize().padding(top = 8.dp)) {
        // Header row: avatar + title + search + add
        Row(
            Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("Your Library", color = SpotifyWhite, fontSize = 24.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.weight(1f))
            IconButton(onClick = { searchActive = !searchActive; if (!searchActive) searchQuery = "" }) {
                Icon(Icons.Default.Search, "Search", tint = SpotifyWhite, modifier = Modifier.size(24.dp))
            }
            IconButton(onClick = { showCreateDialog = true }) {
                Icon(Icons.Default.Add, "Create", tint = SpotifyWhite, modifier = Modifier.size(26.dp))
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
                placeholder = { Text("Search in Your Library", color = SpotifyLightGray.copy(alpha = 0.7f)) },
                leadingIcon = { Icon(Icons.Default.Search, null, tint = SpotifyLightGray) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Default.Close, "Clear", tint = SpotifyLightGray)
                        }
                    }
                },
                colors = TextFieldDefaults.colors(
                    focusedTextColor = SpotifyWhite,
                    unfocusedTextColor = SpotifyWhite,
                    cursorColor = SpotifyWhite,
                    focusedContainerColor = SpotifyGray,
                    unfocusedContainerColor = SpotifyGray,
                    focusedIndicatorColor = Color.Transparent,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                singleLine = true
            )
        }

        // Filter chips row
        LazyRow(
            contentPadding = PaddingValues(horizontal = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(vertical = 8.dp)
        ) {
            val filters = listOf("Playlists", "Artists", "Albums")
            items(filters.size) { i ->
                FilterChip(
                    selected = selectedFilter == filters[i],
                    onClick = {
                        selectedFilter = if (selectedFilter == filters[i]) null else filters[i]
                    },
                    label = { Text(filters[i], fontSize = 13.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = SpotifyWhite,
                        selectedLabelColor = SpotifyBlack,
                        containerColor = SpotifyGray,
                        labelColor = SpotifyWhite
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
                    Icon(Icons.Default.SwapVert, "Sort", tint = SpotifyLightGray, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    val sortLabel = when (sortMode) {
                        "alpha" -> "Alphabetical"
                        "type" -> "By Type"
                        else -> "Recent"
                    }
                    Text(sortLabel, color = SpotifyLightGray, fontSize = 13.sp)
                }
                DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false },
                    containerColor = SpotifyGray
                ) {
                    listOf("recent" to "Recent", "alpha" to "Alphabetical", "type" to "By Type").forEach { (value, label) ->
                        DropdownMenuItem(
                            text = { Text(label, color = if (sortMode == value) SpotifyWhite else SpotifyLightGray) },
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
                    if (gridView) Icons.AutoMirrored.Filled.List else Icons.Default.GridView,
                    "Toggle view",
                    tint = SpotifyLightGray,
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
                itemsIndexed(sortedLibrary) { index, item ->
                    Box(itemAppearModifier(index)) {
                        LibraryGridCard(item, vm)
                    }
                }
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(top = 4.dp, bottom = LocalBottomOverlayHeight.current.value + 16.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp)
            ) {
                itemsIndexed(sortedLibrary) { index, item ->
                    Box(itemAppearModifier(index)) {
                        LibraryListItem(item, vm)
                    }
                }
            }
        }
    }

    if (showCreateDialog) {
        CreatePlaylistDialog(
            onDismiss = { showCreateDialog = false },
            onCreate = { vm.createPlaylist(it); showCreateDialog = false }
        )
    }
}

fun libraryItemClick(item: LibraryItem, vm: SpotifyViewModel) {
    when (item.type) {
        "collection" -> vm.openLikedSongs()
        "playlist" -> {
            val id = item.uri.split(":").lastOrNull() ?: return
            vm.openPlaylist(id)
        }
        "album" -> {
            val id = item.uri.split(":").lastOrNull() ?: return
            vm.openAlbum(id)
        }
        "artist" -> {
            val id = item.uri.split(":").lastOrNull() ?: return
            vm.openArtist(id)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryGridCard(item: LibraryItem, vm: SpotifyViewModel) {
    val isArtist = item.type == "artist"
    var showRemove by remember { mutableStateOf(false) }
    if (showRemove && item.type != "collection") {
        LibraryRemoveDialog(item, vm, onDismiss = { showRemove = false })
    }
    Column(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { libraryItemClick(item, vm) },
                onLongClick = { if (item.type != "collection") showRemove = true }
            ),
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start
    ) {
        if (item.type == "collection") {
            SpotifyImage(
                url = LIKED_SONGS_IMAGE,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                shape = RoundedCornerShape(8.dp)
            )
        } else {
            SpotifyImage(
                url = item.imageUrl,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                shape = if (isArtist) CircleShape else RoundedCornerShape(8.dp),
                icon = when (item.type) {
                    "artist" -> Icons.Default.Person
                    "album" -> Icons.Default.Album
                    else -> Icons.AutoMirrored.Filled.QueueMusic
                }
            )
        }
        Spacer(Modifier.height(8.dp))
        Text(item.name, color = SpotifyWhite, fontSize = 14.sp, fontWeight = FontWeight.Medium,
            maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(
            "${item.type.replaceFirstChar { it.uppercase() }}${if (item.owner != null) " \u00B7 ${item.owner}" else ""}",
            color = SpotifyLightGray, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
        )
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LibraryListItem(item: LibraryItem, vm: SpotifyViewModel) {
    val isArtist = item.type == "artist"
    var showRemove by remember { mutableStateOf(false) }
    if (showRemove && item.type != "collection") {
        LibraryRemoveDialog(item, vm, onDismiss = { showRemove = false })
    }
    Row(
        Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = { libraryItemClick(item, vm) },
                onLongClick = { if (item.type != "collection") showRemove = true }
            )
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (item.type == "collection") {
            // Liked Songs with gradient background like Spotify
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(4.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFF450AF5), Color(0xFFC4EAFD)))),
                contentAlignment = Alignment.Center
            ) {
                Icon(Icons.Default.FavoriteBorder, null, tint = SpotifyWhite, modifier = Modifier.size(28.dp))
            }
        } else {
            SpotifyImage(
                url = item.imageUrl,
                modifier = Modifier.size(56.dp),
                shape = if (isArtist) CircleShape else RoundedCornerShape(4.dp),
                icon = when (item.type) {
                    "artist" -> Icons.Default.Person
                    "album" -> Icons.Default.Album
                    else -> Icons.AutoMirrored.Filled.QueueMusic
                }
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.name, color = SpotifyWhite, fontSize = 16.sp, fontWeight = FontWeight.Medium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${item.type.replaceFirstChar { it.uppercase() }}${if (item.owner != null) " \u00B7 ${item.owner}" else ""}",
                color = SpotifyLightGray, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun CreatePlaylistDialog(onDismiss: () -> Unit, onCreate: (String) -> Unit) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SpotifyElevated,
        title = { Text("Create Playlist", color = SpotifyWhite, fontWeight = FontWeight.Bold) },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                placeholder = { Text("Playlist name", color = SpotifyLightGray) },
                colors = TextFieldDefaults.colors(
                    focusedTextColor = SpotifyWhite,
                    unfocusedTextColor = SpotifyWhite,
                    cursorColor = SpotifyWhite,
                    focusedContainerColor = SpotifyGray,
                    unfocusedContainerColor = SpotifyGray,
                    focusedIndicatorColor = SpotifyWhite,
                    unfocusedIndicatorColor = Color.Transparent
                ),
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        },
        confirmButton = {
            TextButton(onClick = { if (name.isNotBlank()) onCreate(name) }) {
                Text("Create", color = SpotifyWhite, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Cancel", color = SpotifyLightGray) }
        }
    )
}

@Composable
private fun LibraryRemoveDialog(item: LibraryItem, vm: SpotifyViewModel, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Remove from Library", color = SpotifyWhite) },
        text = { Text("Remove \"${item.name}\" from your library?", color = SpotifyLightGray) },
        containerColor = SpotifyGray,
        confirmButton = {
            TextButton(onClick = {
                vm.removeFromLibrary(item)
                onDismiss()
            }) {
                Text("Remove", color = Color.Red)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = SpotifyLightGray)
            }
        }
    )
}
