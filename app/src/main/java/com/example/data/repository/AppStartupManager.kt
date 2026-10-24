package com.example.data.repository

import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import com.example.R
import com.example.data.model.AppConfig
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.tasks.await
import kotlinx.coroutines.withContext

object AppStartupManager {
    private const val TAG = "AppStartupManager"
    private const val PREFS_NAME = "app_startup_prefs"
    private const val KEY_MAINTENANCE_ENABLED = "maintenance_enabled"
    private const val KEY_MAINTENANCE_TITLE = "maintenance_title"
    private const val KEY_MAINTENANCE_MESSAGE = "maintenance_message"
    private const val KEY_MIN_VERSION = "min_version"
    private const val KEY_DEFAULT_OFFLINE_DAYS = "default_offline_days"
    private const val KEY_DEFAULT_FORCED_ADS = "default_forced_ads"

    private val _currentConfigFlow = MutableStateFlow(AppConfig())
    val currentConfigFlow: StateFlow<AppConfig> = _currentConfigFlow.asStateFlow()

    private val _maintenanceFlow = MutableStateFlow<Pair<String, String>?>(null)
    val maintenanceFlow: StateFlow<Pair<String, String>?> = _maintenanceFlow.asStateFlow()

    fun setMaintenanceState(state: Pair<String, String>?) {
        _maintenanceFlow.value = state
    }

    sealed class MaintenanceCheckResult {
        object Lifted : MaintenanceCheckResult()
        data class StillActive(val title: String, val message: String) : MaintenanceCheckResult()
        data class Error(val message: String) : MaintenanceCheckResult()
    }

    suspend fun checkMaintenanceStatus(context: Context): MaintenanceCheckResult = withContext(Dispatchers.IO) {
        val firestore = FirebaseFirestore.getInstance()
        val docRef = firestore.collection(AppConfig.CONFIG_COLLECTION)
            .document(AppConfig.CONFIG_DOCUMENT)
        try {
            // First attempt: Force live fetch directly from Firestore Server
            var doc = try {
                docRef.get(Source.SERVER).await()
            } catch (e: Exception) {
                Log.w(TAG, "Source.SERVER fetch failed, falling back to Source.DEFAULT: ${e.message}")
                try {
                    docRef.get().await()
                } catch (e2: Exception) {
                    null
                }
            }

            val config = if (doc != null && doc.exists()) {
                AppConfig.fromDocument(doc)
            } else {
                AppConfig()
            }

            saveCache(context, config)
            _currentConfigFlow.value = config

            val isExempt = isUserExemptFromMaintenance(config)
            if (config.maintenanceEnabled && !isExempt) {
                val title = config.maintenanceTitle.ifBlank { "" }
                val msg = config.maintenanceMessage.ifBlank { "" }
                _maintenanceFlow.value = Pair(title, msg)
                MaintenanceCheckResult.StillActive(title, msg)
            } else {
                _maintenanceFlow.value = null
                MaintenanceCheckResult.Lifted
            }
        } catch (e: Exception) {
            Log.e(TAG, "checkMaintenanceStatus failed", e)
            MaintenanceCheckResult.Error(e.localizedMessage ?: context.getString(R.string.failed_connect_server_verify))
        }
    }

