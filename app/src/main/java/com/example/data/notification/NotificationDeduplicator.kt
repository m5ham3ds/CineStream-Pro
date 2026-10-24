package com.example.data.notification

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.example.data.db.AppDatabase
import com.example.data.db.NotificationDao
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import java.util.Collections
import java.util.LinkedHashSet

/**
 * Multi-tiered, persistent deduplication manager for notifications.
 *
 * Prevents duplicate notification delivery across:
 * 1. FCM automatic network retries
 * 2. App process restarts and killed-state transitions
 * 3. Concurrent Firestore `/notifications` listener and FCM push messages for the same ID
 *
 * Tier 1: In-memory LRU-style cache for instantaneous lookup.
 * Tier 2: Persistent SharedPreferences disk store surviving process death.
 * Tier 3: Local Room database check (`notifications` table via [NotificationDao]).
 */
class NotificationDeduplicator(
    private val context: Context,
    private val sharedPreferences: SharedPreferences = context.getSharedPreferences(
        PREFS_NAME,
        Context.MODE_PRIVATE
    ),
    private val notificationDaoProvider: () -> NotificationDao? = {
        try {
            AppDatabase.getDatabase(context).notificationDao()
        } catch (e: Exception) {
            Log.w(TAG, "NotificationDao not available: ${e.message}")
            null
        }
    }
) {

    companion object {
        private const val TAG = "NotificationDeduplicator"
        const val PREFS_NAME = "fcm_notification_deduplicator"
        private const val KEY_PREFIX = "proc_id_"
        private const val MAX_MEMORY_ENTRIES = 500
        private const val MAX_DISK_ENTRIES = 500
        private const val PRUNE_THRESHOLD_DAYS_MS = 30L * 24L * 60L * 60L * 1000L // 30 days

        @Volatile
        private var instance: NotificationDeduplicator? = null

        fun getInstance(context: Context): NotificationDeduplicator {
            return instance ?: synchronized(this) {
                instance ?: NotificationDeduplicator(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val memoryCache = Collections.synchronizedSet(LinkedHashSet<String>())
    private val dedupScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    init {
        try {
            for (key in sharedPreferences.all.keys) {
                if (key.startsWith(KEY_PREFIX)) {
                    memoryCache.add(key.removePrefix(KEY_PREFIX))
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Atomically checks whether a notification with [notificationId] has already been processed,
     * and marks it as processed if it was not. Prevents race conditions between concurrent threads.
     *
     * @return `true` if duplicate (already processed), `false` if fresh and now marked as processed.
     */
    @Synchronized
    fun checkAndMarkProcessed(notificationId: String): Boolean {
        if (isDuplicate(notificationId)) {
            return true
        }
        markProcessed(notificationId)
        return false
    }

    /**
     * Suspend version of checkAndMarkProcessed, performing non-blocking async checks against memory, prefs, and Room.
     */
    suspend fun checkAndMarkProcessedSuspend(notificationId: String): Boolean {
        if (isDuplicateSuspend(notificationId)) {
            return true
        }
        markProcessed(notificationId)
        return false
    }

    /**
     * Non-blocking suspend check against memory, SharedPreferences, and Room database.
     */
    suspend fun isDuplicateSuspend(notificationId: String): Boolean {
        if (notificationId.isBlank()) {
            return false
        }
        if (memoryCache.contains(notificationId)) {
            return true
        }
        val diskKey = KEY_PREFIX + notificationId
        if (sharedPreferences.contains(diskKey)) {
            memoryCache.add(notificationId)
            return true
        }
        val dao = notificationDaoProvider()
        if (dao != null) {
            try {
                if (dao.getNotificationById(notificationId) != null) {
                    memoryCache.add(notificationId)
                    sharedPreferences.edit().putLong(diskKey, System.currentTimeMillis()).apply()
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "Error in isDuplicateSuspend querying Room: ${e.message}")
            }
        }
        return false
    }

    /**
     * Synchronous check against memory cache and persistent SharedPreferences disk store.
     * Enqueues non-blocking Room lookups without calling runBlocking.
     */
    fun isDuplicate(notificationId: String): Boolean {
        if (notificationId.isBlank()) {
            return false
        }

        // 1. Check in-memory cache
        if (memoryCache.contains(notificationId)) {
            Log.d(TAG, "Notification '$notificationId' found in in-memory cache. Duplicate detected.")
            return true
        }

        // 2. Check persistent disk SharedPreferences
        val diskKey = KEY_PREFIX + notificationId
        if (sharedPreferences.contains(diskKey)) {
            memoryCache.add(notificationId)
            Log.d(TAG, "Notification '$notificationId' found in SharedPreferences. Duplicate detected.")
            return true
        }

        // 3. Asynchronously check Room without stalling the current thread
        try {
            val dao = notificationDaoProvider()
            if (dao != null) {
                dedupScope.launch {
                    try {
                        if (dao.getNotificationById(notificationId) != null) {
                            memoryCache.add(notificationId)
                            sharedPreferences.edit().putLong(diskKey, System.currentTimeMillis()).apply()
                        }
                    } catch (_: Exception) {}
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error querying Room for deduplication: ${e.message}")
        }

        return false
    }

    /**
     * Marks a notification as processed.
     *
     * Persists the ID to the in-memory cache and SharedPreferences on disk.
     */
    fun markProcessed(notificationId: String) {
        if (notificationId.isBlank()) {
            return
        }

        // In-memory cache update
        synchronized(memoryCache) {
            if (memoryCache.size >= MAX_MEMORY_ENTRIES) {
                val firstKey = memoryCache.firstOrNull()
                if (firstKey != null) {
                    memoryCache.remove(firstKey)
                }
            }
            memoryCache.add(notificationId)
        }

        // Persistent disk update
        val diskKey = KEY_PREFIX + notificationId
        sharedPreferences.edit().putLong(diskKey, System.currentTimeMillis()).apply()

        pruneDiskStoreIfNeeded()
    }

    private fun pruneDiskStoreIfNeeded() {
        try {
            val allEntries = sharedPreferences.all
            if (allEntries.size > MAX_DISK_ENTRIES) {
                val cutoff = System.currentTimeMillis() - PRUNE_THRESHOLD_DAYS_MS
                val editor = sharedPreferences.edit()
                for ((key, value) in allEntries) {
                    if (key.startsWith(KEY_PREFIX) && (value as? Long ?: 0L) < cutoff) {
                        editor.remove(key)
                    }
                }
                editor.apply()
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed pruning disk entries: ${e.message}")
        }
    }

    /**
     * Clears cached state for unit testing purposes.
     */
    fun clearForTesting() {
        memoryCache.clear()
        sharedPreferences.edit().clear().apply()
    }
}
