package com.example.data.sync

import android.content.Context
import android.util.Log
import com.example.data.db.AppDatabase
import com.example.data.model.HistoryItem
import com.example.data.model.LibraryItem
import com.example.data.model.NotificationPreferences
import com.example.data.model.WatchedEpisode
import com.example.data.repository.NotificationPreferencesRepository
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

class CloudSyncManager(private val context: Context) {
    private val db = AppDatabase.getDatabase(context)
    private val firestore = FirebaseFirestore.getInstance()

    companion object {
        private const val TAG = "CloudSyncManager"
        private var libraryListener: ListenerRegistration? = null
        private var historyListener: ListenerRegistration? = null
        private var watchedEpisodesListener: ListenerRegistration? = null
        private var notificationPrefsListener: ListenerRegistration? = null
        private val syncScope = CoroutineScope(Dispatchers.IO)

        fun isSyncActive(): Boolean = libraryListener != null || historyListener != null || watchedEpisodesListener != null || notificationPrefsListener != null

        fun stopRealtimeSync() {
            libraryListener?.remove()
            libraryListener = null
            historyListener?.remove()
            historyListener = null
            watchedEpisodesListener?.remove()
            watchedEpisodesListener = null
            notificationPrefsListener?.remove()
            notificationPrefsListener = null
            Log.d(TAG, "Stopped all real-time cloud sync listeners")
        }
    }

