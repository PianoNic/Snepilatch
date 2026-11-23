package ch.snepilatch.app.ui.shared

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.R
import ch.snepilatch.app.logic.shared.AppSettings
import ch.snepilatch.app.ui.theme.SnepilatchGray
import ch.snepilatch.app.ui.theme.SnepilatchLightGray
import ch.snepilatch.app.ui.theme.SnepilatchWhite
import androidx.compose.ui.res.stringResource

/** One row of the source order: which source, its name and line, and whether it is tried at all. */
private data class SourceRow(val id: String, val on: Boolean)

/**
 * The playback sources in the order a track is tried on them (#1132). Switched-on ones are tried top
 * to bottom until one has the track; switched-off ones are never asked. At least one stays on.
 */
@Composable
fun SourceOrderDialog(order: List<String>, onSave: (List<String>) -> Unit, onDismiss: () -> Unit) {
    val rows = remember {
        val all = listOf(AppSettings.SOURCE_SPFY, AppSettings.SOURCE_LOSSLESS, AppSettings.SOURCE_YTM)
        mutableStateListOf<SourceRow>().apply {
            addAll(order.map { SourceRow(it, on = true) })
            addAll(all.filterNot { it in order }.map { SourceRow(it, on = false) })
        }
    }
    TightAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.audio_source), color = SnepilatchWhite) },
        text = {
            Column {
                Text(stringResource(R.string.audio_source_order_hint), color = SnepilatchLightGray, fontSize = 13.sp)
                Spacer(Modifier.height(12.dp))
                rows.forEachIndexed { index, row ->
                    SourceOrderRow(
                        row = row,
                        // The last switched-on source cannot be switched off: something has to play.
                        canToggle = !row.on || rows.count { it.on } > 1,
                        onToggle = { rows[index] = row.copy(on = !row.on) },
                        onUp = if (index > 0) ({ rows.add(index - 1, rows.removeAt(index)) }) else null,
                        onDown = if (index < rows.lastIndex) ({ rows.add(index + 1, rows.removeAt(index)) }) else null,
                    )
                }
            }
        },
        containerColor = SnepilatchGray,
        confirmButton = {
            TextButton(onClick = { onSave(rows.filter { it.on }.map { it.id }) }) {
                Text(stringResource(R.string.done), color = SnepilatchWhite)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = SnepilatchLightGray)
            }
        },
    )
}

@Composable
private fun SourceOrderRow(row: SourceRow, canToggle: Boolean, onToggle: () -> Unit, onUp: (() -> Unit)?, onDown: (() -> Unit)?) {
    val (label, line) = sourceText(row.id)
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 48.dp)
            .toggleable(value = row.on, enabled = canToggle, role = Role.Checkbox) { onToggle() },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Checkbox(checked = row.on, onCheckedChange = null, enabled = canToggle)
        Spacer(Modifier.width(8.dp))
        Column(Modifier.weight(1f)) {
            Text(label, color = if (row.on) SnepilatchWhite else SnepilatchLightGray, fontSize = 15.sp)
            Text(line, color = SnepilatchLightGray, fontSize = 12.sp)
        }
        IconButton(onClick = { onUp?.invoke() }, enabled = onUp != null) {
            Icon(Icons.Rounded.KeyboardArrowUp, stringResource(R.string.move_up), tint = if (onUp != null) SnepilatchWhite else SnepilatchGray)
        }
        IconButton(onClick = { onDown?.invoke() }, enabled = onDown != null) {
            Icon(Icons.Rounded.KeyboardArrowDown, stringResource(R.string.move_down), tint = if (onDown != null) SnepilatchWhite else SnepilatchGray)
        }
    }
}

/** The name and one-line description of source [id]. */
@Composable
fun sourceText(id: String): Pair<String, String> = when (id) {
    AppSettings.SOURCE_LOSSLESS -> stringResource(R.string.lossless_audio) to stringResource(R.string.audio_source_lossless_desc)
    AppSettings.SOURCE_YTM -> stringResource(R.string.audio_source_ytm) to stringResource(R.string.audio_source_ytm_desc)
    else -> stringResource(R.string.audio_source_spotify) to stringResource(R.string.audio_source_spotify_desc)
}
