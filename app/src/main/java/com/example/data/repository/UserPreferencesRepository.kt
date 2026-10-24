package com.example.data.repository

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "user_prefs")

class UserPreferencesRepository(private val context: Context) {
    private val ONBOARDING_COMPLETED = booleanPreferencesKey("onboarding_completed")
    private val IS_GUEST = booleanPreferencesKey("is_guest")
    private val IS_LOGGED_IN = booleanPreferencesKey("is_logged_in")
    
    // New Settings Keys
    private val THEME_MODE = intPreferencesKey("theme_mode") // 0 = System, 1 = Light, 2 = Dark
    private val PRIMARY_COLOR = intPreferencesKey("primary_color") // 0 = Default(Red), 1 = Blue, 2 = Green, 3 = Purple, 4 = Orange
    private val APP_LANGUAGE = stringPreferencesKey("app_language") // "system", "en", "ar"
    private val START_SCREEN = stringPreferencesKey("start_screen") // "home", "search", "downloads", "settings"

    
    private val MAX_CONCURRENT_DOWNLOADS = intPreferencesKey("max_concurrent_downloads")
    private val MAX_SEGMENTS = intPreferencesKey("max_segments")
    private val DOWNLOAD_NETWORK = intPreferencesKey("download_network") // 0=All, 1=WiFi

    private val PLAYBACK_SEEK_DURATION = intPreferencesKey("playback_seek_duration") // 5, 10 (default), 15
    private val PLAYBACK_CONTROLS_TIMEOUT = intPreferencesKey("playback_controls_timeout") // 5, 10 (default), 15, 30
    private val USER_BIO = stringPreferencesKey("user_bio")
    private val CUSTOM_AVATAR_URI = stringPreferencesKey("custom_avatar_uri")
    private val BLOCKED_USERS = stringSetPreferencesKey("blocked_users")
    private val FRIEND_REQUESTS = stringSetPreferencesKey("friend_requests")
    private val GUEST_MIGRATION_UID = stringPreferencesKey("guest_migration_uid")

    val onboardingCompleted: Flow<Boolean> = context.dataStore.data.map { it[ONBOARDING_COMPLETED] ?: false }
    val isGuest: Flow<Boolean> = context.dataStore.data.map { it[IS_GUEST] ?: false }
    val isLoggedIn: Flow<Boolean> = context.dataStore.data.map { it[IS_LOGGED_IN] ?: false }
    val guestMigrationUid: Flow<String?> = context.dataStore.data.map { it[GUEST_MIGRATION_UID] }
    
    val themeMode: Flow<Int> = context.dataStore.data.map { it[THEME_MODE] ?: 0 }
    val primaryColor: Flow<Int> = context.dataStore.data.map { it[PRIMARY_COLOR] ?: 0 }
    val appLanguage: Flow<String> = context.dataStore.data.map { it[APP_LANGUAGE] ?: "system" }
    val startScreen: Flow<String> = context.dataStore.data.map { it[START_SCREEN] ?: "home" }

    val maxConcurrentDownloads: Flow<Int> = context.dataStore.data.map { it[MAX_CONCURRENT_DOWNLOADS] ?: 3 }
    val maxSegments: Flow<Int> = context.dataStore.data.map { it[MAX_SEGMENTS] ?: 8 }
    val downloadNetwork: Flow<Int> = context.dataStore.data.map { it[DOWNLOAD_NETWORK] ?: 0 }

    val playbackSeekDuration: Flow<Int> = context.dataStore.data.map { it[PLAYBACK_SEEK_DURATION] ?: 10 }
    val playbackControlsTimeout: Flow<Int> = context.dataStore.data.map { it[PLAYBACK_CONTROLS_TIMEOUT] ?: 10 }
    val userBio: Flow<String> = context.dataStore.data.map { it[USER_BIO] ?: "Movie & Anime Lover\nEnjoying great stories ✨" }
    val customAvatarUri: Flow<String> = context.dataStore.data.map { it[CUSTOM_AVATAR_URI] ?: "" }
    val blockedUsers: Flow<Set<String>> = context.dataStore.data.map { it[BLOCKED_USERS] ?: emptySet() }
    val friendRequests: Flow<Set<String>> = context.dataStore.data.map { it[FRIEND_REQUESTS] ?: emptySet() }


