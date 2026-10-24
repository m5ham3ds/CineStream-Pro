package com.example.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R

/**
 * Screen providing granular user controls for all notification types.
 *
 * Implements Phase 4B specifications:
 * - Master Switch: Allow Notifications
 * - App & System: Announcements, App Updates, Maintenance
 * - New Content: New Movies, New TV Series, New Anime
 * - New Episodes: New TV Episodes, New Anime Episodes
 * - New Seasons: New TV Seasons, New Anime Seasons
 * - OS Permission Detection: Prominently warns when Android system notifications are blocked
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NotificationPreferencesScreen(
    onBack: () -> Unit,
    viewModel: NotificationPreferencesViewModel = viewModel(factory = com.example.ui.ViewModelFactory())
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    // Re-verify Android OS notification permission on app resume
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.checkSystemPermission()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        stringResource(R.string.notif_pref_title),
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onBack,
                        modifier = Modifier.testTag("notification_prefs_back_button")
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_back),
                            tint = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        },
        containerColor = MaterialTheme.colorScheme.background
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .verticalScroll(scrollState)
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // Android OS Permission Blocked Banner
            if (uiState.isSystemNotificationBlocked) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 16.dp)
                        .testTag("notification_system_blocked_card"),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
                    ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                Icons.Default.Warning,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = stringResource(R.string.notif_system_blocked_title),
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            text = stringResource(R.string.notif_system_blocked_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                    Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).apply {
                                        putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                                    }
                                } else {
                                    Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                                        data = Uri.fromParts("package", context.packageName, null)
                                    }
                                }
                                try {
                                    context.startActivity(intent)
                                } catch (_: Exception) {}
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.testTag("notification_open_settings_button")
                        ) {
                            Text(
                                stringResource(R.string.notif_open_settings),
                                color = MaterialTheme.colorScheme.onError
                            )
                        }
                    }
                }
            }

            // 1. MASTER CONTROL
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            ) {
                NotificationToggleItem(
                    title = stringResource(R.string.notif_pref_master_title),
                    subtitle = stringResource(R.string.notif_pref_master_desc),
                    checked = uiState.preferences.notificationsEnabled,
                    enabled = true,
                    isLast = true,
                    icon = Icons.Outlined.NotificationsActive,
                    testTag = "switch_master_notifications",
                    onCheckedChange = { viewModel.toggleMaster(it) }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 2. APP & SYSTEM NOTIFICATIONS
            SettingsSectionHeader(
                icon = Icons.Outlined.Notifications,
                title = stringResource(R.string.notif_pref_section_app)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            ) {
                NotificationToggleItem(
                    title = stringResource(R.string.notif_pref_announcements),
                    subtitle = stringResource(R.string.notif_pref_announcements_desc),
                    checked = uiState.preferences.announcementsEnabled,
                    enabled = true,
                    isLast = false,
                    icon = Icons.Outlined.Campaign,
                    testTag = "switch_announcements",
                    onCheckedChange = { viewModel.toggleAnnouncements(it) }
                )
                NotificationToggleItem(
                    title = stringResource(R.string.notif_pref_app_updates),
                    subtitle = stringResource(R.string.notif_pref_app_updates_desc),
                    checked = uiState.preferences.appUpdatesEnabled,
                    enabled = true,
                    isLast = false,
                    icon = Icons.Outlined.SystemUpdate,
                    testTag = "switch_app_updates",
                    onCheckedChange = { viewModel.toggleAppUpdates(it) }
                )
                NotificationToggleItem(
                    title = stringResource(R.string.notif_pref_maintenance),
                    subtitle = stringResource(R.string.notif_pref_maintenance_desc),
                    checked = uiState.preferences.maintenanceEnabled,
                    enabled = true,
                    isLast = true,
                    icon = Icons.Outlined.Build,
                    testTag = "switch_maintenance",
                    onCheckedChange = { viewModel.toggleMaintenance(it) }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 3. NEW CONTENT
            SettingsSectionHeader(
                icon = Icons.Outlined.Movie,
                title = stringResource(R.string.notif_pref_section_content)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            ) {
                NotificationToggleItem(
                    title = stringResource(R.string.notif_pref_new_movies),
                    subtitle = stringResource(R.string.notif_pref_new_movies_desc),
                    checked = uiState.preferences.newMoviesEnabled,
                    enabled = true,
                    isLast = false,
                    icon = Icons.Outlined.LocalMovies,
                    testTag = "switch_new_movies",
                    onCheckedChange = { viewModel.toggleNewMovies(it) }
                )
                NotificationToggleItem(
                    title = stringResource(R.string.notif_pref_new_tv_series),
                    subtitle = stringResource(R.string.notif_pref_new_tv_series_desc),
                    checked = uiState.preferences.newTvSeriesEnabled,
                    enabled = true,
                    isLast = false,
                    icon = Icons.Outlined.Tv,
                    testTag = "switch_new_tv_series",
                    onCheckedChange = { viewModel.toggleNewTvSeries(it) }
                )
                NotificationToggleItem(
                    title = stringResource(R.string.notif_pref_new_anime),
                    subtitle = stringResource(R.string.notif_pref_new_anime_desc),
                    checked = uiState.preferences.newAnimeEnabled,
                    enabled = true,
                    isLast = true,
                    icon = Icons.Outlined.AutoAwesome,
                    testTag = "switch_new_anime",
                    onCheckedChange = { viewModel.toggleNewAnime(it) }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 4. NEW EPISODES
            SettingsSectionHeader(
                icon = Icons.Outlined.PlayCircleOutline,
                title = stringResource(R.string.notif_pref_section_episodes)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            ) {
                NotificationToggleItem(
                    title = stringResource(R.string.notif_pref_new_tv_episodes),
                    subtitle = stringResource(R.string.notif_pref_new_tv_episodes_desc),
                    checked = uiState.preferences.newTvEpisodesEnabled,
                    enabled = true,
                    isLast = false,
                    icon = Icons.Outlined.VideoLibrary,
                    testTag = "switch_new_tv_episodes",
                    onCheckedChange = { viewModel.toggleNewTvEpisodes(it) }
                )
                NotificationToggleItem(
                    title = stringResource(R.string.notif_pref_new_anime_episodes),
                    subtitle = stringResource(R.string.notif_pref_new_anime_episodes_desc),
                    checked = uiState.preferences.newAnimeEpisodesEnabled,
                    enabled = true,
                    isLast = true,
                    icon = Icons.Outlined.SmartDisplay,
                    testTag = "switch_new_anime_episodes",
                    onCheckedChange = { viewModel.toggleNewAnimeEpisodes(it) }
                )
            }

            Spacer(modifier = Modifier.height(20.dp))

            // 5. NEW SEASONS
            SettingsSectionHeader(
                icon = Icons.Outlined.CalendarMonth,
                title = stringResource(R.string.notif_pref_section_seasons)
            )
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            ) {
                NotificationToggleItem(
                    title = stringResource(R.string.notif_pref_new_tv_seasons),
                    subtitle = stringResource(R.string.notif_pref_new_tv_seasons_desc),
                    checked = uiState.preferences.newTvSeasonsEnabled,
                    enabled = true,
                    isLast = false,
                    icon = Icons.Outlined.DateRange,
                    testTag = "switch_new_tv_seasons",
                    onCheckedChange = { viewModel.toggleNewTvSeasons(it) }
                )
                NotificationToggleItem(
                    title = stringResource(R.string.notif_pref_new_anime_seasons),
                    subtitle = stringResource(R.string.notif_pref_new_anime_seasons_desc),
                    checked = uiState.preferences.newAnimeSeasonsEnabled,
                    enabled = true,
                    isLast = true,
                    icon = Icons.Outlined.EventRepeat,
                    testTag = "switch_new_anime_seasons",
                    onCheckedChange = { viewModel.toggleNewAnimeSeasons(it) }
                )
            }

            Spacer(modifier = Modifier.height(32.dp))
        }
    }
}

@Composable
fun NotificationToggleItem(
    title: String,
    subtitle: String? = null,
    checked: Boolean,
    enabled: Boolean = true,
    isLast: Boolean = false,
    icon: ImageVector? = null,
    testTag: String? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        val rowModifier = Modifier
            .fillMaxWidth()
            .then(if (testTag != null) Modifier.testTag(testTag) else Modifier)
            .clickable(enabled = enabled) { onCheckedChange(!checked) }
            .padding(horizontal = 16.dp, vertical = 14.dp)

        Row(
            modifier = rowModifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                modifier = Modifier.weight(1f),
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (icon != null) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            icon,
                            contentDescription = null,
                            tint = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = title,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
                    )
                    if (!subtitle.isNullOrBlank()) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = subtitle,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (enabled) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.width(16.dp))
            Switch(
                checked = checked,
                onCheckedChange = onCheckedChange,
                enabled = enabled,
                modifier = if (testTag != null) Modifier.testTag("${testTag}_switch") else Modifier,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = MaterialTheme.colorScheme.onPrimary,
                    checkedTrackColor = MaterialTheme.colorScheme.primary,
                    uncheckedThumbColor = MaterialTheme.colorScheme.outline,
                    uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                )
            )
        }
        if (!isLast) {
            HorizontalDivider(
                color = MaterialTheme.colorScheme.surfaceVariant,
                thickness = 1.dp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
    }
}