    fun getCurrentVersionCode(context: Context): Int {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                pInfo.longVersionCode.toInt()
            } else {
                @Suppress("DEPRECATION")
                pInfo.versionCode
            }
        } catch (e: Exception) {
            1
        }
    }

    fun getCurrentVersionName(context: Context): String {
        return try {
            val pInfo = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(context.packageName, PackageManager.PackageInfoFlags.of(0))
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(context.packageName, 0)
            }
            pInfo.versionName ?: "1.0.0"
        } catch (e: Exception) {
            "1.0.0"
        }
    }

    /**
     * Checks app configuration from /config/app (with server prioritization and global fallback).
     */
    fun checkAppConfig(
        context: Context,
        currentVersionCode: Int = getCurrentVersionCode(context),
        onProceed: () -> Unit,
        onMaintenance: (title: String, message: String) -> Unit,
        onUpdateAvailable: (isMandatory: Boolean, apkUrl: String, notes: String, latestVersionName: String) -> Unit
    ) {
        val firestore = FirebaseFirestore.getInstance()
        val docRef = firestore.collection(AppConfig.CONFIG_COLLECTION)
            .document(AppConfig.CONFIG_DOCUMENT)

        docRef.get(Source.SERVER)
            .addOnSuccessListener { doc ->
                val config = if (doc != null && doc.exists()) {
                    AppConfig.fromDocument(doc)
                } else {
                    AppConfig()
                }
                applyConfigCheck(context, config, currentVersionCode, onProceed, onMaintenance, onUpdateAvailable)
            }
            .addOnFailureListener { serverError ->
                Log.w(TAG, "Source.SERVER fetch failed, trying default cache: ${serverError.message}")
                docRef.get()
                    .addOnSuccessListener { doc ->
                        val config = if (doc != null && doc.exists()) {
                            AppConfig.fromDocument(doc)
                        } else {
                            AppConfig()
                        }
                        applyConfigCheck(context, config, currentVersionCode, onProceed, onMaintenance, onUpdateAvailable)
                    }
                    .addOnFailureListener { error ->
                        Log.w(TAG, "Failed to load /config/app, applying cached fallback", error)
                        applyOfflineFallback(context, currentVersionCode, onProceed, onMaintenance)
                    }
            }
    }

    /**
     * Realtime listener to /config/app only.
     * Propagates maintenance and OTA updates immediately according to Firebase contract.
     */
    fun listenToAppConfig(
        context: Context,
        currentAppVersionCode: Int = getCurrentVersionCode(context),
        onMaintenance: (title: String, message: String) -> Unit,
        onUpdateRequired: (apkUrl: String, mandatory: Boolean, notes: String, versionName: String) -> Unit,
        onNormalOperation: () -> Unit
    ): ListenerRegistration {
        val firestore = FirebaseFirestore.getInstance()

        return firestore.collection(AppConfig.CONFIG_COLLECTION)
            .document(AppConfig.CONFIG_DOCUMENT)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Error listening to /config/app: ", error)
                    return@addSnapshotListener
                }
                val config = if (snapshot != null && snapshot.exists()) {
                    AppConfig.fromDocument(snapshot)
                } else {
                    AppConfig()
                }
                processRealtimeConfig(context, config, currentAppVersionCode, onMaintenance, onUpdateRequired, onNormalOperation)
            }
    }

    private fun isUserExemptFromMaintenance(config: AppConfig): Boolean {
        if (UserSecurityManager.isAdmin()) return true
        val currentRole = UserSecurityManager.restrictions.role
        // Prevent spoofed role="admin" from bypassing maintenance unless verified by canonical admin doc
        if (currentRole.equals("admin", ignoreCase = true) || currentRole.equals("superadmin", ignoreCase = true)) {
            return false
        }
        return config.maintenanceAllowedRoles.any { it.equals(currentRole, ignoreCase = true) }
    }

    private fun processRealtimeConfig(
        context: Context,
        config: AppConfig,
        currentAppVersionCode: Int,
        onMaintenance: (title: String, message: String) -> Unit,
        onUpdateRequired: (apkUrl: String, mandatory: Boolean, notes: String, versionName: String) -> Unit,
        onNormalOperation: () -> Unit
    ) {
        saveCache(context, config)
        _currentConfigFlow.value = config

        // 1. Maintenance Mode
        if (config.maintenanceEnabled && !isUserExemptFromMaintenance(config)) {
            onMaintenance(
                if (config.maintenanceTitle.isNotBlank()) config.maintenanceTitle else context.getString(R.string.maintenance_title_default),
                if (config.maintenanceMessage.isNotBlank()) config.maintenanceMessage else context.getString(R.string.maintenance_message_default)
            )
            return
        }

        // 2. Minimum Version Code or Mandatory Update Check
        val isBelowMinVersion = currentAppVersionCode < config.minimumVersionCode
        val isUpdateAvailable = currentAppVersionCode < config.latestVersionCode && config.apkUrl.isNotBlank()
        val isMandatory = isBelowMinVersion || (isUpdateAvailable && config.mandatoryUpdate)

        if (isMandatory) {
            onUpdateRequired(
                config.apkUrl,
                true,
                if (config.releaseNotes.isNotBlank()) config.releaseNotes else if (isBelowMinVersion) context.getString(R.string.unsupported_version_update_required) else context.getString(R.string.mandatory_update_required),
                config.latestVersionName
            )
            return
        }

        // 3. Optional Update
        if (isUpdateAvailable) {
            onUpdateRequired(
                config.apkUrl,
                false,
                config.releaseNotes,
                config.latestVersionName
            )
        }

        onNormalOperation()
    }

    private fun applyConfigCheck(
        context: Context,
        config: AppConfig,
        currentVersionCode: Int,
        onProceed: () -> Unit,
        onMaintenance: (title: String, message: String) -> Unit,
        onUpdateAvailable: (isMandatory: Boolean, apkUrl: String, notes: String, latestVersionName: String) -> Unit
    ) {
        saveCache(context, config)
        _currentConfigFlow.value = config

        // 1. Check Maintenance Mode
        if (config.maintenanceEnabled && !isUserExemptFromMaintenance(config)) {
            onMaintenance(
                if (config.maintenanceTitle.isNotBlank()) config.maintenanceTitle else context.getString(R.string.maintenance_title_default),
                if (config.maintenanceMessage.isNotBlank()) config.maintenanceMessage else context.getString(R.string.maintenance_message_default)
            )
            return
        }

        // 2. Minimum Version Code or Mandatory Update Check
        val isBelowMinVersion = currentVersionCode < config.minimumVersionCode
        val isUpdateAvailable = config.latestVersionCode > currentVersionCode && config.apkUrl.isNotBlank()
        val isMandatory = isBelowMinVersion || (isUpdateAvailable && config.mandatoryUpdate)

        if (isMandatory) {
            onUpdateAvailable(
                true,
                config.apkUrl,
                if (config.releaseNotes.isNotBlank()) config.releaseNotes else if (isBelowMinVersion) context.getString(R.string.unsupported_version_update_required) else context.getString(R.string.mandatory_update_required),
                config.latestVersionName
            )
            return
        } else if (isUpdateAvailable) {
            onUpdateAvailable(
                false,
                config.apkUrl,
                config.releaseNotes,
                config.latestVersionName
            )
        }

        onProceed()
    }

    private fun applyOfflineFallback(
        context: Context,
        currentVersionCode: Int,
        onProceed: () -> Unit,
        onMaintenance: (title: String, message: String) -> Unit
    ) {
        Log.w(TAG, "Proceeding with cached/offline fallback")
        val cached = getCachedMaintenance(context)
        if (cached.first && !UserSecurityManager.isAdmin()) {
            onMaintenance(cached.third, cached.fourth)
        } else {
            onProceed()
        }
    }

    private fun saveCache(context: Context, config: AppConfig) {
        try {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            sp.edit()
                .putBoolean(KEY_MAINTENANCE_ENABLED, config.maintenanceEnabled)
                .putString(KEY_MAINTENANCE_TITLE, config.maintenanceTitle)
                .putString(KEY_MAINTENANCE_MESSAGE, config.maintenanceMessage)
                .putInt(KEY_MIN_VERSION, config.minVersionCode.toInt())
                .putInt(KEY_DEFAULT_OFFLINE_DAYS, config.offlineWatchDaysLimit.toInt())
                .putInt(KEY_DEFAULT_FORCED_ADS, config.forcedAdsCount.toInt())
                .apply()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun getCachedMaintenance(context: Context): Tuple4<Boolean, Int, String, String> {
        val defaultTitle = context.getString(R.string.maintenance_title_default)
        val defaultMsg = context.getString(R.string.maintenance_message_default)
        return try {
            val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val enabled = sp.getBoolean(KEY_MAINTENANCE_ENABLED, false)
            val minVersion = sp.getInt(KEY_MIN_VERSION, 1)
            val title = sp.getString(KEY_MAINTENANCE_TITLE, defaultTitle) ?: defaultTitle
            val msg = sp.getString(KEY_MAINTENANCE_MESSAGE, defaultMsg) ?: defaultMsg
            Tuple4(enabled, minVersion, title, msg)
        } catch (e: Exception) {
            Tuple4(false, 1, defaultTitle, defaultMsg)
        }
    }

    fun getDefaultOfflineDays(context: Context): Int {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getInt(KEY_DEFAULT_OFFLINE_DAYS, 2)
    }

    fun getDefaultForcedAds(context: Context): Int {
        val sp = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return sp.getInt(KEY_DEFAULT_FORCED_ADS, 5)
    }

    private data class Tuple4<A, B, C, D>(val first: A, val second: B, val third: C, val fourth: D)
}
