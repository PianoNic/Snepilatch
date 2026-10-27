@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ch.snepilatch.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import ch.snepilatch.app.BuildConfig
import ch.snepilatch.app.R
import ch.snepilatch.app.data.Screen
import ch.snepilatch.app.logic.download.DownloadFolder
import ch.snepilatch.app.logic.download.Downloads
import ch.snepilatch.app.logic.relay.RelaySettings
import ch.snepilatch.app.logic.shared.AppSettings
import ch.snepilatch.app.logic.shared.ThemeController
import ch.snepilatch.app.logic.shared.UpdateInfo
import ch.snepilatch.app.logic.shared.UpdateService
import ch.snepilatch.app.ui.shared.RadioOption
import ch.snepilatch.app.ui.shared.RadioPickerDialog
import ch.snepilatch.app.ui.shared.SettingRow
import ch.snepilatch.app.ui.shared.SettingToggleRow
import ch.snepilatch.app.ui.shared.SettingsSectionHeader
import ch.snepilatch.app.ui.shared.UpdateDialog
import ch.snepilatch.app.ui.theme.*
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import coil.compose.AsyncImage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Settings stores no Spfy id for its own source, so the source picker stands in this one. */
internal const val SOURCE_SPOTIFY_UI = "spotify"

/** Audio source, content region and the device picker: the Playback page of Settings. */
@Composable
internal fun PlaybackSettings(vm: PlaybackViewModel) {
    AudioSourceRow()
    ContentRegionRow()
    DeviceRow(vm)
}

/** Download source, folder and storage limit: the Downloads page of Settings. */
@Composable
internal fun DownloadSettings(vm: PlaybackViewModel) {
    val theme by ThemeController.themeColors.collectAsState()
    val animatedPrimary by animateColorAsState(theme.primary, tween(800), label = "settingsPrimary")
    DownloadSourceRow()
    AutoSaveRow(animatedPrimary)
    ManageDownloadsRow(vm)
    DownloadFolderRow()
    StorageLimitRow()
    StoragePolicyRow()
}

/** The equalizer mode and its headroom: the Sound page of Settings. */
@Composable
internal fun SoundSettings(vm: PlaybackViewModel) {
    EqualizerRows(vm)
}

/** Version, updates, relay, debug logging and the thanks: the About page of Settings. */
@Composable
internal fun AboutSettings() {
    val theme by ThemeController.themeColors.collectAsState()
    val animatedPrimary by animateColorAsState(theme.primary, tween(800), label = "settingsPrimary")
    VersionRow()
    UpdateChannelRow()
    CheckForUpdatesRow(animatedPrimary)
    ReleaseNotesRow()
    RelayRow()
    DebugLoggingRow()
    Spacer(Modifier.height(24.dp))
    SpecialThanks()
}

@Composable
private fun AudioSourceRow() {
    val audioContext = LocalContext.current
    // One choice of three: the sources exclude each other and a chosen one is never silently
    // swapped (#480). The setting stores null for Spfy, so the dialog stands in SOURCE_SPOTIFY_UI.
    val audioSource by AppSettings.preferredAudioSource.collectAsState()
    var showSourcePicker by remember { mutableStateOf(false) }
    val sourceLabel = when (audioSource) {
        AppSettings.SOURCE_LOSSLESS -> stringResource(R.string.lossless_on_flac)
        AppSettings.SOURCE_YTM -> stringResource(R.string.audio_source_ytm)
        else -> stringResource(R.string.lossless_off_spfy)
    }
    SettingRow(
        title = stringResource(R.string.audio_source),
        subtitle = sourceLabel,
        icon = Icons.Rounded.MusicNote,
        onClick = { showSourcePicker = true },
    )
    if (showSourcePicker) {
        RadioPickerDialog(
            title = stringResource(R.string.audio_source),
            options = listOf(
                RadioOption(
                    SOURCE_SPOTIFY_UI,
                    stringResource(R.string.audio_source_spotify),
                    stringResource(R.string.audio_source_spotify_desc)
                ),
                RadioOption(
                    AppSettings.SOURCE_LOSSLESS,
                    stringResource(R.string.lossless_audio),
                    stringResource(R.string.audio_source_lossless_desc)
                ),
                RadioOption(
                    AppSettings.SOURCE_YTM,
                    stringResource(R.string.audio_source_ytm),
                    stringResource(R.string.audio_source_ytm_desc)
                )
            ),
            selected = audioSource ?: SOURCE_SPOTIFY_UI,
            onSelect = { picked ->
                AppSettings.setPreferredAudioSource(picked.takeIf { it != SOURCE_SPOTIFY_UI }, audioContext)
                showSourcePicker = false
            },
            onDismiss = { showSourcePicker = false }
        )
    }
}

