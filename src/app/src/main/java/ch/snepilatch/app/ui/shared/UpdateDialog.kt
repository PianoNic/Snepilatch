package ch.snepilatch.app.ui.shared

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.SystemUpdate
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import ch.snepilatch.app.R
import ch.snepilatch.app.logic.shared.UpdateInfo
import ch.snepilatch.app.logic.shared.UpdateService
import kotlinx.coroutines.launch

@Composable
fun UpdateDialog(
    updateInfo: UpdateInfo,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var isDownloading by remember { mutableStateOf(false) }
    var progress by remember { mutableFloatStateOf(0f) }
    var error by remember { mutableStateOf<String?>(null) }

    TightAlertDialog(
        onDismissRequest = { if (!isDownloading) onDismiss() },
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    Icons.Rounded.SystemUpdate,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary
                )
                Spacer(Modifier.width(8.dp))
                Text(stringResource(R.string.update_available))
            }
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // Version comparison
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    // One line per version, label left and name right: two nightly names never fit
                    // side by side, and a name broken across lines reads as a typo.
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        VersionLine(stringResource(R.string.current_version), updateInfo.currentVersion, MaterialTheme.colorScheme.onPrimaryContainer)
                        VersionLine(stringResource(R.string.new_version), updateInfo.latestVersion, MaterialTheme.colorScheme.primary)
                    }
                }

                if (updateInfo.isPrerelease) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            // No Icon here: the string leads with a warning glyph, and an
                            // ErrorOutline next to it read as two warnings stacked.
                            Text(
                                stringResource(R.string.nightly_build_warning),
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                // Download progress
                if (isDownloading) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        "${(progress * 100).toInt()}%",
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                // Error
                if (error != null) {
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.errorContainer
                        )
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Icon(
                                Icons.Rounded.ErrorOutline,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onErrorContainer
                            )
                            Text(
                                error!!,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                // Release notes
                if (!isDownloading && updateInfo.releaseNotes.isNotBlank()) {
                    Text(stringResource(R.string.whats_new), style = MaterialTheme.typography.titleSmall)
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceContainerHigh
                        )
                    ) {
                        // Scrolled rather than truncated: take(500) used to cut a
                        // changelog mid-word, and the last entries are the newest.
                        MarkdownText(
                            updateInfo.releaseNotes,
                            modifier = Modifier
                                .heightIn(max = 220.dp)
                                .verticalScroll(rememberScrollState())
                                .padding(12.dp)
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (!isDownloading) {
                Button(onClick = {
                    isDownloading = true
                    error = null
                    scope.launch {
                        val file = UpdateService.downloadApk(context, updateInfo.downloadUrl) { p ->
                            progress = p
                        }
                        if (file != null) {
                            isDownloading = false
                            if (!UpdateService.installApk(context, file)) {
                                error = context.getString(R.string.enable_unknown_apps)
                            }
                        } else {
                            isDownloading = false
                            error = context.getString(R.string.update_download_failed)
                        }
                    }
                }) {
                    Text(stringResource(R.string.update_now))
                }
            }
        },
        dismissButton = {
            if (!isDownloading) {
                TextButton(onClick = {
                    UpdateService.dismissVersion(context, updateInfo.latestVersion)
                    onDismiss()
                }) {
                    Text(stringResource(R.string.later))
                }
            } else {
                TextButton(onClick = onDismiss) {
                    Text(stringResource(R.string.cancel))
                }
            }
        }
    )
}

@Composable
private fun VersionLine(label: String, version: String, color: androidx.compose.ui.graphics.Color) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.labelLarge)
        Text(
            version,
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 1,
            modifier = Modifier.padding(start = 16.dp)
        )
    }
}
