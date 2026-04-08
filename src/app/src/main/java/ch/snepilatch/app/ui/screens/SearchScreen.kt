package ch.snepilatch.app.ui.screens

import ch.snepilatch.app.ui.theme.SpotifyWhite
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
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
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.ui.components.TrackRow
import ch.snepilatch.app.ui.components.itemAppearModifier
import ch.snepilatch.app.ui.theme.SpotifyBlack
import ch.snepilatch.app.ui.theme.SpotifyLightGray
import ch.snepilatch.app.viewmodel.SearchViewModel
import ch.snepilatch.app.viewmodel.SpotifyViewModel

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

// --- Search Screen ---

@Composable
fun SearchScreen(vm: SpotifyViewModel, searchVm: SearchViewModel = viewModel()) {
    val query by searchVm.query.collectAsState()
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
            placeholder = { Text("Songs, artists, albums...", color = SpotifyLightGray.copy(alpha = 0.7f)) },
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
            keyboardActions = KeyboardActions(onSearch = { focusManager.clearFocus() })
        )

        Spacer(Modifier.height(8.dp))

        if (query.isEmpty() && results.isEmpty()) {
            // Browse categories
            Text(
                "Browse All",
                color = SpotifyWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = LocalBottomOverlayHeight.current.value + 16.dp),
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
                            .clickable { searchVm.updateQuery(cat.name) }
                            .padding(14.dp),
                        contentAlignment = Alignment.BottomStart
                    ) {
                        Text(cat.name, color = SpotifyWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        } else {
            AnimatedVisibility(
                visible = isSearching,
                enter = fadeIn(),
                exit = fadeOut()
            ) {
                Box(Modifier.fillMaxWidth().padding(16.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = SpotifyWhite, modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
                }
            }

            LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = LocalBottomOverlayHeight.current.value + 16.dp)) {
                itemsIndexed(results) { index, track ->
                    Box(itemAppearModifier(index)) {
                        TrackRow(track, vm)
                    }
                }
            }
        }
    }
}
