package com.example

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Campaign
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.os.LocaleListCompat
import android.content.Intent
import com.example.data.repository.AuthRepository
import com.example.data.repository.UserPreferencesRepository
import com.example.navigation.AppNavigation
import com.example.navigation.NavigationIntentHandler
import com.example.navigation.Screen
import com.example.ui.theme.MyApplicationTheme
import com.example.utils.NotificationHelper
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import com.example.data.repository.NotificationRepository
import com.example.navigation.NotificationIntentParser
import com.startapp.sdk.adsbase.StartAppAd
import com.startapp.sdk.adsbase.StartAppSDK

class MainActivity : AppCompatActivity() {
  override fun onCreate(savedInstanceState: Bundle?) {
    
    super.onCreate(savedInstanceState)
    if (savedInstanceState == null || !NotificationIntentParser.isIntentConsumed(intent)) {
      handleNavigationIntent(intent)
    }
    
    // One-time cleanup of legacy extension preferences to ensure clean migration
    getSharedPreferences("extensions_prefs", android.content.Context.MODE_PRIVATE).edit().clear().apply()
    com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(this)
    
    // Initialize StartApp SDK with configured App ID from BuildConfig if valid and non-empty.
    val startAppId = try {
        (BuildConfig::class.java.getField("STARTAPP_APP_ID").get(null) as? String)?.trim() ?: ""
    } catch (e: Throwable) {
        ""
    }
    val isStartAppConfigured = startAppId.isNotEmpty() && !startAppId.startsWith("YOUR_")
    if (isStartAppConfigured) {
        try {
            StartAppSDK.init(this, startAppId, false)
            StartAppAd.disableSplash()
            com.example.utils.AdManager.preload(this)
        } catch (t: Throwable) {
            android.util.Log.w("MainActivity", "StartAppSDK init failed: ${t.message}")
        }
    } else {
        android.util.Log.d("MainActivity", "StartAppSDK init skipped: no valid App ID configured")
    }
    
    NotificationHelper.createChannel(this)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
    }
    enableEdgeToEdge()
    
    val userPreferences = UserPreferencesRepository(this)
    
