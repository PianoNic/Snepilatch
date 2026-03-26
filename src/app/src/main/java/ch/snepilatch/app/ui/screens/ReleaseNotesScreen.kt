package ch.snepilatch.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ch.snepilatch.app.ui.theme.*
import ch.snepilatch.app.util.UpdateService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray

data class ReleaseNote(
    val version: String,
    val title: String,
    val body: String,
    val date: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseNotesScreen(onBack: () -> Unit) {
    val scope = rememberCoroutineScope()
    var releases by remember { mutableStateOf<List<ReleaseNote>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    fun load() {
        isLoading = true
        error = null
        scope.launch {
            try {
                val notes = withContext(Dispatchers.IO) { fetchReleaseNotes() }
                releases = notes
            } catch (e: Exception) {
                error = e.message ?: "Failed to load"
            }
            isLoading = false
        }
    }

    LaunchedEffect(Unit) { load() }

    Scaffold(
        containerColor = SpotifyBlack,
        topBar = {
            TopAppBar(
                title = { Text("Release Notes", color = SpotifyWhite) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, null, tint = SpotifyWhite)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = SpotifyBlack)
            )
        }
    ) { padding ->
        when {
            isLoading -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = SpotifyLightGray)
                }
            }
            error != null -> {
                Box(Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(Icons.Default.ErrorOutline, null, tint = SpotifyLightGray, modifier = Modifier.size(64.dp))
                        Spacer(Modifier.height(16.dp))
                        Text("Failed to load release notes", color = SpotifyWhite, fontSize = 18.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(error!!, color = SpotifyLightGray, fontSize = 13.sp)
                        Spacer(Modifier.height(24.dp))
                        Button(onClick = { load() }) {
                            Icon(Icons.Default.Refresh, null)
                            Spacer(Modifier.width(8.dp))
                            Text("Retry")
                        }
                    }
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(padding),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    itemsIndexed(releases) { index, note ->
                        ReleaseNoteCard(note, isLatest = index == 0)
                    }
                }
            }
        }
    }
}

@Composable
private fun ReleaseNoteCard(note: ReleaseNote, isLatest: Boolean) {
    Card(
        colors = CardDefaults.cardColors(containerColor = SpotifyElevated),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(Modifier.padding(16.dp)) {
            // Header: version badge + date
            Row(verticalAlignment = Alignment.CenterVertically) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = if (isLatest) MaterialTheme.colorScheme.primary
                        else SpotifyGray
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        "v${note.version}",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        color = if (isLatest) MaterialTheme.colorScheme.onPrimary else SpotifyLightGray
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
                            "LATEST",
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
                Spacer(Modifier.weight(1f))
                Text(note.date, color = SpotifyLightGray, fontSize = 12.sp)
            }

            Spacer(Modifier.height(12.dp))

            // Title
            Text(
                note.title,
                color = SpotifyWhite,
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

@Composable
private fun MarkdownText(markdown: String) {
    // Simple markdown rendering: headers, bullets, bold, links
    val lines = markdown.lines()
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        for (line in lines) {
            val trimmed = line.trim()
            when {
                trimmed.isEmpty() -> Spacer(Modifier.height(4.dp))
                trimmed.startsWith("### ") -> Text(
                    trimmed.removePrefix("### "),
                    color = SpotifyWhite, fontSize = 14.sp, fontWeight = FontWeight.SemiBold
                )
                trimmed.startsWith("## ") -> Text(
                    trimmed.removePrefix("## "),
                    color = SpotifyWhite, fontSize = 15.sp, fontWeight = FontWeight.Bold
                )
                trimmed.startsWith("# ") -> Text(
                    trimmed.removePrefix("# "),
                    color = SpotifyWhite, fontSize = 16.sp, fontWeight = FontWeight.Bold
                )
                trimmed.startsWith("- ") || trimmed.startsWith("* ") -> Text(
                    "  •  ${trimmed.drop(2).cleanMarkdown()}",
                    color = SpotifyLightGray, fontSize = 14.sp, lineHeight = 20.sp
                )
                trimmed.startsWith("> ") -> Text(
                    trimmed.removePrefix("> ").cleanMarkdown(),
                    color = SpotifyLightGray.copy(alpha = 0.7f), fontSize = 13.sp,
                    modifier = Modifier.padding(start = 12.dp)
                )
                else -> Text(
                    trimmed.cleanMarkdown(),
                    color = SpotifyLightGray, fontSize = 14.sp, lineHeight = 20.sp
                )
            }
        }
    }
}

private fun String.cleanMarkdown(): String {
    return this
        .replace(Regex("\\*\\*(.+?)\\*\\*"), "$1")  // bold
        .replace(Regex("\\*(.+?)\\*"), "$1")          // italic
        .replace(Regex("\\[(.+?)]\\(.+?\\)"), "$1")  // links
        .replace(Regex("`(.+?)`"), "$1")               // inline code
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ReleaseNotesDialog(onDismiss: () -> Unit) {
    val scope = rememberCoroutineScope()
    var releases by remember { mutableStateOf<List<ReleaseNote>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        try {
            releases = withContext(Dispatchers.IO) { fetchReleaseNotes() }
        } catch (e: Exception) {
            error = e.message
        }
        isLoading = false
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = SpotifyDarkGray,
        title = { Text("Release Notes", color = SpotifyWhite) },
        text = {
            Box(Modifier.heightIn(max = 500.dp)) {
                when {
                    isLoading -> Box(Modifier.fillMaxWidth().height(200.dp), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = SpotifyLightGray)
                    }
                    error != null -> Text("Failed to load: $error", color = SpotifyLightGray)
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
                Text("Close", color = SpotifyLightGray)
            }
        }
    )
}

private suspend fun fetchReleaseNotes(): List<ReleaseNote> {
    val client = OkHttpClient()
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
            title = obj.optString("name", "Release $tag"),
            body = obj.optString("body", ""),
            date = date
        ))
    }
    return notes
}
