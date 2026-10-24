package com.example.ui.screens.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll

import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Movie
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.RadioButtonChecked
import androidx.compose.material.icons.filled.SignalCellular4Bar
import androidx.compose.material.icons.outlined.FileDownload

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.outlined.ColorLens
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Language
import androidx.compose.material.icons.outlined.LightMode
import androidx.compose.material.icons.outlined.Palette
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.PlayCircleOutline
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Cached
import androidx.compose.material.icons.outlined.BugReport
import androidx.compose.material.icons.outlined.Security
import com.example.ui.components.AuthDiagnosticDialog
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.data.repository.UserPreferencesRepository
import com.example.utils.CacheManagementHelper
import com.example.ui.theme.PrimaryBlue
import com.example.ui.theme.PrimaryGreen
import com.example.ui.theme.PrimaryPurple
import com.example.ui.theme.PrimaryRed
import com.example.ui.theme.PrimaryYellow
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onNavigateToNotificationPreferences: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val userPrefs = remember { UserPreferencesRepository(context) }
    val coroutineScope = rememberCoroutineScope()
    
    val themeMode by userPrefs.themeMode.collectAsState(initial = 0)
    val primaryColor by userPrefs.primaryColor.collectAsState(initial = 0)
    val appLanguage by userPrefs.appLanguage.collectAsState(initial = "system")
    val startScreen by userPrefs.startScreen.collectAsState(initial = "home")
    
    val scrollState = rememberScrollState()
    
    // Bottom Sheets State
    var showLanguageSheet by remember { mutableStateOf(false) }
    
    var showStartScreenSheet by remember { mutableStateOf(false) }
    var showDownloadSettingsDialog by remember { mutableStateOf(false) }
    var showPlaybackSettingsDialog by remember { mutableStateOf(false) }
    var showNotificationSettingsDialog by remember { mutableStateOf(false) }
    var showClearCacheDialog by remember { mutableStateOf(false) }
    var isClearingCache by remember { mutableStateOf(false) }
    var showAuthDiagnosticDialog by remember { mutableStateOf(false) }
    
    val maxConcurrentDownloads by userPrefs.maxConcurrentDownloads.collectAsState(initial = 3)
    val maxSegments by userPrefs.maxSegments.collectAsState(initial = 8)
    val downloadNetwork by userPrefs.downloadNetwork.collectAsState(initial = 0)
    val playbackSeekDuration by userPrefs.playbackSeekDuration.collectAsState(initial = 10)
    val playbackControlsTimeout by userPrefs.playbackControlsTimeout.collectAsState(initial = 10)

    
    var pendingThemeMode by remember { mutableStateOf<Int?>(null) }
    var pendingPrimaryColor by remember { mutableStateOf<Int?>(null) }
    var pendingLanguage by remember { mutableStateOf<String?>(null) }

    val currentThemeName = when(themeMode) {
        1 -> stringResource(R.string.light)
        2 -> stringResource(R.string.dark)
        else -> stringResource(R.string.system)
    }

    val currentColorName = when(primaryColor) {
        1 -> stringResource(R.string.blue)
        2 -> stringResource(R.string.green)
        3 -> stringResource(R.string.purple)
        4 -> stringResource(R.string.yellow)
        else -> stringResource(R.string.red)
    }

    val currentLanguageName = when(appLanguage) {
        "en" -> stringResource(R.string.english)
        "ar" -> stringResource(R.string.arabic)
        else -> stringResource(R.string.system)
    }

    val currentStartScreenName = when(startScreen) {
        "search" -> stringResource(R.string.search)
        "downloads" -> stringResource(R.string.downloads)
        "settings" -> stringResource(R.string.settings)
        "movies" -> stringResource(R.string.movies)
        "series" -> stringResource(R.string.series)
        "anime" -> stringResource(R.string.anime)
        "library" -> stringResource(R.string.library)
        "profile" -> stringResource(R.string.profile)
        else -> stringResource(R.string.home)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        // Appearance Section
        SettingsSectionHeader(icon = Icons.Outlined.Palette, title = stringResource(R.string.appearance))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
        ) {
            // Theme Mode
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(40.dp).clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(stringResource(R.string.theme), color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(currentThemeName, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                
                // Theme selector
                Row(
                    modifier = Modifier
                        .clip(RoundedCornerShape(percent = 50))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(modifier = Modifier.clip(CircleShape).background(if(themeMode == 0) MaterialTheme.colorScheme.primary else Color.Transparent).clickable { pendingThemeMode = 0 }.padding(8.dp)) {
                        Icon(Icons.Outlined.Settings, contentDescription = stringResource(R.string.system), tint = if(themeMode == 0) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(modifier = Modifier.clip(CircleShape).background(if(themeMode == 1) MaterialTheme.colorScheme.primary else Color.Transparent).clickable { pendingThemeMode = 1 }.padding(8.dp)) {
                        Icon(Icons.Outlined.LightMode, contentDescription = stringResource(R.string.light), tint = if(themeMode == 1) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Box(modifier = Modifier.clip(CircleShape).background(if(themeMode == 2) MaterialTheme.colorScheme.primary else Color.Transparent).clickable { pendingThemeMode = 2 }.padding(8.dp)) {
                        Icon(Icons.Outlined.DarkMode, contentDescription = stringResource(R.string.dark), tint = if(themeMode == 2) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(16.dp))
                    }
                }
            }
            
            Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.surfaceVariant))
            
            // Accent Color Row
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(40.dp).clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
                        Icon(Icons.Outlined.ColorLens, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                    }
                    Spacer(modifier = Modifier.width(16.dp))
                    Column {
                        Text(stringResource(R.string.accent_color), color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                        Text(currentColorName, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
                    }
                }
                
                // Color selector
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ColorCircle(color = PrimaryRed, isSelected = primaryColor == 0, onClick = { pendingPrimaryColor = 0 })
                    ColorCircle(color = PrimaryBlue, isSelected = primaryColor == 1, onClick = { pendingPrimaryColor = 1 })
                    ColorCircle(color = PrimaryGreen, isSelected = primaryColor == 2, onClick = { pendingPrimaryColor = 2 })
                    ColorCircle(color = PrimaryPurple, isSelected = primaryColor == 3, onClick = { pendingPrimaryColor = 3 })
                    ColorCircle(color = PrimaryYellow, isSelected = primaryColor == 4, onClick = { pendingPrimaryColor = 4 })
                }
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // General Section
        SettingsSectionHeader(icon = Icons.Outlined.Settings, title = stringResource(R.string.general))
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(MaterialTheme.colorScheme.surface)
                .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
        ) {
            SettingsListItem(
                icon = Icons.Outlined.Language, 
                title = stringResource(R.string.language), 
                subtitle = currentLanguageName, 
                isLast = false,
                onClick = { showLanguageSheet = true }
            )
            SettingsListItem(
                icon = Icons.Outlined.Home, 
                title = stringResource(R.string.start_screen), 
                subtitle = currentStartScreenName, 
                isLast = true,
                onClick = { showStartScreenSheet = true }
            )
        }
        
        
        Spacer(modifier = Modifier.height(32.dp))
        SettingsSectionHeader(icon = Icons.Outlined.Settings, title = stringResource(R.string.advanced_prefs))
        Column(modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
            SettingsListItem(
                icon = Icons.Outlined.PlayCircleOutline,
                title = stringResource(R.string.playback_settings),
                subtitle = "${playbackSeekDuration}s ${stringResource(R.string.seek_duration)} • ${playbackControlsTimeout}s ${stringResource(R.string.controls_timeout)}",
                isLast = false
            ) { showPlaybackSettingsDialog = true }
            SettingsListItem(Icons.Outlined.Download, stringResource(R.string.downloads_settings), stringResource(R.string.downloads_desc), false) { showDownloadSettingsDialog = true }
            SettingsListItem(
                icon = Icons.Outlined.Notifications,
                title = stringResource(R.string.notifications_settings),
                subtitle = stringResource(R.string.notifications_desc),
                isLast = false,
                modifier = Modifier.testTag("settings_notifications_item")
            ) {
                if (onNavigateToNotificationPreferences != null) {
                    onNavigateToNotificationPreferences()
                } else {
                    showNotificationSettingsDialog = true
                }
            }
            SettingsListItem(
                icon = Icons.Outlined.Cached,
                title = stringResource(R.string.clear_cache),
                subtitle = stringResource(R.string.clear_cache_desc),
                isLast = true
            ) { showClearCacheDialog = true }
        }

        if (com.example.BuildConfig.DEBUG) {
            Spacer(modifier = Modifier.height(32.dp))
            SettingsSectionHeader(icon = Icons.Outlined.BugReport, title = stringResource(R.string.developer_diagnostics))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surface)
                    .border(1.dp, MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
            ) {
                SettingsListItem(
                    icon = Icons.Outlined.Security,
                    title = stringResource(R.string.auth_diagnostic_title),
                    subtitle = stringResource(R.string.auth_diagnostic_desc),
                    isLast = true
                ) {
                    showAuthDiagnosticDialog = true
                }
            }
        }
        
        Spacer(modifier = Modifier.height(100.dp))
    }

    // Language Selection Sheet
    if (showLanguageSheet) {
        ModalBottomSheet(onDismissRequest = { showLanguageSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(stringResource(R.string.language), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
                
                ListItem(
                    headlineContent = { Text(stringResource(R.string.system)) },
                    modifier = Modifier.clickable { pendingLanguage = "system"; showLanguageSheet = false }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.english)) },
                    modifier = Modifier.clickable { pendingLanguage = "en"; showLanguageSheet = false }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.arabic)) },
                    modifier = Modifier.clickable { pendingLanguage = "ar"; showLanguageSheet = false }
                )
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }


    // Confirmation Dialogs
    if (pendingThemeMode != null) {
        AlertDialog(
            onDismissRequest = { pendingThemeMode = null },
            title = { Text(stringResource(R.string.confirm_change)) },
            text = { Text(stringResource(R.string.confirm_theme_change)) },
            confirmButton = {
                TextButton(onClick = {
                    val mode = pendingThemeMode
                    if (mode != null) {
                        coroutineScope.launch { userPrefs.saveThemeMode(mode) }
                    }
                    pendingThemeMode = null
                }) { Text(stringResource(R.string.yes), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { pendingThemeMode = null }) { Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onSurface) }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (pendingPrimaryColor != null) {
        AlertDialog(
            onDismissRequest = { pendingPrimaryColor = null },
            title = { Text(stringResource(R.string.confirm_change)) },
            text = { Text(stringResource(R.string.confirm_color_change)) },
            confirmButton = {
                TextButton(onClick = {
                    val color = pendingPrimaryColor
                    if (color != null) {
                        coroutineScope.launch { userPrefs.savePrimaryColor(color) }
                    }
                    pendingPrimaryColor = null
                }) { Text(stringResource(R.string.yes), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { pendingPrimaryColor = null }) { Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onSurface) }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (pendingLanguage != null) {
        AlertDialog(
            onDismissRequest = { pendingLanguage = null },
            title = { Text(stringResource(R.string.confirm_change)) },
            text = { Text(stringResource(R.string.confirm_language_change)) },
            confirmButton = {
                TextButton(onClick = {
                    val lang = pendingLanguage
                    if (lang != null) {
                        coroutineScope.launch { 
                            userPrefs.saveAppLanguage(lang)
                            kotlinx.coroutines.delay(100) 
                            val intent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                            intent?.addFlags(android.content.Intent.FLAG_ACTIVITY_CLEAR_TOP or android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                            if (intent != null) {
                                context.startActivity(intent)
                                (context as? android.app.Activity)?.finishAffinity()
                                Runtime.getRuntime().exit(0)
                            }
                        }
                    }
                    pendingLanguage = null
                }) { Text(stringResource(R.string.yes), color = MaterialTheme.colorScheme.primary) }
            },
            dismissButton = {
                TextButton(onClick = { pendingLanguage = null }) { Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onSurface) }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }

    if (showClearCacheDialog) {
        AlertDialog(
            onDismissRequest = { if (!isClearingCache) showClearCacheDialog = false },
            title = { Text(stringResource(R.string.clear_cache)) },
            text = { Text(stringResource(R.string.confirm_clear_cache)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        coroutineScope.launch {
                            isClearingCache = true
                            val freed = CacheManagementHelper.cleanCacheSafely(context)
                            val freedStr = CacheManagementHelper.formatBytes(freed)
                            isClearingCache = false
                            showClearCacheDialog = false
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.cache_cleared_success, freedStr),
                                android.widget.Toast.LENGTH_LONG
                            ).show()
                        }
                    },
                    enabled = !isClearingCache
                ) {
                    if (isClearingCache) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Text(stringResource(R.string.clean_now), color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showClearCacheDialog = false },
                    enabled = !isClearingCache
                ) {
                    Text(stringResource(R.string.cancel), color = MaterialTheme.colorScheme.onSurface)
                }
            },
            containerColor = MaterialTheme.colorScheme.surface,
            titleContentColor = MaterialTheme.colorScheme.onSurface,
            textContentColor = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
    // Start Screen Selection Sheet

    if (showDownloadSettingsDialog) {
        DownloadSettingsDialog(
            maxConcurrentDownloads = maxConcurrentDownloads,
            maxSegments = maxSegments,
            downloadNetwork = downloadNetwork,
            onMaxConcurrentChanged = { coroutineScope.launch { userPrefs.saveMaxConcurrentDownloads(it) } },
            onMaxSegmentsChanged = { coroutineScope.launch { userPrefs.saveMaxSegments(it) } },
            onNetworkChanged = { coroutineScope.launch { userPrefs.saveDownloadNetwork(it) } },
            onDismiss = { showDownloadSettingsDialog = false }
        )
    }

    if (showPlaybackSettingsDialog) {
        PlaybackSettingsDialog(
            seekDuration = playbackSeekDuration,
            controlsTimeout = playbackControlsTimeout,
            onSeekDurationChanged = { coroutineScope.launch { userPrefs.savePlaybackSeekDuration(it) } },
            onControlsTimeoutChanged = { coroutineScope.launch { userPrefs.savePlaybackControlsTimeout(it) } },
            onDismiss = { showPlaybackSettingsDialog = false }
        )
    }

    if (showNotificationSettingsDialog) {
        NotificationPreferencesDialog(
            onDismiss = { showNotificationSettingsDialog = false }
        )
    }

    if (com.example.BuildConfig.DEBUG && showAuthDiagnosticDialog) {
        AuthDiagnosticDialog(onDismiss = { showAuthDiagnosticDialog = false })
    }



    if (showStartScreenSheet) {
        ModalBottomSheet(onDismissRequest = { showStartScreenSheet = false }) {
            Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                Text(stringResource(R.string.start_screen), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(bottom = 16.dp))
                
                ListItem(
                    headlineContent = { Text(stringResource(R.string.home)) },
                    modifier = Modifier.clickable { coroutineScope.launch { userPrefs.saveStartScreen("home"); showStartScreenSheet = false } }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.movies)) },
                    modifier = Modifier.clickable { coroutineScope.launch { userPrefs.saveStartScreen("movies"); showStartScreenSheet = false } }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.series)) },
                    modifier = Modifier.clickable { coroutineScope.launch { userPrefs.saveStartScreen("series"); showStartScreenSheet = false } }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.anime)) },
                    modifier = Modifier.clickable { coroutineScope.launch { userPrefs.saveStartScreen("anime"); showStartScreenSheet = false } }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.search)) },
                    modifier = Modifier.clickable { coroutineScope.launch { userPrefs.saveStartScreen("search"); showStartScreenSheet = false } }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.library)) },
                    modifier = Modifier.clickable { coroutineScope.launch { userPrefs.saveStartScreen("library"); showStartScreenSheet = false } }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.downloads)) },
                    modifier = Modifier.clickable { coroutineScope.launch { userPrefs.saveStartScreen("downloads"); showStartScreenSheet = false } }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.profile)) },
                    modifier = Modifier.clickable { coroutineScope.launch { userPrefs.saveStartScreen("profile"); showStartScreenSheet = false } }
                )
                ListItem(
                    headlineContent = { Text(stringResource(R.string.settings)) },
                    modifier = Modifier.clickable { coroutineScope.launch { userPrefs.saveStartScreen("settings"); showStartScreenSheet = false } }
                )
                Spacer(modifier = Modifier.height(32.dp))
            }
        }
    }
}