@Composable
private fun ContentRegionRow() {
    val audioContext = LocalContext.current
    // Content region picker
    val currentRegion by AppSettings.contentRegion.collectAsState()
    var showRegionPicker by remember { mutableStateOf(false) }
    val regionLabel = if (currentRegion == "nearest") {
        stringResource(R.string.region_nearest)
    } else {
        currentRegion
    }
    SettingRow(
        title = stringResource(R.string.content_region),
        subtitle = regionLabel,
        icon = Icons.Rounded.Language,
        onClick = { showRegionPicker = true },
    )
    if (showRegionPicker) {
        val regionOptions = listOf(
            "nearest" to stringResource(R.string.region_nearest),
            "US" to stringResource(R.string.region_us),
            "GB" to stringResource(R.string.region_gb),
            "DE" to stringResource(R.string.region_de),
            "CH" to stringResource(R.string.region_ch),
            "FR" to stringResource(R.string.region_fr),
            "JP" to stringResource(R.string.region_jp),
            "KR" to stringResource(R.string.region_kr),
            "AU" to stringResource(R.string.region_au),
            "BR" to stringResource(R.string.region_br),
            "CA" to stringResource(R.string.region_ca),
            "SE" to stringResource(R.string.region_se)
        )
        RadioPickerDialog(
            title = stringResource(R.string.content_region),
            options = regionOptions.map { RadioOption(it.first, it.second, it.first) },
            selected = currentRegion,
            onSelect = {
                AppSettings.setContentRegion(it, audioContext)
                showRegionPicker = false
            },
            onDismiss = { showRegionPicker = false }
        )
    }
}

@Composable
private fun DeviceRow(vm: PlaybackViewModel) {
    // Connect to device (Playback)
    SettingRow(
        title = stringResource(R.string.connect_to_device),
        icon = Icons.Rounded.Devices,
        onClick = {
            vm.loadDevices()
            vm.showDevices.value = true
        },
    )
}

@Composable
private fun DownloadSourceRow() {
    val audioContext = LocalContext.current
    // Downloads have their own source: the files can be FLAC while streaming stays on YouTube
    // Music, or the reverse. Spfy is absent because its stream is Widevine and cannot be saved.
    val downloadSource by AppSettings.downloadSource.collectAsState()
    var showDownloadSourcePicker by remember { mutableStateOf(false) }
    SettingRow(
        title = stringResource(R.string.download_source),
        subtitle = if (downloadSource == AppSettings.SOURCE_LOSSLESS) {
            stringResource(R.string.download_source_lossless)
        } else {
            stringResource(R.string.download_source_ytm)
        },
        icon = Icons.Rounded.Download,
        onClick = { showDownloadSourcePicker = true },
    )
    if (showDownloadSourcePicker) {
        RadioPickerDialog(
            title = stringResource(R.string.download_source),
            options = listOf(
                RadioOption(
                    AppSettings.SOURCE_YTM,
                    stringResource(R.string.download_source_ytm),
                    stringResource(R.string.audio_source_ytm_desc)
                ),
                RadioOption(
                    AppSettings.SOURCE_LOSSLESS,
                    stringResource(R.string.download_source_lossless),
                    stringResource(R.string.audio_source_lossless_desc)
                )
            ),
            selected = downloadSource,
            onSelect = { picked ->
                AppSettings.setDownloadSource(picked, audioContext)
                showDownloadSourcePicker = false
            },
            onDismiss = { showDownloadSourcePicker = false }
        )
    }
}

@Composable
private fun AutoSaveRow(accent: Color) {
    val audioContext = LocalContext.current
    val autoSave by AppSettings.autoSaveListened.collectAsState()
    SettingToggleRow(
        title = stringResource(R.string.auto_save_listened),
        checked = autoSave,
        onCheckedChange = { AppSettings.setAutoSaveListened(it, audioContext) },
        accent = accent,
        subtitle = stringResource(R.string.auto_save_listened_desc),
        icon = Icons.Rounded.DownloadForOffline,
    )
}

