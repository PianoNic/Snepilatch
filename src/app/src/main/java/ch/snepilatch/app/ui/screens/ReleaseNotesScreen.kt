@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ch.snepilatch.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.R
import ch.snepilatch.app.ui.components.MarkdownText
import ch.snepilatch.app.ui.components.TightAlertDialog
import ch.snepilatch.app.ui.theme.*
import ch.snepilatch.app.util.UpdateService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Request
import org.json.JSONArray

data class ReleaseNote(
    val version: String,
    val title: String,
    val body: String,
    val date: String
)

@Composable
private fun ReleaseNoteCard(note: ReleaseNote, isLatest: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SpfyElevated),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            // Header: version badge + date
            Row(verticalAlignment = Alignment.CenterVertically) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isLatest) MaterialTheme.colorScheme.primary
                        else SpfyGray
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        "v${note.version}",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isLatest) MaterialTheme.colorScheme.onPrimary else SpfyLightGray
                    )
                }
                if (isLatest) {
                    Spacer(Modifier.width(8.dp))
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            stringResource(R.string.release_latest_badge),
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(note.date, color = SpfyLightGray, fontSize = 12.sp)
            }

            Spacer(Modifier.height(12.dp))

            // Title
            Text(
                note.title,
                color = SpfyWhite,
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )

            if (note.body.isNotBlank()) {
                Spacer(Modifier.height(12.dp))
                // Render markdown as simple formatted text
                MarkdownText(note.body)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseNotesDialog(onDismiss: () -> Unit) {
    val context = LocalContext.current
    var releases by remember { mutableStateOf<List<ReleaseNote>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            releases = withContext(Dispatchers.IO) { fetchReleaseNotes(context) }
        } catch (e: Exception) {
            error = e.message
        }
        isLoading = false
    }

    TightAlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SpfyDarkGray,
        title = { Text(stringResource(R.string.release_notes), color = SpfyWhite) },
        text = {
            Box(Modifier.heightIn(max = 500.dp)) {
                when {
                    isLoading -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        LoadingIndicator(color = SpfyLightGray)
                    }
                    error != null -> Text(stringResource(R.string.release_load_failed_short, error ?: ""), color = SpfyLightGray)
                    else -> LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        itemsIndexed(releases) { index, note ->
                            ReleaseNoteCard(note, isLatest = index == 0)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.close), color = SpfyLightGray)
            }
        }
    )
}

private suspend fun fetchReleaseNotes(context: android.content.Context): List<ReleaseNote> {
    val client = UpdateService.client
    val request = Request.Builder()
        .url("https://api.github.com/repos/PianoNic/Snepilatch/releases")
        .header("Accept", "application/vnd.github+json")
        .build()
    val response = client.newCall(request).execute()
    if (!response.isSuccessful) throw Exception("HTTP ${response.code}")
    val json = JSONArray(response.body?.string() ?: "[]")
    val notes = mutableListOf<ReleaseNote>()
    for (i in 0 until json.length()) {
        val obj = json.getJSONObject(i)
        val tag = obj.optString("tag_name", "").removePrefix("v")
        val published = obj.optString("published_at", "")
        val date = if (published.length >= 10) published.substring(0, 10) else published
        notes.add(ReleaseNote(
            version = tag,
            title = obj.optString("name", context.getString(R.string.release_fallback_title, tag)),
            body = obj.optString("body", ""),
            date = date
        ))
    }
    return notes
}