    setContent {
      

      val themeMode by userPreferences.themeMode.collectAsState(initial = 0)
      val primaryColor by userPreferences.primaryColor.collectAsState(initial = 0)
      val appLanguage by userPreferences.appLanguage.collectAsState(initial = null)
      
      // Update app language
      LaunchedEffect(appLanguage) {
          if (appLanguage != null) {
              val currentLocales = AppCompatDelegate.getApplicationLocales()
              val desiredLanguage = if (appLanguage == "system") "" else appLanguage!!
              
              val currentTag = if (currentLocales.isEmpty) "" else currentLocales.toLanguageTags()
              
              val needsUpdate = if (desiredLanguage.isEmpty()) {
                  !currentLocales.isEmpty
              } else {
                  !currentTag.startsWith(desiredLanguage)
              }
              
              if (needsUpdate) {
                  if (desiredLanguage.isEmpty()) {
                      AppCompatDelegate.setApplicationLocales(LocaleListCompat.getEmptyLocaleList())
                  } else {
                      AppCompatDelegate.setApplicationLocales(LocaleListCompat.forLanguageTags(desiredLanguage))
                  }
              }
          }
      }

      MyApplicationTheme(
          themeMode = themeMode,
          primaryColor = primaryColor
      ) {
        val downloadRepo = remember { com.example.data.repository.DownloadRepository(this@MainActivity) }
        com.example.data.repository.DownloadManagerService.init(this@MainActivity.applicationContext)
        
        var availableUpdate by remember { androidx.compose.runtime.mutableStateOf<com.example.data.repository.AppUpdateInfo?>(null) }
        var alertAnnouncement by remember { androidx.compose.runtime.mutableStateOf<Pair<String, String>?>(null) }
        var realtimeMaintenance by remember { androidx.compose.runtime.mutableStateOf<Pair<String, String>?>(null) }
        val currentUser by AuthRepository.currentUserFlow.collectAsState()
        val currentUid = currentUser?.uid ?: AuthRepository.auth.currentUser?.uid ?: ""

        // Realtime Maintenance & Global Config Listener
        DisposableEffect(Unit) {
            com.example.extension.orchestrator.ManagedMediaOrchestrator.getInstance(this@MainActivity)
            // Cleanup any old legacy extension APK cache if present
            try {
                val legacyExtDir = java.io.File(this@MainActivity.filesDir, "extensions")
                if (legacyExtDir.exists()) legacyExtDir.deleteRecursively()
                val legacyExtCacheDir = java.io.File(this@MainActivity.cacheDir, "extensions")
                if (legacyExtCacheDir.exists()) legacyExtCacheDir.deleteRecursively()
            } catch (_: Exception) {}
            val configListener = com.example.data.repository.AppStartupManager.listenToAppConfig(
                context = this@MainActivity,
                onMaintenance = { title, message ->
                    realtimeMaintenance = Pair(title, message)
                },
                onUpdateRequired = { apkUrl, mandatory, notes, versionName ->
                    realtimeMaintenance = null
                    val currentCode = com.example.data.repository.AppStartupManager.getCurrentVersionCode(this@MainActivity)
                    availableUpdate = com.example.data.repository.AppUpdateInfo(
                        versionCode = currentCode + 1L,
                        versionName = versionName,
                        releaseNotes = notes,
                        downloadUrl = apkUrl,
                        isMandatory = mandatory
                    )
                },
                onNormalOperation = {
                    realtimeMaintenance = null
                }
            )
            onDispose {
                configListener.remove()
            }
        }

        DisposableEffect(currentUid) {
            val announcementListener = com.example.data.repository.NotificationRepository.listenForAnnouncements(
                context = this@MainActivity,
                currentUid = currentUid
            ) { title, msg ->
                alertAnnouncement = Pair(title, msg)
            }
            val securityListener = if (currentUid.isNotBlank()) {
                com.example.data.sync.CloudSyncManager(this@MainActivity).startRealtimeSync(currentUid)
                com.example.data.repository.UserSecurityManager.listenToUserSecurity(currentUid)
            } else {
                com.example.data.sync.CloudSyncManager.stopRealtimeSync()
                com.example.data.repository.UserSecurityManager.reset()
                null
            }
            onDispose {
                announcementListener?.remove()
                securityListener?.remove()
                com.example.data.sync.CloudSyncManager.stopRealtimeSync()
            }
        }

        LaunchedEffect(currentUid) {
            if (currentUid.isNotBlank()) {
                try {
                    AuthRepository.getCurrentUser()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            // 1. Sync notifications and preferences from Firestore in background
            try {
                com.example.data.notification.FcmTokenManager.getInstance(this@MainActivity).syncTokenAsync()
                if (currentUid.isNotBlank()) {
                    com.example.data.repository.NotificationPreferencesRepository(this@MainActivity).syncWithFirestore(currentUid)
                }
                com.example.data.repository.NotificationRepository(this@MainActivity).syncCloudNotifications(
                    context = this@MainActivity,
                    currentUid = currentUid
                )
            } catch (e: Exception) {
                e.printStackTrace()
            }
            // 2. Check for App Updates in background
            try {
                val update = com.example.data.repository.AppUpdateManager.checkForUpdate(this@MainActivity)
                if (update != null) {
                    availableUpdate = update
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        availableUpdate?.let { updateInfo ->
            com.example.ui.components.AppUpdateDialog(
                updateInfo = updateInfo,
                onDismiss = { availableUpdate = null }
            )
        }

        alertAnnouncement?.let { (title, msg) ->
            AlertDialog(
                onDismissRequest = { alertAnnouncement = null },
                icon = {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.Campaign,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(36.dp)
                    )
                },
                title = {
                    Text(
                        text = if (title.isNotBlank()) title else stringResource(R.string.important_alert),
                        fontWeight = androidx.compose.ui.text.font.FontWeight.Bold,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                },
                text = {
                    Text(
                        text = msg,
                        fontSize = 15.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                },
                confirmButton = {
                    Button(
                        onClick = { alertAnnouncement = null },
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                    ) {
                        Text(stringResource(R.string.ok))
                    }
                }
            )
        }
        
        val userRestrictions by com.example.data.repository.UserSecurityManager.restrictionsFlow.collectAsState()

        if (realtimeMaintenance != null) {
            com.example.ui.screens.maintenance.MaintenanceScreen(
                title = realtimeMaintenance!!.first,
                message = realtimeMaintenance!!.second,
                onMaintenanceLifted = {
                    realtimeMaintenance = null
                },
                onRetry = {
                    realtimeMaintenance = null
                }
            )
        } else if (currentUid.isNotBlank() && userRestrictions.isBanActive) {
            com.example.ui.screens.banned.BannedScreen(
                reason = userRestrictions.banReason,
                expiresAt = userRestrictions.banExpiresAt,
                onSignOut = {
                    lifecycleScope.launch {
                        com.example.data.session.SessionManager.onUserLoggedOut(applicationContext)
                    }
                }
            )
        } else {
            Surface(modifier = Modifier.fillMaxSize()) {
                AppNavigation()
            }
        }
      }
    }
  }

  override fun onResume() {
    super.onResume()
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
      com.example.ui.screens.player.PlayerStateHolder.isInPipMode = isInPictureInPictureMode
    } else {
      com.example.ui.screens.player.PlayerStateHolder.isInPipMode = false
    }
    if (!com.example.ui.screens.player.PlayerStateHolder.isPlayerActive) {
      val lp = window.attributes
      if (lp.screenBrightness != android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE) {
        lp.screenBrightness = android.view.WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        window.attributes = lp
      }
    }
  }

  override fun onUserLeaveHint() {
    super.onUserLeaveHint()
    if (com.example.utils.AdManager.isShowingAd) {
      return
    }
    if (com.example.ui.screens.player.PlayerStateHolder.isPlayerActive && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
      com.example.ui.screens.player.PlayerStateHolder.onEnterPipRequested?.invoke()
    }
  }

  override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: android.content.res.Configuration) {
    super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    com.example.ui.screens.player.PlayerStateHolder.isInPipMode = isInPictureInPictureMode
  }

  override fun onNewIntent(intent: Intent) {
    super.onNewIntent(intent)
    setIntent(intent)
    handleNavigationIntent(intent)
  }

  private fun handleNavigationIntent(intent: Intent?) {
    if (intent == null) return
    if (NotificationIntentParser.isIntentConsumed(intent)) return

    // 1. Check if a navigation target was requested
    val hasNavigateToExtra = intent.hasExtra(com.example.navigation.NotificationNavigationContract.EXTRA_NAVIGATE_TO) ||
        intent.hasExtra(com.example.navigation.NotificationNavigationContract.EXTRA_NAVIGATE_TO_CAMEL)

    // 2. Parse and validate navigation destination
    val targetDestination = NotificationIntentParser.parse(intent)

    // If navigation extra was supplied but failed validation (e.g. malicious or invalid ID),
    // reject the intent without marking as read or navigating.
    if (hasNavigateToExtra && targetDestination == null) {
      android.util.Log.w("MainActivity", "Rejected invalid or malicious navigation intent payload.")
      NotificationIntentParser.markIntentConsumed(intent)
      return
    }

    // 3. Mark notification as read ONLY after validating that the intent is genuine and valid
    val notificationId = NotificationIntentParser.extractNotificationId(intent)
    if (notificationId != null) {
      lifecycleScope.launch(Dispatchers.IO) {
        try {
          val repository = NotificationRepository(applicationContext)
          repository.markAsRead(notificationId)
        } catch (e: Exception) {
          android.util.Log.w("MainActivity", "Failed to mark notification as read: ${e.message}")
        }
      }
    }

    // 4. Dispatch navigation if destination was specified and valid
    if (targetDestination != null) {
      NavigationIntentHandler.navigateTo(targetDestination)
    }

    // 5. Mark intent as consumed to prevent duplicate processing on configuration change / recreation
    NotificationIntentParser.markIntentConsumed(intent)
  }
}