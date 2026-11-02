package ch.snepilatch.app.ui.shared

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import ch.snepilatch.app.R
import ch.snepilatch.app.ui.theme.SnepilatchGray
import ch.snepilatch.app.ui.theme.SnepilatchLightGray
import ch.snepilatch.app.ui.theme.SnepilatchWhite
import ch.snepilatch.app.viewmodel.DuplicateAdd

/**
 * The web player's warning when a playlist already holds what is being added: with something new among
 * them the choice is the new ones or all, otherwise nothing or all anyway.
 */
@Composable
fun DuplicateTracksDialog(add: DuplicateAdd, onAdd: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val someNew = add.newUris.isNotEmpty()
    TightAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SnepilatchGray,
        title = {
            Text(stringResource(if (someNew) R.string.duplicate_some_title else R.string.duplicate_title), color = SnepilatchWhite)
        },
        text = {
            Text(
                stringResource(if (someNew) R.string.duplicate_some else R.string.duplicate_all, add.playlistName),
                color = SnepilatchLightGray,
            )
        },
        confirmButton = {
            TextButton(onClick = { if (someNew) onAdd(add.newUris) else onDismiss() }) {
                Text(stringResource(if (someNew) R.string.duplicate_add_new else R.string.duplicate_dont_add), color = SnepilatchWhite)
            }
        },
        dismissButton = {
            TextButton(onClick = { onAdd(add.uris) }) {
                Text(stringResource(if (someNew) R.string.duplicate_add_all else R.string.duplicate_add_anyway), color = SnepilatchLightGray)
            }
        },
    )
}
