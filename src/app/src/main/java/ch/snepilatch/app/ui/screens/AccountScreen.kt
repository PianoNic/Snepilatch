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
import ch.snepilatch.app.download.DownloadFolder
import ch.snepilatch.app.download.Downloads
import ch.snepilatch.app.ui.components.ProfileInfoItem
import ch.snepilatch.app.ui.components.TightAlertDialog
import ch.snepilatch.app.ui.components.UpdateDialog
import ch.snepilatch.app.ui.theme.*
import ch.snepilatch.app.util.UpdateInfo
import ch.snepilatch.app.util.UpdateService
import ch.snepilatch.app.util.clearCookies
import ch.snepilatch.app.viewmodel.ThemeController
import ch.snepilatch.app.viewmodel.AppSettings
import ch.snepilatch.app.viewmodel.PlaybackViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
                    .background(SpfyGray),
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
                    Icon(Icons.Rounded.Person, null, tint = SpfyLightGray, modifier = Modifier.size(64.dp))
                }
            }

            Spacer(Modifier.height(16.dp))

            Text(
                account.displayName.ifEmpty { account.username.ifEmpty { stringResource(R.string.loading_dots) } },
                color = SpfyWhite,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(6.dp))
            Text(
                stringResource(R.string.followers_playlists, account.followers, account.playlistCount),
                color = SpfyLightGray,
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

        AccountSectionHeader(stringResource(R.string.account_section_profile))

        val dots = stringResource(R.string.placeholder_dots)
        val premiumLabel = stringResource(R.string.premium)
        val freeLabel = stringResource(R.string.plan_free)
        ProfileInfoItem(
            stringResource(R.string.username),
            account.displayName.ifEmpty { account.username.ifEmpty { dots } },
            Icons.Rounded.Person
        )
        ProfileInfoItem(
            stringResource(R.string.user_id),
            account.username.ifEmpty { dots },
            Icons.Rounded.Badge
        )
        ProfileInfoItem(
            stringResource(R.string.plan),
            if (account.isPremium) premiumLabel else freeLabel,
            Icons.Rounded.CreditCard
        )

        Spacer(Modifier.height(24.dp))
        AccountSectionHeader(stringResource(R.string.account_section_playback))

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
        ListItem(
            headlineContent = { Text(stringResource(R.string.audio_source), color = SpfyWhite) },
            supportingContent = { Text(sourceLabel, color = SpfyLightGray) },
            leadingContent = { Icon(Icons.Rounded.MusicNote, null, tint = SpfyLightGray) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SpfyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { showSourcePicker = true }
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
                selectedColor = animatedPrimary,
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
        ListItem(
            headlineContent = { Text(stringResource(R.string.content_region), color = SpfyWhite) },
            supportingContent = { Text(regionLabel, color = SpfyLightGray) },
            leadingContent = { Icon(Icons.Rounded.Language, null, tint = SpfyLightGray) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SpfyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { showRegionPicker = true }
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
                selectedColor = animatedPrimary,
                onSelect = {
                    AppSettings.setContentRegion(it, audioContext)
                    showRegionPicker = false
                },
                onDismiss = { showRegionPicker = false }
            )
        }

        // Connect to device (Playback)
        ListItem(
            headlineContent = { Text(stringResource(R.string.connect_to_device), color = SpfyWhite) },
            leadingContent = { Icon(Icons.Rounded.Devices, null, tint = SpfyLightGray) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SpfyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { vm.loadDevices(); vm.showDevices.value = true }
        )

        Spacer(Modifier.height(24.dp))
        AccountSectionHeader(stringResource(R.string.downloads))

        // Downloads have their own source: the files can be FLAC while streaming stays on YouTube
        // Music, or the reverse. Spfy is absent because its stream is Widevine and cannot be saved.
        val downloadSource by AppSettings.downloadSource.collectAsState()
        var showDownloadSourcePicker by remember { mutableStateOf(false) }
        ListItem(
            headlineContent = { Text(stringResource(R.string.download_source), color = SpfyWhite) },
            supportingContent = {
                Text(
                    if (downloadSource == AppSettings.SOURCE_LOSSLESS) {
                        stringResource(R.string.download_source_lossless)
                    } else {
                        stringResource(R.string.download_source_ytm)
                    },
                    color = SpfyLightGray
                )
            },
            leadingContent = { Icon(Icons.Rounded.Download, null, tint = SpfyLightGray) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SpfyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { showDownloadSourcePicker = true }
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
                selectedColor = animatedPrimary,
                onSelect = { picked ->
                    AppSettings.setDownloadSource(picked, audioContext)
                    showDownloadSourcePicker = false
                },
                onDismiss = { showDownloadSourcePicker = false }
            )
        }

        val autoSave by AppSettings.autoSaveListened.collectAsState()
        ListItem(
            headlineContent = { Text(stringResource(R.string.auto_save_listened), color = SpfyWhite) },
            supportingContent = {
                Text(stringResource(R.string.auto_save_listened_desc), color = SpfyLightGray)
            },
            leadingContent = { Icon(Icons.Rounded.DownloadForOffline, null, tint = SpfyLightGray) },
            trailingContent = {
                Switch(
                    checked = autoSave,
                    onCheckedChange = { AppSettings.setAutoSaveListened(it, audioContext) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = animatedPrimary,
                        checkedTrackColor = animatedPrimary.copy(alpha = 0.5f),
                        uncheckedThumbColor = SpfyLightGray,
                        uncheckedTrackColor = SpfyLightGray.copy(alpha = 0.3f)
                    )
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        ListItem(
            headlineContent = { Text(stringResource(R.string.manage_downloads), color = SpfyWhite) },
            supportingContent = {
                val count by Downloads.downloaded.collectAsState()
                Text(stringResource(R.string.downloads_count, count.size), color = SpfyLightGray)
            },
            leadingContent = { Icon(Icons.Rounded.LibraryMusic, null, tint = SpfyLightGray) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SpfyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { vm.navigateTo(ch.snepilatch.app.data.Screen.DOWNLOADS) }
        )

        // Download folder. Downloading stays disabled until one is picked, so this is the entry point.
        val downloadFolder by DownloadFolder.folder.collectAsState()
        val folderPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocumentTree()
        ) { picked -> if (picked != null) DownloadFolder.setFolder(picked, audioContext) }
        ListItem(
            headlineContent = { Text(stringResource(R.string.download_folder), color = SpfyWhite) },
            supportingContent = {
                Text(
                    downloadFolder?.let { readableFolder(it) }
                        ?: stringResource(R.string.download_folder_none),
                    color = SpfyLightGray
                )
            },
            leadingContent = { Icon(Icons.Rounded.Folder, null, tint = SpfyLightGray) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SpfyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { folderPicker.launch(null) }
        )

        // Storage limit: 0 = unlimited.
        val downloadCapGb by AppSettings.downloadCapGb.collectAsState()
        var showCapDialog by remember { mutableStateOf(false) }
        ListItem(
            headlineContent = { Text(stringResource(R.string.storage_limit), color = SpfyWhite) },
            supportingContent = {
                Text(
                    if (downloadCapGb > 0f) {
                        stringResource(R.string.storage_limit_value, downloadCapGb)
                    } else {
                        stringResource(R.string.storage_limit_unlimited)
                    },
                    color = SpfyLightGray
                )
            },
            leadingContent = { Icon(Icons.Rounded.Storage, null, tint = SpfyLightGray) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SpfyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { showCapDialog = true }
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
        ListItem(
            headlineContent = { Text(stringResource(R.string.storage_policy), color = SpfyWhite) },
            supportingContent = { Text(capPolicyLabel, color = SpfyLightGray) },
            leadingContent = { Icon(Icons.Rounded.DeleteSweep, null, tint = SpfyLightGray) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SpfyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { showCapPolicyPicker = true }
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
                selectedColor = animatedPrimary,
                onSelect = {
                    AppSettings.setDownloadCapPolicy(it, audioContext)
                    showCapPolicyPicker = false
                },
                onDismiss = { showCapPolicyPicker = false }
            )
        }

        Spacer(Modifier.height(24.dp))
        AccountSectionHeader(stringResource(R.string.account_section_sound))

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
        ListItem(
            headlineContent = { Text(stringResource(R.string.equalizer), color = SpfyWhite) },
            supportingContent = { Text(eqModeLabel, color = SpfyLightGray) },
            leadingContent = { Icon(Icons.Rounded.GraphicEq, null, tint = SpfyLightGray) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SpfyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { showEqPicker = true }
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
                selectedColor = animatedPrimary,
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
                colors = SliderDefaults.colors(thumbColor = animatedPrimary, activeTrackColor = animatedPrimary),
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }

        Spacer(Modifier.height(24.dp))
        AccountSectionHeader(stringResource(R.string.account_section_appearance))

        // Language picker
        val appLanguage by AppSettings.appLanguage.collectAsState()
        var showLanguagePicker by remember { mutableStateOf(false) }
        val systemDefaultLabel = stringResource(R.string.language_system_default)
        val languages = remember(systemDefaultLabel) {
            listOf(
                "system" to systemDefaultLabel,
                "en" to "English",
                "de" to "Deutsch",
                "ru" to "Русский",
                "gsw" to "Schwiizerdütsch"
            )
        }
        val currentLanguageLabel = languages.find { it.first == appLanguage }?.second ?: systemDefaultLabel
        ListItem(
            headlineContent = { Text(stringResource(R.string.language), color = SpfyWhite) },
            supportingContent = { Text(currentLanguageLabel, color = SpfyLightGray) },
            leadingContent = { Icon(Icons.Rounded.Language, null, tint = SpfyLightGray) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SpfyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { showLanguagePicker = true }
        )
        if (showLanguagePicker) {
            RadioPickerDialog(
                title = stringResource(R.string.language),
                options = languages.map { RadioOption(it.first, it.second) },
                selected = appLanguage,
                selectedColor = animatedPrimary,
                onSelect = {
                    AppSettings.setAppLanguage(it, audioContext)
                    showLanguagePicker = false
                },
                onDismiss = { showLanguagePicker = false }
            )
        }

        // Lyrics animation direction (Appearance)
        val lyricsAnim by AppSettings.lyricsAnimDirection.collectAsState()
        var showLyricsPicker by remember { mutableStateOf(false) }
        val lyricsLabel = if (lyricsAnim == "horizontal") stringResource(R.string.lyrics_horizontal) else stringResource(R.string.lyrics_vertical)
        ListItem(
            headlineContent = { Text(stringResource(R.string.lyrics_animation), color = SpfyWhite) },
            supportingContent = { Text(lyricsLabel, color = SpfyLightGray) },
            leadingContent = { Icon(Icons.Rounded.MusicNote, null, tint = SpfyLightGray) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SpfyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { showLyricsPicker = true }
        )
        if (showLyricsPicker) {
            RadioPickerDialog(
                title = stringResource(R.string.lyrics_animation),
                description = stringResource(R.string.lyrics_anim_desc),
                options = listOf(
                    RadioOption("vertical", stringResource(R.string.lyrics_vertical)),
                    RadioOption("horizontal", stringResource(R.string.lyrics_horizontal))
                ),
                selected = lyricsAnim,
                selectedColor = animatedPrimary,
                onSelect = {
                    AppSettings.setLyricsAnimDirection(it, audioContext)
                    showLyricsPicker = false
                },
                onDismiss = { showLyricsPicker = false }
            )
        }

        // Canvas background
        val canvasOn by AppSettings.canvasEnabled.collectAsState()
        ListItem(
            headlineContent = { Text(stringResource(R.string.canvas_background), color = SpfyWhite) },
            supportingContent = { Text(
                if (canvasOn) stringResource(R.string.canvas_on) else stringResource(R.string.canvas_off),
                color = SpfyLightGray
            ) },
            leadingContent = { Icon(Icons.Rounded.PlayCircle, null, tint = SpfyLightGray) },
            trailingContent = {
                Switch(
                    checked = canvasOn,
                    onCheckedChange = { vm.setCanvasEnabled(it, audioContext) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = animatedPrimary,
                        checkedTrackColor = animatedPrimary.copy(alpha = 0.5f),
                        uncheckedThumbColor = SpfyLightGray,
                        uncheckedTrackColor = SpfyLightGray.copy(alpha = 0.3f)
                    )
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        // Player background style: album-colour gradient vs. the fluid Kawarp album-art warp.
        val gradientBg by AppSettings.playerGradientBg.collectAsState()
        ListItem(
            headlineContent = { Text(stringResource(R.string.gradient_background), color = SpfyWhite) },
            supportingContent = {
                Text(
                    stringResource(if (gradientBg) R.string.gradient_bg_on else R.string.gradient_bg_off),
                    color = SpfyLightGray
                )
            },
            leadingContent = { Icon(Icons.Rounded.Gradient, null, tint = SpfyLightGray) },
            trailingContent = {
                Switch(
                    checked = gradientBg,
                    onCheckedChange = { AppSettings.setPlayerGradientBg(it, audioContext) },
                    colors = SwitchDefaults.colors(
                        checkedThumbColor = animatedPrimary,
                        checkedTrackColor = animatedPrimary.copy(alpha = 0.5f),
                        uncheckedThumbColor = SpfyLightGray,
                        uncheckedTrackColor = SpfyLightGray.copy(alpha = 0.3f)
                    )
                )
            },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        Spacer(Modifier.height(24.dp))
        AccountSectionHeader(stringResource(R.string.account_section_notifications))

        // Notification button options
        val notifLikeLabel = stringResource(R.string.notif_like)
        val notifShuffleLabel = stringResource(R.string.notif_shuffle)
        val notifRepeatLabel = stringResource(R.string.notif_repeat)
        val notifLikeDesc = stringResource(R.string.notif_like_short_desc)
        val notifShuffleDesc = stringResource(R.string.notif_shuffle_desc)
        val notifRepeatDesc = stringResource(R.string.notif_repeat_desc)
        val buttonOptions = remember(
            notifLikeLabel, notifShuffleLabel, notifRepeatLabel,
            notifLikeDesc, notifShuffleDesc, notifRepeatDesc
        ) {
            listOf(
                "like" to notifLikeLabel to notifLikeDesc,
                "shuffle" to notifShuffleLabel to notifShuffleDesc,
                "repeat" to notifRepeatLabel to notifRepeatDesc
            )
        }
        fun buttonLabel(type: String) = when (type) {
            "like" -> notifLikeLabel
            "shuffle" -> notifShuffleLabel
            "repeat" -> notifRepeatLabel
            else -> type
        }
        val notifRadioOptions = buttonOptions.map { (pair, desc) ->
            RadioOption(pair.first, pair.second, desc)
        }

        // Left notification button
        val leftButton by AppSettings.notificationLeftButton.collectAsState()
        var showLeftPicker by remember { mutableStateOf(false) }
        ListItem(
            headlineContent = { Text(stringResource(R.string.notification_left_button), color = SpfyWhite) },
            supportingContent = { Text(buttonLabel(leftButton), color = SpfyLightGray) },
            leadingContent = { Icon(Icons.Rounded.Notifications, null, tint = SpfyLightGray) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SpfyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { showLeftPicker = true }
        )
        if (showLeftPicker) {
            RadioPickerDialog(
                title = stringResource(R.string.notification_button_left),
                options = notifRadioOptions,
                selected = leftButton,
                selectedColor = animatedPrimary,
                onSelect = {
                    AppSettings.setNotificationLeftButton(it, audioContext)
                    showLeftPicker = false
                },
                onDismiss = { showLeftPicker = false }
            )
        }

        // Right notification button
        val rightButton by AppSettings.notificationRightButton.collectAsState()
        var showRightPicker by remember { mutableStateOf(false) }
        ListItem(
            headlineContent = { Text(stringResource(R.string.notification_right_button), color = SpfyWhite) },
            supportingContent = { Text(buttonLabel(rightButton), color = SpfyLightGray) },
            leadingContent = { Icon(Icons.Rounded.Notifications, null, tint = SpfyLightGray) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SpfyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { showRightPicker = true }
        )
        if (showRightPicker) {
            RadioPickerDialog(
                title = stringResource(R.string.notification_button_right),
                options = notifRadioOptions,
                selected = rightButton,
                selectedColor = animatedPrimary,
                onSelect = {
                    AppSettings.setNotificationRightButton(it, audioContext)
                    showRightPicker = false
                },
                onDismiss = { showRightPicker = false }
            )
        }

        Spacer(Modifier.height(24.dp))
        AccountSectionHeader(stringResource(R.string.about))

        ListItem(
            headlineContent = { Text(stringResource(R.string.app_version), color = SpfyWhite) },
            supportingContent = { Text(BuildConfig.VERSION_NAME, color = SpfyLightGray) },
            leadingContent = { Icon(Icons.Rounded.Info, null, tint = SpfyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent)
        )

        // Update channel
        val updateChannelPref by AppSettings.updateChannel.collectAsState()
        var showUpdateChannelPicker by remember { mutableStateOf(false) }
        val updateChannelLabel = if (updateChannelPref == AppSettings.CHANNEL_NIGHTLY) {
            stringResource(R.string.update_channel_nightly)
        } else {
            stringResource(R.string.update_channel_stable)
        }
        ListItem(
            headlineContent = { Text(stringResource(R.string.update_channel), color = SpfyWhite) },
            supportingContent = { Text(updateChannelLabel, color = SpfyLightGray) },
            leadingContent = { Icon(Icons.Rounded.SystemUpdate, null, tint = SpfyLightGray) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SpfyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { showUpdateChannelPicker = true }
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
                selectedColor = animatedPrimary,
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
            headlineContent = { Text(stringResource(R.string.check_for_updates), color = SpfyWhite) },
            supportingContent = { Text(
                when {
                    isChecking -> stringResource(R.string.checking)
                    upToDate -> stringResource(R.string.up_to_date)
                    else -> stringResource(R.string.tap_to_check)
                },
                color = if (upToDate) animatedPrimary else SpfyLightGray
            ) },
            leadingContent = {
                if (isChecking) {
                    LoadingIndicator(
                        modifier = Modifier.size(24.dp),
                        color = animatedPrimary
                    )
                } else {
                    Icon(Icons.Rounded.SystemUpdate, null, tint = SpfyLightGray)
                }
            },
            trailingContent = { if (!isChecking) Icon(Icons.Rounded.ChevronRight, null, tint = SpfyLightGray) },
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

        ListItem(
            headlineContent = { Text(stringResource(R.string.release_notes), color = SpfyWhite) },
            supportingContent = { Text(stringResource(R.string.view_changelog), color = SpfyLightGray) },
            leadingContent = { Icon(Icons.Rounded.Description, null, tint = SpfyLightGray) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SpfyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { showReleaseNotes = true }
        )

        if (showReleaseNotes) {
            ReleaseNotesDialog(onDismiss = { showReleaseNotes = false })
        }

        // Debug logging: lets the user point the app at a Loki endpoint to share logs on request,
        // without anything baked into the build. Empty = disabled (the default).
        val lokiEndpoint by AppSettings.lokiEndpoint.collectAsState()
        var showLokiDialog by remember { mutableStateOf(false) }
        ListItem(
            headlineContent = { Text(stringResource(R.string.debug_logging), color = SpfyWhite) },
            supportingContent = {
                Text(
                    lokiEndpoint.ifBlank { stringResource(R.string.debug_logging_off) },
                    color = SpfyLightGray
                )
            },
            leadingContent = { Icon(Icons.Rounded.BugReport, null, tint = SpfyLightGray) },
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SpfyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable { showLokiDialog = true }
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
        AccountSectionHeader(stringResource(R.string.special_thanks))

        ListItem(
            headlineContent = { Text("Cinnabar 🧼", color = SpfyWhite) },
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
            headlineContent = { Text("MyDrift", color = SpfyWhite) },
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
            trailingContent = { Icon(Icons.Rounded.ChevronRight, null, tint = SpfyLightGray) },
            colors = ListItemDefaults.colors(containerColor = Color.Transparent),
            modifier = Modifier.clickable {
                clearCookies(context)
                vm.showLogin()
            }
        )
    }
}

@Composable
private fun AccountSectionHeader(title: String) {
    HorizontalDivider(color = SpfyGray, modifier = Modifier.padding(horizontal = 16.dp))
    Text(
        title,
        color = SpfyWhite,
        fontSize = 16.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp)
    )
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
    val confirmColor = MaterialTheme.colorScheme.primary
    TightAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = SpfyWhite) },
        text = {
            Column {
                if (description != null) {
                    Text(description, color = SpfyLightGray, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                }
                OutlinedTextField(
                    value = value,
                    onValueChange = { value = it },
                    placeholder = { Text(placeholder, color = SpfyLightGray.copy(alpha = 0.7f)) },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = keyboardType),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedTextColor = SpfyWhite,
                        unfocusedTextColor = SpfyWhite,
                        cursorColor = confirmColor,
                        focusedBorderColor = confirmColor,
                        unfocusedBorderColor = SpfyLightGray
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

private data class RadioOption(val value: String, val label: String, val supportingText: String? = null)

/** Single radio-select settings dialog shared by the Language, Lyrics, Region and notification pickers. */
@Composable
private fun RadioPickerDialog(
    title: String,
    description: String? = null,
    options: List<RadioOption>,
    selected: String,
    selectedColor: Color,
    onSelect: (String) -> Unit,
    onDismiss: () -> Unit
) {
    TightAlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, color = SpfyWhite) },
        text = {
            Column {
                if (description != null) {
                    Text(description, color = SpfyLightGray, fontSize = 13.sp)
                    Spacer(Modifier.height(12.dp))
                }
                options.forEach { opt ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(opt.value) }
                            .padding(vertical = 12.dp, horizontal = 4.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selected == opt.value,
                            onClick = { onSelect(opt.value) },
                            colors = RadioButtonDefaults.colors(
                                selectedColor = selectedColor,
                                unselectedColor = SpfyLightGray
                            )
                        )
                        Spacer(Modifier.width(8.dp))
                        if (opt.supportingText != null) {
                            Column {
                                Text(opt.label, color = SpfyWhite, fontSize = 15.sp)
                                Text(opt.supportingText, color = SpfyLightGray, fontSize = 12.sp)
                            }
                        } else {
                            Text(opt.label, color = SpfyWhite, fontSize = 15.sp)
                        }
                    }
                }
            }
        },
        containerColor = SpfyGray,
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.cancel), color = SpfyLightGray)
            }
        }
    )
}

/** Turns a SAF tree uri into something recognisable, e.g. "primary:Music/Snepilatch" -> "Music/Snepilatch". */
internal fun readableFolder(uri: android.net.Uri): String {
    val id = runCatching { android.provider.DocumentsContract.getTreeDocumentId(uri) }.getOrNull()
    return id?.substringAfter(':')?.takeIf { it.isNotBlank() } ?: uri.lastPathSegment.orEmpty()
}
