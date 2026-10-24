package com.example.ui.screens.settings

import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.R

/**
 * Modal dialog for modifying notification preferences without leaving the current screen.
 */
@Composable
fun NotificationPreferencesDialog(
    onDismiss: () -> Unit,
    viewModel: NotificationPreferencesViewModel = viewModel(factory = com.example.ui.ViewModelFactory())
) {
    val context = LocalContext.current
    val uiState by viewModel.uiState.collectAsState()
    val scrollState = rememberScrollState()

    LaunchedEffect(Unit) {
        viewModel.checkSystemPermission()
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.92f)
                .fillMaxHeight(0.85f),
            shape = RoundedCornerShape(24.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp)
            ) {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.Notifications,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.notif_pref_title),
                            color = MaterialTheme.colorScheme.onSurface,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    IconButton(
                        onClick = onDismiss,
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .testTag("notification_prefs_dialog_close_button")
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = stringResource(R.string.cd_close),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Scrollable Content
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(scrollState)
                ) {
                    if (uiState.isSystemNotificationBlocked) {
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 12.dp)
                                .testTag("notification_dialog_blocked_card"),
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.8f)
                            ),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Column(modifier = Modifier.padding(12.dp)) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Warning,
                                        contentDescription = null,
                                        tint = MaterialTheme.colorScheme.error,
                                        modifier = Modifier.size(20.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = stringResource(R.string.notif_system_blocked_title),
                                        style = MaterialTheme.typography.titleSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onErrorContainer
                                    )
                                }
                                Spacer(modifier = Modifier.height(8.dp))
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
                                    modifier = Modifier.testTag("notification_dialog_open_settings_button")
                                ) {
                                    Text(stringResource(R.string.notif_open_settings), color = MaterialTheme.colorScheme.onError)
                                }
                            }
                        }
                    }

                    // Master Switch
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
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

                    Spacer(modifier = Modifier.height(16.dp))

                    // App & System
                    SettingsSectionHeader(icon = Icons.Outlined.Info, title = stringResource(R.string.notif_pref_section_app))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
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

                    Spacer(modifier = Modifier.height(16.dp))

                    // New Content
                    SettingsSectionHeader(icon = Icons.Outlined.Movie, title = stringResource(R.string.notif_pref_section_content))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
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

                    Spacer(modifier = Modifier.height(16.dp))

                    // New Episodes
                    SettingsSectionHeader(icon = Icons.Outlined.PlayCircleOutline, title = stringResource(R.string.notif_pref_section_episodes))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
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

                    Spacer(modifier = Modifier.height(16.dp))

                    // New Seasons
                    SettingsSectionHeader(icon = Icons.Outlined.CalendarMonth, title = stringResource(R.string.notif_pref_section_seasons))
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
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
                }
            }
        }
    }
}