    /**
     * Starts persistent real-time snapshot listeners for library, history, watched episodes, and preferences.
     * Guarantees that cloud changes appear inside the app immediately without requiring app restart.
     */
    fun startRealtimeSync(userId: String) {
        if (userId.isBlank()) {
            stopRealtimeSync()
            return
        }

        stopRealtimeSync()
        val userDoc = firestore.collection("users").document(userId)

        // 1. Real-time Library Sync
        libraryListener = userDoc.collection("library")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Library listener error: ", error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val libraryItems = snapshot.documents.mapNotNull { doc ->
                        val data = doc.data ?: return@mapNotNull null
                        LibraryItem.fromFirestoreMap(doc.id, data)
                    }
                    syncScope.launch {
                        libraryItems.forEach { item ->
                            val local = db.libraryDao().getItemById(item.libraryId)
                            val resolvedMode = if (item.variantMode.isNotBlank() && item.variantMode != "subtitled") {
                                item.variantMode
                            } else if (local != null && local.variantMode.isNotBlank() && local.variantMode != "subtitled") {
                                local.variantMode
                            } else {
                                item.variantMode.ifBlank { "subtitled" }
                            }
                            val canonicalLibId = com.example.data.model.LibraryIdentity.createLibraryId(item.contentType, item.tmdbId, resolvedMode)
                            val merged = if (local != null) {
                                item.copy(
                                    libraryId = canonicalLibId,
                                    posterUrl = if (item.posterUrl.isNotBlank()) item.posterUrl else local.posterUrl,
                                    variantMode = resolvedMode,
                                    providerId = if (item.providerId.isNotBlank()) item.providerId else local.providerId,
                                    title = if (item.title.isNotBlank()) item.title else local.title
                                )
                            } else {
                                if (item.libraryId != canonicalLibId) item.copy(libraryId = canonicalLibId, variantMode = resolvedMode) else item
                            }
                            db.libraryDao().insertItem(merged)
                        }
                    }
                }
            }

        // 2. Real-time History Sync
        historyListener = userDoc.collection("history")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "History listener error: ", error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val historyItems = snapshot.toObjects(HistoryItem::class.java)
                    syncScope.launch {
                        historyItems.filter { it.durationMillis > 0L && it.positionMillis >= 10000L }
                            .forEach { db.historyDao().insertHistory(it) }
                    }
                }
            }

        // 3. Real-time Watched Episodes Sync
        watchedEpisodesListener = userDoc.collection("watched_episodes")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Watched episodes listener error: ", error)
                    return@addSnapshotListener
                }
                if (snapshot != null) {
                    val episodes = snapshot.documents.mapNotNull { doc ->
                        try {
                            val ep = doc.toObject(WatchedEpisode::class.java)
                            if (ep != null && ep.id.isNotBlank()) ep else WatchedEpisode(id = doc.id)
                        } catch (e: Exception) {
                            WatchedEpisode(id = doc.id)
                        }
                    }
                    syncScope.launch {
                        episodes.forEach { db.watchedEpisodeDao().insert(it) }
                    }
                }
            }

        // 4. Real-time Notification Preferences Sync
        notificationPrefsListener = userDoc.collection("settings")
            .document("notifications")
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Notification preferences listener error: ", error)
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    val cloudPrefs = NotificationPreferences.fromFirestoreMap(snapshot.data)
                    syncScope.launch {
                        try {
                            NotificationPreferencesRepository(context).updateFromCloud(cloudPrefs)
                        } catch (e: Exception) {
                            Log.w(TAG, "Failed updating preferences from realtime sync: ${e.message}")
                        }
                    }
                }
            }

        Log.d(TAG, "Started real-time cloud sync listeners for user $userId")
    }

    suspend fun syncFromCloud(userId: String) {
        val userDoc = firestore.collection("users").document(userId)
        val userPrefs = com.example.data.repository.UserPreferencesRepository(context)
        
        try {
            // 1. PHASE 05T: Migrate Guest Local Data -> Authenticated Account
            migrateGuestDataToUser(userId)

            // 2. Pull cloud library data to local
            val librarySnapshot = userDoc.collection("library").get().await()
            val libraryItems = librarySnapshot.documents.mapNotNull { doc ->
                val data = doc.data ?: return@mapNotNull null
                LibraryItem.fromFirestoreMap(doc.id, data)
            }
            libraryItems.forEach { item ->
                val local = db.libraryDao().getItemById(item.libraryId)
                val resolvedMode = if (item.variantMode.isNotBlank() && item.variantMode != "subtitled") {
                    item.variantMode
                } else if (local != null && local.variantMode.isNotBlank() && local.variantMode != "subtitled") {
                    local.variantMode
                } else {
                    item.variantMode.ifBlank { "subtitled" }
                }
                val canonicalLibId = com.example.data.model.LibraryIdentity.createLibraryId(item.contentType, item.tmdbId, resolvedMode)
                val merged = if (local != null) {
                    item.copy(
                        libraryId = canonicalLibId,
                        posterUrl = if (item.posterUrl.isNotBlank()) item.posterUrl else local.posterUrl,
                        variantMode = resolvedMode,
                        providerId = if (item.providerId.isNotBlank()) item.providerId else local.providerId,
                        title = if (item.title.isNotBlank()) item.title else local.title
                    )
                } else {
                    if (item.libraryId != canonicalLibId) item.copy(libraryId = canonicalLibId, variantMode = resolvedMode) else item
                }
                db.libraryDao().insertItem(merged)
            }

            // 3. Pull cloud history to local ensuring most advanced progress is preserved
            val historySnapshot = userDoc.collection("history").get().await()
            val historyItems = historySnapshot.toObjects(HistoryItem::class.java)
            historyItems.filter { it.durationMillis > 0L && it.positionMillis >= 10000L }.forEach { db.historyDao().insertHistory(it) }

            // 4. Watched Episodes
            val episodesSnapshot = userDoc.collection("watched_episodes").get().await()
            val episodes = episodesSnapshot.documents.mapNotNull { doc ->
                try {
                    val ep = doc.toObject(WatchedEpisode::class.java)
                    if (ep != null && ep.id.isNotBlank()) ep else WatchedEpisode(id = doc.id)
                } catch (e: Exception) {
                    WatchedEpisode(id = doc.id)
                }
            }
            episodes.forEach { db.watchedEpisodeDao().insert(it) }

            // 5. Notification Preferences Sync
            try {
                com.example.data.repository.NotificationPreferencesRepository(context).syncWithFirestore(userId)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // 6. Start real-time listeners right after initial sync
            startRealtimeSync(userId)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * PHASE 05T: Migrates guest local data into the authenticated user's account.
     * Rules:
     * 1. Strictly ONE-WAY: Guest Local Data -> Authenticated Account.
     * 2. Deterministic Merge:
     *    - Watch progress / history: advance progress, taking MAX(guest.positionMillis, cloud.positionMillis).
     *      Progress NEVER regresses.
     *    - Watched episodes: union of guest and cloud.
     * 3. Idempotent: safe to retry, tagged by migration marker.
     * 4. Economic data is NEVER migrated (points, subscriptions, transactions stay 0/cloud authoritative).
     * 5. No cross-user leakage.
     */
    suspend fun migrateGuestDataToUser(userId: String) {
        if (userId.isBlank()) return
        val userPrefs = com.example.data.repository.UserPreferencesRepository(context)
        val alreadyMigratedTo = userPrefs.guestMigrationUid.firstOrNull()
        if (alreadyMigratedTo == userId) {
            // Already migrated to this user account
            return
        }

        val userDoc = firestore.collection("users").document(userId)

        try {
            // 1. Fetch current local data accumulated while in guest mode
            val localHistory = db.historyDao().getAllHistory().firstOrNull() ?: emptyList()
            val localEpisodes = db.watchedEpisodeDao().getAllWatched().firstOrNull() ?: emptyList()

            // 2. Fetch cloud history to perform deterministic merge
            val cloudHistorySnapshot = userDoc.collection("history").get().await()
            val cloudHistoryItems = cloudHistorySnapshot.toObjects(HistoryItem::class.java).associateBy { it.id }

            // Merge History: for each local guest item, merge with cloud item using max progress
            val mergedHistoryMap = mutableMapOf<String, HistoryItem>()
            cloudHistoryItems.forEach { (id, item) -> mergedHistoryMap[id] = item }

            for (guestItem in localHistory) {
                val cloudItem = mergedHistoryMap[guestItem.id]
                if (cloudItem != null) {
                    val maxPosition = maxOf(guestItem.positionMillis, cloudItem.positionMillis)
                    val maxTimestamp = maxOf(guestItem.timestamp, cloudItem.timestamp)
                    val validDuration = if (cloudItem.durationMillis > 0L) cloudItem.durationMillis else guestItem.durationMillis
                    val resolvedTitle = if (cloudItem.title.isNotBlank()) cloudItem.title else guestItem.title
                    val resolvedPoster = if (cloudItem.posterUrl.isNotBlank()) cloudItem.posterUrl else guestItem.posterUrl
                    val merged = cloudItem.copy(
                        positionMillis = maxPosition,
                        durationMillis = validDuration,
                        timestamp = maxTimestamp,
                        title = resolvedTitle,
                        posterUrl = resolvedPoster,
                        isMovie = cloudItem.isMovie
                    )
                    mergedHistoryMap[guestItem.id] = merged
                } else {
                    mergedHistoryMap[guestItem.id] = guestItem
                }
            }

            // Write merged history items back to Firestore and local Room database
            for ((id, item) in mergedHistoryMap) {
                if (item.durationMillis > 0L && item.positionMillis >= 10000L) {
                    userDoc.collection("history").document(id).set(item, com.google.firebase.firestore.SetOptions.merge())
                    db.historyDao().insertHistory(item)
                }
            }

            // Watched Episodes: union of guest and cloud
            val cloudEpisodesSnapshot = userDoc.collection("watched_episodes").get().await()
            val cloudEpisodeIds = cloudEpisodesSnapshot.documents.map { it.id }.toSet()
            val mergedEpisodeIds = cloudEpisodeIds + localEpisodes.map { it.id }.filter { it.isNotBlank() }

            for (epId in mergedEpisodeIds) {
                val ep = WatchedEpisode(id = epId)
                userDoc.collection("watched_episodes").document(epId).set(ep, com.google.firebase.firestore.SetOptions.merge())
                db.watchedEpisodeDao().insert(ep)
            }

            // Persist migration completion marker for this user UID
            userPrefs.setGuestMigrationUid(userId)
            Log.d(TAG, "Successfully executed Phase 05T guest data migration for user $userId")
        } catch (e: Exception) {
            Log.w(TAG, "Error during guest data migration: ${e.message}")
        }
    }

    suspend fun clearLocalData() {
        stopRealtimeSync()
        db.libraryDao().clearAll()
        db.historyDao().clearAll()
        db.watchedEpisodeDao().clearAll()
        db.notificationDao().clearAll()
        db.supportDao().clearMessages()
        try {
            com.example.data.repository.UserPreferencesRepository(context).clearUserScopedPreferences()
            com.example.data.repository.NotificationPreferencesRepository(context).resetToDefaults()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
