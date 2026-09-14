@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package ch.snepilatch.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ExitToApp
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import ch.snepilatch.app.BuildConfig
import ch.snepilatch.app.R
import ch.snepilatch.app.data.Screen
import ch.snepilatch.app.logic.download.DownloadFolder
import ch.snepilatch.app.logic.download.Downloads
import ch.snepilatch.app.ui.shared.TightAlertDialog
import ch.snepilatch.app.ui.shared.UpdateDialog
import ch.snepilatch.app.ui.theme.*
import ch.snepilatch.app.logic.shared.UpdateInfo
import ch.snepilatch.app.logic.shared.UpdateService
import ch.snepilatch.app.logic.shared.clearCookies
import ch.snepilatch.app.logic.shared.ThemeController
import ch.snepilatch.app.logic.shared.AppSettings
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ch.snepilatch.app.ui.shared.SettingRow
import ch.snepilatch.app.ui.shared.SettingToggleRow
import ch.snepilatch.app.ui.shared.RadioOption
import ch.snepilatch.app.ui.shared.RadioPickerDialog
import ch.snepilatch.app.ui.shared.SettingsSectionHeader

@Composable
fun AccountScreen(vm: PlaybackViewModel) {
    val account by vm.account.collectAsState()
    val theme by ThemeController.themeColors.collectAsState()
    val animatedPrimary by animateColorAsState(theme.primary, tween(800), label = "accPrimary")

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = LocalBottomOverlayHeight.current.value + 16.dp)
    ) {
        // Header
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(32.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Profile image
            Box(
                Modifier
                    .size(120.dp)
                    .clip(CircleShape)
                    .background(SnepilatchGray),
                contentAlignment = Alignment.Center
            ) {
                if (account.profileImageUrl != null) {
                    AsyncImage(
                        model = account.profileImageUrl,
                        contentDescription = stringResource(R.string.profile_image),
                        modifier = Modifier.size(120.dp).clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(Icons.Rounded.Person, null, tint = SnepilatchLightGray, modifier = Modifier.size(64.dp))
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                account.displayName.ifEmpty { account.username.ifEmpty { stringResource(R.string.loading_dots) } },
                color = SnepilatchWhite,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.followers_playlists, account.followers, account.playlistCount),
                color = SnepilatchLightGray,
                fontSize = 13.sp
            )

            Spacer(Modifier.height(12.dp))

            if (account.isPremium) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = animatedPrimary.copy(alpha = 0.15f)),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(
                        Modifier.padding(horizontal = 14.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Star, null, tint = animatedPrimary, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(stringResource(R.string.premium), color = animatedPrimary, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }

        SettingsSectionHeader(stringResource(R.string.account_section_profile))

        val dots = stringResource(R.string.placeholder_dots)
        val premiumLabel = stringResource(R.string.premium)
        val freeLabel = stringResource(R.string.plan_free)
        SettingRow(
            title = stringResource(R.string.username),
            subtitle = account.displayName.ifEmpty { account.username.ifEmpty { dots } },
            icon = Icons.Rounded.Person,
        )
        SettingRow(
            title = stringResource(R.string.user_id),
            subtitle = account.username.ifEmpty { dots },
            icon = Icons.Rounded.Badge,
        )
        SettingRow(
            title = stringResource(R.string.plan),
            subtitle = if (account.isPremium) premiumLabel else freeLabel,
            icon = Icons.Rounded.CreditCard,
        )

        Spacer(Modifier.height(24.dp))
        SettingsSectionHeader(stringResource(R.string.account_section_playback))

        val audioContext = androidx.compose.ui.platform.LocalContext.current

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

        // Connect to device (Playback)
        SettingRow(
            title = stringResource(R.string.connect_to_device),
            icon = Icons.Rounded.Devices,
            onClick = { vm.loadDevices(); vm.showDevices.value = true },
        )

        Spacer(Modifier.height(24.dp))
        SettingsSectionHeader(stringResource(R.string.downloads))

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

        val autoSave by AppSettings.autoSaveListened.collectAsState()
        SettingToggleRow(
            title = stringResource(R.string.auto_save_listened),
            checked = autoSave,
            onCheckedChange = { AppSettings.setAutoSaveListened(it, audioContext) },
            accent = animatedPrimary,
            subtitle = stringResource(R.string.auto_save_listened_desc),
            icon = Icons.Rounded.DownloadForOffline,
        )

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

        Spacer(Modifier.height(24.dp))
        SettingsSectionHeader(stringResource(R.string.account_section_sound))

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

        Spacer(Modifier.height(24.dp))
        SettingsSectionHeader(stringResource(R.string.account_section_appearance))

        SettingRow(
            title = stringResource(R.string.appearance_settings),
            subtitle = stringResource(R.string.appearance_settings_desc),
            icon = Icons.Rounded.Palette,
            onClick = { vm.navigateTo(Screen.INTERFACE) },
        )

        Spacer(Modifier.height(24.dp))
        SettingsSectionHeader(stringResource(R.string.about))

        SettingRow(
            title = stringResource(R.string.app_version),
            subtitle = BuildConfig.VERSION_NAME,
            icon = Icons.Rounded.Info,
        )

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

        // Check for Updates
        val scope = rememberCoroutineScope()
        val updateContext = androidx.compose.ui.platform.LocalContext.current
        var updateInfo by remember { mutableStateOf<UpdateInfo?>(null) }
        var isChecking by remember { mutableStateOf(false) }
        var upToDate by remember { mutableStateOf(false) }

        ListItem(
            headlineContent = { Text(stringResource(R.string.check_for_updates), color = SnepilatchWhite) },
            supportingContent = { Text(
                when {
                    isChecking -> stringResource(R.string.checking)
                    upToDate -> stringResource(R.string.up_to_date)
                    else -> stringResource(R.string.tap_to_check)
                },
                color = if (upToDate) animatedPrimary else SnepilatchLightGray
            ) },
            leadingContent = {
                if (isChecking) {
                    LoadingIndicator(
                        modifier = Modifier.size(24.dp),
                        color = animatedPrimary
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

        Spacer(Modifier.height(24.dp))
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

        Spacer(Modifier.height(16.dp))

        val context = androidx.compose.ui.platform.LocalContext.current
        ListItem(
            headlineContent = { Text(stringResource(R.string.log_out), color = Color(0xFFE57373)) },
            leadingContent = { Icon(Icons.AutoMirrored.Rounded.ExitToApp, null, tint = Color(0xFFE57373)) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SnepilatchLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable {
                clearCookies(context)
                vm.showLogin()
            }
        )
    }
}

/** Free-text settings dialog, e.g. the Loki debug-logging endpoint. Empty input clears the setting. */
@Composable
private fun TextInputDialog(
    title: String,
    description: String? = null,
    initialValue: String,
    placeholder: String,
    keyboardType: KeyboardType = KeyboardType.Text,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var value by remember { mutableStateOf(initialValue) }
    TightAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = SnepilatchWhite) },
        text = {
            Column {
                if (description != null) {
                    Text(description, color = SnepilatchLightGray, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = { Text(placeholder, color = SnepilatchLightGray.copy(alpha = 0.7f)) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = SnepilatchWhite,
                        unfocusedTextColor = SnepilatchWhite,
                        unfocusedBorderColor = SnepilatchLightGray
                    )
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(value) }) {
                Text(stringResource(R.string.save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel))
            }
        }
    )
}

/** Stands in for Spfy in the audio-source dialog, which selects on a String while the setting is null. */
private const val SOURCE_SPOTIFY_UI = "spotify"

/** Turns a SAF tree uri into something recognisable, e.g. "primary:Music/Snepilatch" -> "Music/Snepilatch". */
internal fun readableFolder(uri: android.net.Uri): String {
    val id = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
    return id?.substringAfter(':')?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty()
}
