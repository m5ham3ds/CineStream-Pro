package com.example.data.repository

import android.content.Context
import android.util.Log
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import com.example.data.model.NotificationPreferences
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.tasks.await

/**
 * Repository for managing user notification preferences.
 *
 * Local Storage: Jetpack DataStore ("user_prefs") ensures immediate offline responsiveness.
 * Cloud Sync: For authenticated users, preferences are mirrored to Firestore at
 * `/users/{uid}/settings/notifications`.
 *
 * Guest Safety: Guest users write only to DataStore; no unauthenticated writes to /users/{uid}.
 */
class NotificationPreferencesRepository(
    private val context: Context,
    private val dataStore: DataStore<Preferences> = context.dataStore,
    private val firestoreProvider: () -> FirebaseFirestore? = {
        try {
            FirebaseFirestore.getInstance()
        } catch (_: Exception) {
            null
        }
    },
    private val authProvider: () -> FirebaseAuth? = {
        try {
            FirebaseAuth.getInstance()
        } catch (_: Exception) {
            null
        }
    }
) {

    private val firestore: FirebaseFirestore? get() = firestoreProvider()
    private val auth: FirebaseAuth? get() = authProvider()

    companion object {
        private const val TAG = "NotifPrefsRepo"

        val KEY_NOTIFICATIONS_ENABLED = booleanPreferencesKey("notif_enabled")
        val KEY_ANNOUNCEMENTS_ENABLED = booleanPreferencesKey("notif_announcements")
        val KEY_APP_UPDATES_ENABLED = booleanPreferencesKey("notif_app_updates")
        val KEY_MAINTENANCE_ENABLED = booleanPreferencesKey("notif_maintenance")
        val KEY_NEW_MOVIES_ENABLED = booleanPreferencesKey("notif_new_movies")
        val KEY_NEW_TV_SERIES_ENABLED = booleanPreferencesKey("notif_new_tv_series")
        val KEY_NEW_ANIME_ENABLED = booleanPreferencesKey("notif_new_anime")
        val KEY_NEW_TV_EPISODES_ENABLED = booleanPreferencesKey("notif_new_tv_episodes")
        val KEY_NEW_ANIME_EPISODES_ENABLED = booleanPreferencesKey("notif_new_anime_episodes")
        val KEY_NEW_TV_SEASONS_ENABLED = booleanPreferencesKey("notif_new_tv_seasons")
        val KEY_NEW_ANIME_SEASONS_ENABLED = booleanPreferencesKey("notif_new_anime_seasons")
    }

    /**
     * Flow of current notification preferences, defaulting all toggles to true.
     */
    val preferencesFlow: Flow<NotificationPreferences> = dataStore.data.map { prefs ->
        NotificationPreferences(
            notificationsEnabled = prefs[KEY_NOTIFICATIONS_ENABLED] ?: true,
            announcementsEnabled = prefs[KEY_ANNOUNCEMENTS_ENABLED] ?: true,
            appUpdatesEnabled = prefs[KEY_APP_UPDATES_ENABLED] ?: true,
            maintenanceEnabled = prefs[KEY_MAINTENANCE_ENABLED] ?: true,
            newMoviesEnabled = prefs[KEY_NEW_MOVIES_ENABLED] ?: true,
            newTvSeriesEnabled = prefs[KEY_NEW_TV_SERIES_ENABLED] ?: true,
            newAnimeEnabled = prefs[KEY_NEW_ANIME_ENABLED] ?: true,
            newTvEpisodesEnabled = prefs[KEY_NEW_TV_EPISODES_ENABLED] ?: true,
            newAnimeEpisodesEnabled = prefs[KEY_NEW_ANIME_EPISODES_ENABLED] ?: true,
            newTvSeasonsEnabled = prefs[KEY_NEW_TV_SEASONS_ENABLED] ?: true,
            newAnimeSeasonsEnabled = prefs[KEY_NEW_ANIME_SEASONS_ENABLED] ?: true
        )
    }

    /**
     * Fetches current snapshot of preferences.
     */
    suspend fun getPreferences(): NotificationPreferences {
        return preferencesFlow.first()
    }

    suspend fun updateNotificationsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_NOTIFICATIONS_ENABLED] = enabled }
        syncToCloudIfAuthenticated()
    }

    suspend fun updateAnnouncementsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_ANNOUNCEMENTS_ENABLED] = enabled }
        syncToCloudIfAuthenticated()
    }

    suspend fun updateAppUpdatesEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_APP_UPDATES_ENABLED] = enabled }
        syncToCloudIfAuthenticated()
    }

    suspend fun updateMaintenanceEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_MAINTENANCE_ENABLED] = enabled }
        syncToCloudIfAuthenticated()
    }

    suspend fun updateNewMoviesEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_NEW_MOVIES_ENABLED] = enabled }
        syncToCloudIfAuthenticated()
    }

    suspend fun updateNewTvSeriesEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_NEW_TV_SERIES_ENABLED] = enabled }
        syncToCloudIfAuthenticated()
    }

    suspend fun updateNewAnimeEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_NEW_ANIME_ENABLED] = enabled }
        syncToCloudIfAuthenticated()
    }

    suspend fun updateNewTvEpisodesEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_NEW_TV_EPISODES_ENABLED] = enabled }
        syncToCloudIfAuthenticated()
    }

    suspend fun updateNewAnimeEpisodesEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_NEW_ANIME_EPISODES_ENABLED] = enabled }
        syncToCloudIfAuthenticated()
    }

    suspend fun updateNewTvSeasonsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_NEW_TV_SEASONS_ENABLED] = enabled }
        syncToCloudIfAuthenticated()
    }

    suspend fun updateNewAnimeSeasonsEnabled(enabled: Boolean) {
        dataStore.edit { it[KEY_NEW_ANIME_SEASONS_ENABLED] = enabled }
        syncToCloudIfAuthenticated()
    }

    /**
     * Writes all values from a model into DataStore and triggers a cloud sync.
     */
    suspend fun updateAll(preferences: NotificationPreferences) {
        dataStore.edit { prefs ->
            prefs[KEY_NOTIFICATIONS_ENABLED] = preferences.notificationsEnabled
            prefs[KEY_ANNOUNCEMENTS_ENABLED] = preferences.announcementsEnabled
            prefs[KEY_APP_UPDATES_ENABLED] = preferences.appUpdatesEnabled
            prefs[KEY_MAINTENANCE_ENABLED] = preferences.maintenanceEnabled
            prefs[KEY_NEW_MOVIES_ENABLED] = preferences.newMoviesEnabled
            prefs[KEY_NEW_TV_SERIES_ENABLED] = preferences.newTvSeriesEnabled
            prefs[KEY_NEW_ANIME_ENABLED] = preferences.newAnimeEnabled
            prefs[KEY_NEW_TV_EPISODES_ENABLED] = preferences.newTvEpisodesEnabled
            prefs[KEY_NEW_ANIME_EPISODES_ENABLED] = preferences.newAnimeEpisodesEnabled
            prefs[KEY_NEW_TV_SEASONS_ENABLED] = preferences.newTvSeasonsEnabled
            prefs[KEY_NEW_ANIME_SEASONS_ENABLED] = preferences.newAnimeSeasonsEnabled
        }
        syncToCloudIfAuthenticated()
    }

    /**
     * Resets local DataStore preferences to clean defaults.
     * Invoked upon user sign-out to prevent cross-account preference leakage.
     */
    suspend fun resetToDefaults() {
        dataStore.edit { prefs ->
            prefs.remove(KEY_NOTIFICATIONS_ENABLED)
            prefs.remove(KEY_ANNOUNCEMENTS_ENABLED)
            prefs.remove(KEY_APP_UPDATES_ENABLED)
            prefs.remove(KEY_MAINTENANCE_ENABLED)
            prefs.remove(KEY_NEW_MOVIES_ENABLED)
            prefs.remove(KEY_NEW_TV_SERIES_ENABLED)
            prefs.remove(KEY_NEW_ANIME_ENABLED)
            prefs.remove(KEY_NEW_TV_EPISODES_ENABLED)
            prefs.remove(KEY_NEW_ANIME_EPISODES_ENABLED)
            prefs.remove(KEY_NEW_TV_SEASONS_ENABLED)
            prefs.remove(KEY_NEW_ANIME_SEASONS_ENABLED)
        }
    }

    /**
     * Synchronizes preferences with Firestore for a specific user ID.
     * If cloud document exists, pulls from cloud to local.
     * If cloud document does not exist, pushes current local preferences to cloud.
     */
    suspend fun syncWithFirestore(userId: String) {
        if (userId.isBlank()) return
        val db = firestore ?: return
        try {
            val docRef = db.collection("users")
                .document(userId)
                .collection("settings")
                .document("notifications")

            val snapshot = docRef.get().await()
            if (snapshot.exists()) {
                val cloudPrefs = NotificationPreferences.fromFirestoreMap(snapshot.data)
                // Save cloud preferences to local DataStore
                updateFromCloud(cloudPrefs)
                Log.d(TAG, "Successfully synced notification preferences from cloud for user $userId")
            } else {
                // Upload current local preferences to create the cloud document
                val currentLocal = getPreferences()
                docRef.set(currentLocal.toFirestoreMap()).await()
                Log.d(TAG, "Initialized cloud notification preferences for user $userId")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed syncing notification preferences with Firestore: ${e.message}")
        }
    }

    suspend fun updateFromCloud(cloudPrefs: NotificationPreferences) {
        dataStore.edit { prefs ->
            prefs[KEY_NOTIFICATIONS_ENABLED] = cloudPrefs.notificationsEnabled
            prefs[KEY_ANNOUNCEMENTS_ENABLED] = cloudPrefs.announcementsEnabled
            prefs[KEY_APP_UPDATES_ENABLED] = cloudPrefs.appUpdatesEnabled
            prefs[KEY_MAINTENANCE_ENABLED] = cloudPrefs.maintenanceEnabled
            prefs[KEY_NEW_MOVIES_ENABLED] = cloudPrefs.newMoviesEnabled
            prefs[KEY_NEW_TV_SERIES_ENABLED] = cloudPrefs.newTvSeriesEnabled
            prefs[KEY_NEW_ANIME_ENABLED] = cloudPrefs.newAnimeEnabled
            prefs[KEY_NEW_TV_EPISODES_ENABLED] = cloudPrefs.newTvEpisodesEnabled
            prefs[KEY_NEW_ANIME_EPISODES_ENABLED] = cloudPrefs.newAnimeEpisodesEnabled
            prefs[KEY_NEW_TV_SEASONS_ENABLED] = cloudPrefs.newTvSeasonsEnabled
            prefs[KEY_NEW_ANIME_SEASONS_ENABLED] = cloudPrefs.newAnimeSeasonsEnabled
        }
    }

    private suspend fun syncToCloudIfAuthenticated() {
        val user = auth?.currentUser
        val db = firestore ?: return
        if (user != null && !user.isAnonymous && user.uid.isNotBlank()) {
            try {
                val currentPrefs = getPreferences()
                db.collection("users")
                    .document(user.uid)
                    .collection("settings")
                    .document("notifications")
                    .set(currentPrefs.toFirestoreMap())
                    .await()
            } catch (e: Exception) {
                Log.w(TAG, "Offline or error uploading preferences to Firestore: ${e.message}")
            }
        }
    }
}
