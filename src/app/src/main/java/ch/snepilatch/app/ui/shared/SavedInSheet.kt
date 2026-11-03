package ch.snepilatch.app.ui.shared

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import ch.snepilatch.app.R
import ch.snepilatch.app.logic.shared.ThemeController
import ch.snepilatch.app.ui.theme.SnepilatchElevated
import ch.snepilatch.app.ui.theme.SnepilatchLightGray
import ch.snepilatch.app.ui.theme.SnepilatchWhite
import ch.snepilatch.app.viewmodel.SavedInViewModel
import kotify.api.playlist.CurationContext

private const val LOAD_MORE_MARGIN = 5

/** Which playlists hold [trackUri], ticked, as the web player's "Saved in" sheet. Done saves the changes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SavedInSheet(trackUri: String, onLiked: (Boolean) -> Unit, onDismiss: () -> Unit) {
    val savedIn: SavedInViewModel = viewModel()
    LaunchedEffect(trackUri) { savedIn.open(trackUri) }
    val state by savedIn.state.collectAsState()

    val sheetState = rememberBottomSheetState(
        initialValue = SheetValue.Hidden,
        enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
    )
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SnepilatchElevated,
        dragHandle = { SheetDragHandle() },
        properties = ModalBottomSheetProperties(shouldDismissOnBackPress = state.folder == null),
    ) {
        SheetNavBarFix()
        // Inside the sheet: it is its own window, so a handler outside never hears Back.
        BackHandler(enabled = state.folder != null) { savedIn.closeFolder() }
        Column(Modifier.fillMaxWidth().fillMaxHeight(0.85f)) {
            SavedInHeader(state.folder, onBack = savedIn::closeFolder)
            SavedInSearch(state.filter, savedIn::search)
            Box(Modifier.weight(1f).fillMaxWidth()) {
                SavedInList(state, savedIn)
                if (state.loading && state.rows.isEmpty()) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = SnepilatchLightGray)
                }
            }
            Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp)) {
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel), color = SnepilatchLightGray)
                }
                TextButton(
                    onClick = {
                        savedIn.save(onLiked)
                        onDismiss()
                    }
                ) {
                    Text(stringResource(R.string.done), color = SnepilatchWhite, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun SavedInHeader(folder: CurationContext?, onBack: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (folder != null) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, stringResource(R.string.back), tint = SnepilatchWhite)
            }
        }
        Text(
            folder?.name ?: stringResource(R.string.add_to_playlist),
            color = SnepilatchWhite,
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 8.dp),
        )
    }
}

@Composable
private fun SavedInSearch(filter: String, onChange: (String) -> Unit) {
    TextField(
        value = filter,
        onValueChange = onChange,
        singleLine = true,
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).clip(RoundedCornerShape(8.dp)),
        placeholder = { Text(stringResource(R.string.saved_in_search), color = SnepilatchLightGray.copy(alpha = 0.7f)) },
        leadingIcon = { Icon(Icons.Rounded.Search, null, tint = SnepilatchLightGray) },
        colors = TextFieldDefaults.colors(
            focusedTextColor = SnepilatchWhite,
            unfocusedTextColor = SnepilatchWhite,
            focusedContainerColor = SnepilatchLightGray.copy(alpha = 0.12f),
            unfocusedContainerColor = SnepilatchLightGray.copy(alpha = 0.12f),
            focusedIndicatorColor = Color.Transparent,
            unfocusedIndicatorColor = Color.Transparent,
        ),
    )
}

@Composable
private fun SavedInList(state: SavedInViewModel.State, savedIn: SavedInViewModel) {
    LazyColumn(Modifier.fillMaxWidth()) {
        // The server lists what holds the track first, so the heading sits above those rows.
        if (state.rows.firstOrNull()?.isCurated == true) {
            item(key = "saved-in") {
                Text(
                    stringResource(R.string.saved_in),
                    color = SnepilatchLightGray,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
                )
            }
        }
        itemsIndexed(state.rows, key = { _, row -> row.uri }) { index, row ->
            LaunchedEffect(index, state.rows.size) {
                if (index >= state.rows.size - LOAD_MORE_MARGIN) savedIn.loadMore()
            }
            SavedInRow(row, state.ticked(row), onClick = {
                if (row.type == "folder") savedIn.openFolder(row) else savedIn.toggle(row)
            })
        }
    }
}

@Composable
private fun SavedInRow(row: CurationContext, ticked: Boolean, onClick: () -> Unit) {
    val theme by ThemeController.themeColors.collectAsState()
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 20.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (row.type == "folder") {
            Icon(Icons.Rounded.Folder, null, tint = SnepilatchLightGray, modifier = Modifier.size(44.dp))
        } else {
            SpfyImage(url = row.imageUrl, modifier = Modifier.size(44.dp), shape = RoundedCornerShape(4.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(
            row.name,
            color = SnepilatchWhite,
            fontSize = 15.sp,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(12.dp))
        when {
            row.type == "folder" -> Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, null, tint = SnepilatchLightGray)
            ticked -> Icon(Icons.Rounded.CheckCircle, stringResource(R.string.saved_in), tint = theme.primary)
            else -> Icon(Icons.Rounded.RadioButtonUnchecked, null, tint = SnepilatchLightGray)
        }
    }
}