    suspend fun saveOnboardingCompleted(completed: Boolean) {
        context.dataStore.edit { it[ONBOARDING_COMPLETED] = completed }
    }
    suspend fun saveIsGuest(isGuest: Boolean) {
        context.dataStore.edit { it[IS_GUEST] = isGuest }
    }
    suspend fun saveIsLoggedIn(isLoggedIn: Boolean) {
        context.dataStore.edit { it[IS_LOGGED_IN] = isLoggedIn }
    }
    suspend fun saveThemeMode(mode: Int) {
        context.dataStore.edit { it[THEME_MODE] = mode }
    }
    suspend fun savePrimaryColor(color: Int) {
        context.dataStore.edit { it[PRIMARY_COLOR] = color }
    }
    suspend fun saveAppLanguage(lang: String) {
        context.dataStore.edit { it[APP_LANGUAGE] = lang }
    }
    suspend fun saveStartScreen(screen: String) {
        context.dataStore.edit { it[START_SCREEN] = screen }
    }

    suspend fun saveMaxConcurrentDownloads(count: Int) {
        context.dataStore.edit { it[MAX_CONCURRENT_DOWNLOADS] = count }
    }
    suspend fun saveMaxSegments(count: Int) {
        context.dataStore.edit { it[MAX_SEGMENTS] = count }
    }
    suspend fun saveDownloadNetwork(network: Int) {
        context.dataStore.edit { it[DOWNLOAD_NETWORK] = network }
    }

    suspend fun savePlaybackSeekDuration(seconds: Int) {
        context.dataStore.edit { it[PLAYBACK_SEEK_DURATION] = seconds }
    }

    suspend fun savePlaybackControlsTimeout(seconds: Int) {
        context.dataStore.edit { it[PLAYBACK_CONTROLS_TIMEOUT] = seconds }
    }

    suspend fun saveUserBio(bio: String) {
        context.dataStore.edit { it[USER_BIO] = bio }
    }

    suspend fun saveCustomAvatarUri(uri: String) {
        context.dataStore.edit { it[CUSTOM_AVATAR_URI] = uri }
    }

    suspend fun saveCustomAvatarFromUri(uri: android.net.Uri) {
        kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            try {
                val file = java.io.File(context.filesDir, "custom_avatar.jpg")
                context.contentResolver.openInputStream(uri)?.use { input ->
                    file.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                saveCustomAvatarUri(file.absolutePath)
            } catch (e: Exception) {
                saveCustomAvatarUri(uri.toString())
            }
        }
    }

    suspend fun blockUser(userId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[BLOCKED_USERS] ?: emptySet()
            prefs[BLOCKED_USERS] = current + userId
        }
    }

    suspend fun unblockUser(userId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[BLOCKED_USERS] ?: emptySet()
            prefs[BLOCKED_USERS] = current - userId
        }
    }

    suspend fun sendFriendRequest(userId: String) {
        context.dataStore.edit { prefs ->
            val current = prefs[FRIEND_REQUESTS] ?: emptySet()
            prefs[FRIEND_REQUESTS] = current + userId
        }
    }

    suspend fun setGuestMigrationUid(uid: String) {
        context.dataStore.edit { it[GUEST_MIGRATION_UID] = uid }
    }

    suspend fun clearGuestMigrationUid() {
        context.dataStore.edit { it.remove(GUEST_MIGRATION_UID) }
    }

    suspend fun clearUserScopedPreferences() {
        context.dataStore.edit { prefs ->
            prefs.remove(USER_BIO)
            prefs.remove(CUSTOM_AVATAR_URI)
            prefs.remove(BLOCKED_USERS)
            prefs.remove(FRIEND_REQUESTS)
            prefs.remove(IS_LOGGED_IN)
            prefs.remove(IS_GUEST)
            prefs.remove(GUEST_MIGRATION_UID)
        }
    }
}