@Composable
private fun ManageDownloadsRow(vm: PlaybackViewModel) {
    ListItem(
        headlineContent = { Text(stringResource(R.string.manage_downloads), color = SnepilatchWhite) },
        supportingContent = {
            val count by Downloads.downloaded.collectAsState()
            Text(stringResource(R.string.downloads_count, count.size), color = SnepilatchLightGray)
        },
        leadingContent = { Icon(Icons.Rounded.LibraryMusic, null, tint = SnepilatchLightGray) },
        trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SnepilatchLightGray) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable { vm.navigateTo(ch.snepilatch.app.data.Screen.DOWNLOADS) }
    )
}

@Composable
private fun DownloadFolderRow() {
    val audioContext = LocalContext.current
    // Download folder. Downloading stays disabled until one is picked, so this is the entry point.
    val downloadFolder by DownloadFolder.folder.collectAsState()
    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { picked -> if (picked != null) DownloadFolder.setFolder(picked, audioContext) }
    SettingRow(
        title = stringResource(R.string.download_folder),
        subtitle = downloadFolder?.let { readableFolder(it) }
            ?: stringResource(R.string.download_folder_none),
        icon = Icons.Rounded.Folder,
        onClick = { folderPicker.launch(null) },
    )
}

@Composable
private fun StorageLimitRow() {
    val audioContext = LocalContext.current
    // Storage limit: 0 = unlimited.
    val downloadCapGb by AppSettings.downloadCapGb.collectAsState()
    var showCapDialog by remember { mutableStateOf(false) }
    SettingRow(
        title = stringResource(R.string.storage_limit),
        subtitle = if (downloadCapGb > 0f) {
            stringResource(R.string.storage_limit_value, downloadCapGb)
        } else {
            stringResource(R.string.storage_limit_unlimited)
        },
        icon = Icons.Rounded.Storage,
        onClick = { showCapDialog = true },
    )
    if (showCapDialog) {
        TextInputDialog(
            title = stringResource(R.string.storage_limit),
            description = stringResource(R.string.storage_limit_desc),
            initialValue = if (downloadCapGb > 0f) downloadCapGb.toString() else "",
            placeholder = stringResource(R.string.storage_limit_placeholder),
            keyboardType = KeyboardType.Decimal,
            onConfirm = {
                AppSettings.setDownloadCapGb(it.toFloatOrNull()?.coerceAtLeast(0f) ?: 0f, audioContext)
                showCapDialog = false
            },
            onDismiss = { showCapDialog = false }
        )
    }
}

@Composable
private fun StoragePolicyRow() {
    val audioContext = LocalContext.current
    // What to do once the storage limit is hit — only takes effect while a limit is set above.
    val capPolicy by AppSettings.downloadCapPolicy.collectAsState()
    var showCapPolicyPicker by remember { mutableStateOf(false) }
    val capPolicyLabel = if (capPolicy == AppSettings.CAP_POLICY_EVICT_OLDEST) {
        stringResource(R.string.storage_policy_evict)
    } else {
        stringResource(R.string.storage_policy_stop)
    }
    SettingRow(
        title = stringResource(R.string.storage_policy),
        subtitle = capPolicyLabel,
        icon = Icons.Rounded.DeleteSweep,
        onClick = { showCapPolicyPicker = true },
    )
    if (showCapPolicyPicker) {
        RadioPickerDialog(
            title = stringResource(R.string.storage_policy),
            options = listOf(
                RadioOption(
                    AppSettings.CAP_POLICY_STOP,
                    stringResource(R.string.storage_policy_stop),
                    stringResource(R.string.storage_policy_stop_desc)
                ),
                RadioOption(
                    AppSettings.CAP_POLICY_EVICT_OLDEST,
                    stringResource(R.string.storage_policy_evict),
                    stringResource(R.string.storage_policy_evict_desc)
                )
            ),
            selected = capPolicy,
            onSelect = {
                AppSettings.setDownloadCapPolicy(it, audioContext)
                showCapPolicyPicker = false
            },
            onDismiss = { showCapPolicyPicker = false }
        )
    }
}