@Composable

fun SettingsSectionHeader(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 12.dp)) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
        Spacer(modifier = Modifier.width(8.dp))
        Text(title, color = MaterialTheme.colorScheme.onBackground, fontSize = 16.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun ColorCircle(color: Color, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(24.dp)
            .clip(CircleShape)
            .background(color)
            .clickable { onClick() }
            .border(
                width = if (isSelected) 2.dp else 0.dp,
                color = if (isSelected) MaterialTheme.colorScheme.onBackground else Color.Transparent,
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isSelected) {
            Icon(Icons.Default.Check, contentDescription = null, tint = MaterialTheme.colorScheme.onBackground, modifier = Modifier.size(14.dp))
        }
    }
}

@Composable
fun SettingsListItem(
    icon: androidx.compose.ui.graphics.vector.ImageVector, 
    title: String, 
    subtitle: String, 
    isLast: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(modifier = Modifier.size(40.dp).clip(CircleShape).border(1.dp, MaterialTheme.colorScheme.surfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
        }
        Spacer(modifier = Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, color = MaterialTheme.colorScheme.onSurface, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Spacer(modifier = Modifier.height(2.dp))
            Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 12.sp)
        }
        Icon(Icons.Default.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (!isLast) {
        Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.surfaceVariant))
    }
}
@Composable
fun DownloadSettingsDialog(
    maxConcurrentDownloads: Int,
    maxSegments: Int,
    downloadNetwork: Int,
    onMaxConcurrentChanged: (Int) -> Unit,
    onMaxSegmentsChanged: (Int) -> Unit,
    onNetworkChanged: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onDismiss,
        properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false)
    ) {
        val darkBg = MaterialTheme.colorScheme.background
        val cardBg = MaterialTheme.colorScheme.surfaceVariant
        val redPrimary = androidx.compose.material3.MaterialTheme.colorScheme.primary
        
        Box(
            modifier = Modifier
                .fillMaxWidth(0.9f)
                .clip(RoundedCornerShape(24.dp))
                .background(darkBg)
                .border(1.dp, redPrimary.copy(alpha = 0.3f), RoundedCornerShape(24.dp))
                .padding(24.dp)
        ) {
            Column {
                // Header
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .background(MaterialTheme.colorScheme.surface)
                                .border(1.dp, redPrimary, RoundedCornerShape(12.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(Icons.Outlined.FileDownload, contentDescription = null, tint = redPrimary, modifier = Modifier.size(24.dp))
                        }
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(stringResource(R.string.downloads_settings), color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    }
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(MaterialTheme.colorScheme.outlineVariant)
                            .clickable { onDismiss() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Default.Close, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(20.dp))
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Max Concurrent
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(cardBg)
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Layers, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.max_concurrent_downloads), color = Color.LightGray, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(maxConcurrentDownloads.toString(), color = redPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    androidx.compose.material3.Slider(
                        value = maxConcurrentDownloads.toFloat(),
                        onValueChange = { onMaxConcurrentChanged(it.toInt()) },
                        valueRange = 1f..5f,
                        steps = 3,
                        colors = androidx.compose.material3.SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = redPrimary,
                            inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                            
                            
                        )
                    )
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        for (i in 1..5) {
                            Text(i.toString(), color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Max Segments
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(cardBg)
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Default.Movie, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.max_segments_per_download), color = Color.LightGray, fontSize = 14.sp, modifier = Modifier.weight(1f))
                        Text(maxSegments.toString(), color = redPrimary, fontSize = 16.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    val segmentOptions = listOf(5, 10, 20, 50)
                    val closestIndex = segmentOptions.indexOfMinByOrNull { kotlin.math.abs(it - maxSegments) } ?: 0
                    
                    androidx.compose.material3.Slider(
                        value = closestIndex.toFloat(),
                        onValueChange = { onMaxSegmentsChanged(segmentOptions[it.toInt()]) },
                        valueRange = 0f..(segmentOptions.size - 1).toFloat(),
                        steps = segmentOptions.size - 2,
                        colors = androidx.compose.material3.SliderDefaults.colors(
                            thumbColor = Color.White,
                            activeTrackColor = redPrimary,
                            inactiveTrackColor = MaterialTheme.colorScheme.outlineVariant,
                            
                            
                        )
                    )
                    Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                        segmentOptions.forEach {
                            Text(it.toString(), color = Color.Gray, fontSize = 12.sp)
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Network
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(cardBg)
                        .padding(16.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(bottom = 16.dp)) {
                        Icon(Icons.Default.Wifi, contentDescription = null, tint = Color.LightGray, modifier = Modifier.size(20.dp))
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(stringResource(R.string.download_network), color = Color.LightGray, fontSize = 14.sp)
                    }
                    
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        NetworkOption(
                            modifier = Modifier.weight(1.2f),
                            title = stringResource(R.string.all),
                            subtitle = stringResource(R.string.wifi_and_cellular),
                            isSelected = downloadNetwork == 0,
                            onClick = { onNetworkChanged(0) }
                        )
                        NetworkOption(
                            modifier = Modifier.weight(1f),
                            title = stringResource(R.string.network_wifi),
                            subtitle = null,
                            isSelected = downloadNetwork == 1,
                            onClick = { onNetworkChanged(1) }
                        )
                        NetworkOption(
                            modifier = Modifier.weight(1f),
                            title = stringResource(R.string.network_cellular),
                            subtitle = null,
                            isSelected = downloadNetwork == 2,
                            onClick = { onNetworkChanged(2) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = androidx.compose.material3.ButtonDefaults.buttonColors(containerColor = redPrimary),
                    shape = RoundedCornerShape(percent = 50)
                ) {
                    Text(stringResource(R.string.done), color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
fun NetworkOption(modifier: Modifier = Modifier, title: String, subtitle: String?, isSelected: Boolean, onClick: () -> Unit) {
    val bgColor = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surface
    val borderColor = if (isSelected) androidx.compose.material3.MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    val iconColor = if (isSelected) androidx.compose.material3.MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline

    Row(
        modifier = modifier
            .clip(RoundedCornerShape(12.dp))
            .background(bgColor)
            .border(1.dp, borderColor, RoundedCornerShape(12.dp))
            .clickable { onClick() }
            .padding(vertical = 12.dp, horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start
    ) {
        Icon(
            if (isSelected) Icons.Default.Check else Icons.Default.Close,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(6.dp))
        Column(verticalArrangement = Arrangement.Center) {
            Text(title, color = if (isSelected) Color.White else Color.LightGray, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, maxLines = 1)
            if (subtitle != null) {
                Text(subtitle, color = Color.Gray, fontSize = 10.sp, maxLines = 1)
            }
        }
    }
}

inline fun <T, R : Comparable<R>> Iterable<T>.indexOfMinByOrNull(selector: (T) -> R): Int? {
    val iterator = iterator()
    if (!iterator.hasNext()) return null
    var minElem = iterator.next()
    var minValue = selector(minElem)
    var minIndex = 0
    var index = 1
    while (iterator.hasNext()) {
        val e = iterator.next()
        val v = selector(e)
        if (minValue > v) {
            minElem = e
            minValue = v
            minIndex = index
        }
        index++
    }
    return minIndex
}

@Composable
fun PlaybackSettingsDialog(
    seekDuration: Int,
    controlsTimeout: Int,
    onSeekDurationChanged: (Int) -> Unit,
    onControlsTimeoutChanged: (Int) -> Unit,
    onDismiss: () -> Unit
) {
    androidx.compose.ui.window.Dialog(onDismissRequest = onDismiss) {
        val cardBg = MaterialTheme.colorScheme.surface
        val primaryColor = MaterialTheme.colorScheme.primary

        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp)),
            color = MaterialTheme.colorScheme.background,
            tonalElevation = 8.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
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
                                .background(primaryColor.copy(alpha = 0.15f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Outlined.PlayCircleOutline,
                                contentDescription = null,
                                tint = primaryColor,
                                modifier = Modifier.size(24.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            stringResource(R.string.playback_settings),
                            color = MaterialTheme.colorScheme.onBackground,
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
                    ) {
                        Icon(
                            Icons.Default.Close,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(20.dp))

                // Seek Duration Section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(cardBg)
                        .padding(16.dp)
                ) {
                    Text(
                        stringResource(R.string.seek_duration),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    val seekOptions = listOf(5, 10, 15)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        seekOptions.forEach { opt ->
                            val isSelected = opt == seekDuration
                            val isDefault = opt == 10
                            val label = "${opt}s" + if (isDefault) " *" else ""
                            
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) primaryColor else MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { onSeekDurationChanged(opt) }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        label,
                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                    if (isDefault) {
                                        Text(
                                            stringResource(R.string.default_option),
                                            color = if (isSelected) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            fontSize = 9.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                // Controls Timeout Section
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(cardBg)
                        .padding(16.dp)
                ) {
                    Text(
                        stringResource(R.string.controls_timeout),
                        color = MaterialTheme.colorScheme.onSurface,
                        fontSize = 14.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(12.dp))

                    val timeoutOptions = listOf(5, 10, 15, 30)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        timeoutOptions.forEach { opt ->
                            val isSelected = opt == controlsTimeout
                            val isDefault = opt == 10
                            val label = "${opt}s"
                            
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(if (isSelected) primaryColor else MaterialTheme.colorScheme.surfaceVariant)
                                    .clickable { onControlsTimeoutChanged(opt) }
                                    .padding(vertical = 12.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Text(
                                        label,
                                        color = if (isSelected) Color.White else MaterialTheme.colorScheme.onSurfaceVariant,
                                        fontSize = 14.sp,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium
                                    )
                                    if (isDefault) {
                                        Text(
                                            stringResource(R.string.default_option),
                                            color = if (isSelected) Color.White.copy(alpha = 0.8f) else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                                            fontSize = 9.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = onDismiss,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = primaryColor),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        stringResource(R.string.done),
                        color = Color.White,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}
