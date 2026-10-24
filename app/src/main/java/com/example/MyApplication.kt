package com.example

import android.app.Application
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.ExistingPeriodicWorkPolicy
import java.util.concurrent.TimeUnit
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import coil.memory.MemoryCache
import okhttp3.Cache
import java.io.File

class MyApplication : Application(), ImageLoaderFactory {
    companion object {
        lateinit var appContext: android.content.Context
    }

    
    override fun onCreate() {
        super.onCreate()

        // Cancel any legacy periodic aggressive cache wipes to preserve user covers and offline data
        try {
            WorkManager.getInstance(this).cancelUniqueWork("CacheCleanupWork")
        } catch (e: Exception) {
            android.util.Log.w("MyApplication", "WorkManager not available: ${e.message}")
        }

        appContext = applicationContext

        // The official app/google-services.json is matched to the canonical applicationId.
        // Google Services initializes the default FirebaseApp before Application.onCreate;
        // this initializer verifies it and retains a defensive fallback for unusual runtimes.
        // Run this before repositories/services first request FirebaseAuth/Firestore.
        try {
            if (!com.example.utils.FirebaseInitializer.initialize(this)) {
                android.util.Log.e("MyApplication", "Firebase initialization failed; Firebase-backed features may be unavailable")
            }
        } catch (e: Exception) {
            android.util.Log.e("MyApplication", "Firebase initialization failed", e)
        }

        // Initialize all notification channels centrally at application startup
        com.example.data.notification.NotificationChannels.createAllChannels(this)
        
        val isRobolectric = android.os.Build.FINGERPRINT == "robolectric" || android.os.Build.HARDWARE == "robolectric"
        if (!isRobolectric) {
            val defaultHandler = Thread.getDefaultUncaughtExceptionHandler()
            Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
                val stackTrace = android.util.Log.getStackTraceString(throwable)
                val intent = android.content.Intent(this, com.example.ui.screens.crash.CrashActivity::class.java).apply {
                    putExtra("EXTRA_STACK_TRACE", stackTrace)
                    flags = android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                android.os.Process.killProcess(android.os.Process.myPid())
                System.exit(1)
            }
        }

        com.example.di.AppContainer.application = this
        
        // Phase 05X: Initialize DownloadedPostersManager cache asynchronously
        com.example.utils.DownloadedPostersManager.initAsync(this)
        
        // Phase 05M: Start Realtime Extension Sync Listener
        try {
            com.example.extension.managed.repository.ManagedExtensionRealtimeSyncManager.getInstance().startListening()
        } catch (e: Exception) {
            android.util.Log.w("MyApplication", "ManagedExtensionRealtimeSyncManager initialization notice: ${e.message}")
        }
        
        val cloudName = com.example.BuildConfig.CLOUDINARY_CLOUD_NAME
        val cloudNameIsConfigured = cloudName.isNotBlank() &&
            !cloudName.contains("YOUR_", ignoreCase = true) &&
            !cloudName.contains("mock", ignoreCase = true) &&
            !cloudName.contains("dummy", ignoreCase = true) &&
            !cloudName.contains("example", ignoreCase = true) &&
            cloudName != "UNCONFIGURED"
        if (cloudNameIsConfigured) {
            val config = mapOf("cloud_name" to cloudName)
            try {
                com.cloudinary.android.MediaManager.init(this, config)
            } catch (e: Exception) {
                // Ignore if already initialized; uploads independently validate the configuration.
            }
        }

    }

    override fun newImageLoader(): ImageLoader {
        return ImageLoader.Builder(this)
            .memoryCache {
                MemoryCache.Builder(this)
                    .maxSizePercent(0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(this.filesDir.resolve("image_cache"))
                    .maxSizeBytes(800L * 1024 * 1024) // 800 MB safe persistent cache
                    .build()
            }
            .transitionFactory(com.example.utils.SelectiveCrossfadeTransitionFactory(100))
            .respectCacheHeaders(false) // Ignore server headers that say don't cache
            .build()
    }
}