@Composable
private fun EqualizerRows(vm: PlaybackViewModel) {
    val audioContext = LocalContext.current
    // Equalizer: one choice, because the options exclude each other. Our EQ computes its own
    // input gain from the curve; the headroom attenuation exists only to give an external EQ room
    // to boost into. See AppSettings.eqMode.
    val eqMode by AppSettings.eqMode.collectAsState()
    val headroomDb by AppSettings.eqHeadroomDb.collectAsState()
    var showEqPicker by remember { mutableStateOf(false) }
    val eqModeLabel = when (eqMode) {
        AppSettings.EQ_IN_APP -> stringResource(R.string.eq_in_app)
        AppSettings.EQ_EXTERNAL -> stringResource(R.string.eq_mode_external_at, headroomDb.toInt())
        else -> stringResource(R.string.state_off)
    }
    SettingRow(
        title = stringResource(R.string.equalizer),
        subtitle = eqModeLabel,
        icon = Icons.Rounded.GraphicEq,
        onClick = { showEqPicker = true },
    )
    if (showEqPicker) {
        RadioPickerDialog(
            title = stringResource(R.string.equalizer),
            options = listOf(
                RadioOption(AppSettings.EQ_OFF, stringResource(R.string.state_off), stringResource(R.string.eq_mode_off_desc)),
                RadioOption(AppSettings.EQ_IN_APP, stringResource(R.string.eq_in_app), stringResource(R.string.eq_mode_in_app_desc)),
                RadioOption(AppSettings.EQ_EXTERNAL, stringResource(R.string.eq_mode_external), stringResource(R.string.eq_mode_external_desc))
            ),
            selected = eqMode,
            onSelect = { picked ->
                AppSettings.setEqMode(picked, audioContext)
                showEqPicker = false
                // Straight into the curve editor: picking In-app is a request to shape it.
                if (picked == AppSettings.EQ_IN_APP) vm.navigateTo(ch.snepilatch.app.data.Screen.EQUALIZER)
            },
            onDismiss = { showEqPicker = false }
        )
    }
    // Only External uses this attenuation; the in-app EQ stages its own gain.
    if (eqMode == AppSettings.EQ_EXTERNAL) {
        Slider(
            value = headroomDb,
            onValueChange = { AppSettings.setEqHeadroomDb(it.toInt().toFloat(), audioContext) },
            valueRange = -18f..0f,
            steps = 17,
            modifier = Modifier.padding(horizontal = 24.dp)
        )
    }
}

@Composable
private fun VersionRow() {
    SettingRow(
        title = stringResource(R.string.app_version),
        subtitle = BuildConfig.VERSION_NAME,
        icon = Icons.Rounded.Info,
    )
}

@Composable
private fun UpdateChannelRow() {
    val audioContext = LocalContext.current
    // Update channel
    val updateChannelPref by AppSettings.updateChannel.collectAsState()
    var showUpdateChannelPicker by remember { mutableStateOf(false) }
    val updateChannelLabel = if (updateChannelPref == AppSettings.CHANNEL_NIGHTLY) {
        stringResource(R.string.update_channel_nightly)
    } else {
        stringResource(R.string.update_channel_stable)
    }
    SettingRow(
        title = stringResource(R.string.update_channel),
        subtitle = updateChannelLabel,
        icon = Icons.Rounded.SystemUpdate,
        onClick = { showUpdateChannelPicker = true },
    )
    if (showUpdateChannelPicker) {
        RadioPickerDialog(
            title = stringResource(R.string.update_channel),
            options = listOf(
                RadioOption(
                    AppSettings.CHANNEL_STABLE,
                    stringResource(R.string.update_channel_stable),
                    stringResource(R.string.update_channel_stable_desc)
                ),
                RadioOption(
                    AppSettings.CHANNEL_NIGHTLY,
                    stringResource(R.string.update_channel_nightly),
                    stringResource(R.string.update_channel_nightly_desc)
                )
            ),
            selected = updateChannelPref,
            onSelect = {
                AppSettings.setUpdateChannel(it, audioContext)
                showUpdateChannelPicker = false
            },
            onDismiss = { showUpdateChannelPicker = false }
        )
    }
}

@Composable
private fun CheckForUpdatesRow(accent: Color) {
    // Check for Updates
    val scope = rememberCoroutineScope()
    val updateContext = LocalContext.current
    var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
    var isChecking by remember { mutableStateOf(false) }
    var upToDate by remember { mutableStateOf(false) }

    ListItem(
        headlineContent = { Text(stringResource(R.string.check_for_updates), color = SnepilatchWhite) },
        supportingContent = {
            Text(
                when {
                    isChecking -> stringResource(R.string.checking)
                    upToDate -> stringResource(R.string.up_to_date)
                    else -> stringResource(R.string.tap_to_check)
                },
                color = if (upToDate) accent else SnepilatchLightGray
            )
        },
        leadingContent = {
            if (isChecking) {
                LoadingIndicator(
                    modifier = Modifier.size(24.dp),
                    color = accent
                )
            } else {
                Icon(Icons.Rounded.SystemUpdate, null, tint = SnepilatchLightGray)
            }
        },
        trailingContent = { if (!isChecking) Icon(Icons.Rounded.ChevronRight, null, tint = SnepilatchLightGray) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = Modifier.clickable(enabled = !isChecking) {
            isChecking = true
            upToDate = false
            scope.launch {
                val info = withContext(Dispatchers.IO) {
                    UpdateService.checkForUpdates(updateContext, AppSettings.updateChannelEnum())
                }
                isChecking = false
                if (info != null) {
                    updateInfo = info
                } else {
                    upToDate = true
                }
            }
        }
    )

    if (updateInfo != null) {
        UpdateDialog(
            updateInfo = updateInfo!!,
            onDismiss = { updateInfo = null }
        )
    }
}

@Composable
private fun ReleaseNotesRow() {
    // Release Notes
    var showReleaseNotes by remember { mutableStateOf(false) }

    SettingRow(
        title = stringResource(R.string.release_notes),
        subtitle = stringResource(R.string.view_changelog),
        icon = Icons.Rounded.Description,
        onClick = { showReleaseNotes = true },
    )

    if (showReleaseNotes) {
        ReleaseNotesDialog(onDismiss = { showReleaseNotes = false })
    }
}

@Composable
private fun RelayRow() {
    val audioContext = LocalContext.current
    val relayUrl by RelaySettings.url.collectAsState()
    var showRelayDialog by remember { mutableStateOf(false) }
    SettingRow(
        title = stringResource(R.string.relay_server),
        subtitle = relayUrl,
        icon = Icons.Rounded.Groups,
        onClick = { showRelayDialog = true },
    )
    if (showRelayDialog) {
        TextInputDialog(
            title = stringResource(R.string.relay_server),
            description = stringResource(R.string.relay_server_desc),
            initialValue = relayUrl.takeUnless { it == RelaySettings.DEFAULT_URL }.orEmpty(),
            placeholder = RelaySettings.DEFAULT_URL,
            keyboardType = KeyboardType.Uri,
            onConfirm = {
                RelaySettings.set(it, audioContext)
                showRelayDialog = false
            },
            onDismiss = { showRelayDialog = false }
        )
    }
}

@Composable
private fun DebugLoggingRow() {
    val audioContext = LocalContext.current
    // Debug logging: lets the user point the app at a Loki endpoint to share logs on request,
    // without anything baked into the build. Empty = disabled (the default).
    val lokiEndpoint by AppSettings.lokiEndpoint.collectAsState()
    var showLokiDialog by remember { mutableStateOf(false) }
    SettingRow(
        title = stringResource(R.string.debug_logging),
        subtitle = lokiEndpoint.ifBlank { stringResource(R.string.debug_logging_off) },
        icon = Icons.Rounded.BugReport,
        onClick = { showLokiDialog = true },
    )
    if (showLokiDialog) {
        TextInputDialog(
            title = stringResource(R.string.debug_logging),
            description = stringResource(R.string.debug_logging_desc),
            initialValue = lokiEndpoint,
            placeholder = "https://loki.example.com",
            onConfirm = {
                AppSettings.setLokiEndpoint(it, audioContext)
                showLokiDialog = false
            },
            onDismiss = { showLokiDialog = false }
        )
    }
}

@Composable
private fun SpecialThanks() {
    SettingsSectionHeader(stringResource(R.string.special_thanks))

    ListItem(
        headlineContent = { Text("Cinnabar 🧼", color = SnepilatchWhite) },
        leadingContent = {
            AsyncImage(
                model = "https://cdn.discordapp.com/avatars/823656705350565898/0167b0e2080d52dfa1f0a964a17828bb.webp?size=1024",
                contentDescription = "Cinnabar",
                modifier = Modifier.size(40.dp).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )

    ListItem(
        headlineContent = { Text("MyDrift", color = SnepilatchWhite) },
        leadingContent = {
            AsyncImage(
                model = "https://cdn.discordapp.com/avatars/679006161554505729/2a9c7c72d662df626e9e740cf427c15e.webp?size=1024",
                contentDescription = "MyDrift",
                modifier = Modifier.size(40.dp).clip(CircleShape),
                contentScale = ContentScale.Crop
            )
        },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent)
    )
}
