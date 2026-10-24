package com.example.data.session

import android.content.Context
import android.util.Log
import com.example.data.db.AppDatabase
import com.example.data.notification.FcmTokenManager
import com.example.data.repository.AuthRepository
import com.example.data.repository.NotificationPreferencesRepository
import com.example.data.repository.SocialRepository
import com.example.data.repository.UserPreferencesRepository
import com.example.data.repository.UserSecurityManager
import com.example.data.sync.CloudSyncManager
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

/**
 * PHASE 07.0 / WAVE 2: CANONICAL SESSION MANAGER
 *
 * Establishes the authoritative session boundary, session-generation tracking (epoch),
 * stale asynchronous operation invalidation, and synchronized transition contracts
 * for login, logout, account switching, and account deletion.
 *
 * Guarantees that asynchronous jobs belonging to UID_A cannot mutate UID_B state
 * after a session transition has occurred.
 */
object SessionManager {
    private const val TAG = "SessionManager"
    private val sessionGeneration = AtomicLong(1L)
    private val transitionMutex = Mutex()

    private val _activeUidFlow = MutableStateFlow<String?>(null)
    val activeUidFlow: StateFlow<String?> = _activeUidFlow.asStateFlow()

    val currentGeneration: Long get() = sessionGeneration.get()
    val activeUid: String? get() = _activeUidFlow.value

    data class SessionContext(
        val uid: String,
        val generation: Long
    )

    fun getCurrentSession(): SessionContext? {
        val uid = _activeUidFlow.value ?: return null
        return SessionContext(uid, sessionGeneration.get())
    }

    private var isTestingSession: Boolean = false

    /**
     * Binds a test session directly without launching network sync workers.
     */
    fun bindSessionForTesting(uid: String, gen: Long? = null): Long {
        isTestingSession = true
        val targetGen = gen ?: sessionGeneration.incrementAndGet()
        sessionGeneration.set(targetGen)
        _activeUidFlow.value = uid
        return targetGen
    }

    /**
     * Stale operation validation check.
     * All asynchronous operations that mutate or write user-scoped state
     * MUST call this before and after suspension points.
     */
    fun isSessionValid(expectedUid: String, expectedGeneration: Long): Boolean {
        if (expectedUid.isBlank()) return false
        val currentGen = sessionGeneration.get()
        val currentUid = _activeUidFlow.value
        val isFirebaseActive = try { com.google.firebase.FirebaseApp.getInstance() != null } catch (_: Exception) { false }
        val fbUid = if (isFirebaseActive) {
            try { FirebaseAuth.getInstance().currentUser?.uid } catch (_: Exception) { null }
        } else {
            null
        }
        val fbMatches = if (isTestingSession) {
            true
        } else if (isFirebaseActive && fbUid != null) {
            fbUid == expectedUid
        } else {
            true
        }
        return currentGen == expectedGeneration && currentUid == expectedUid && fbMatches
    }

    /**
     * Invalidates the current session generation immediately without full teardown.
     * Stale in-flight coroutines fail closed immediately.
     */
    fun invalidateSession(): Long {
        isTestingSession = false
        val newGen = sessionGeneration.incrementAndGet()
        _activeUidFlow.value = null
        Log.d(TAG, "Session invalidated. New generation: $newGen")
        return newGen
    }

    /**
     * Creates or binds a session for an authenticated user.
     */
    suspend fun onUserLoggedIn(context: Context, uid: String): Long = transitionMutex.withLock {
        val oldUid = _activeUidFlow.value
        val newGen = sessionGeneration.incrementAndGet()

        // If transitioning from an existing different user, perform complete cleanup first to isolate accounts
        if (!oldUid.isNullOrBlank() && oldUid != uid) {
            Log.i(TAG, "Account switch detected: oldUid=$oldUid -> newUid=$uid. Performing full state isolation.")
            try {
                CloudSyncManager.stopRealtimeSync()
                UserSecurityManager.stopListening()
                UserSecurityManager.reset()
                com.example.data.repository.NotificationRepository.stopListeningAnnouncements()
                com.example.data.repository.EconomyConfigRepository.stopListening()
                SocialRepository.clearCache()
                FcmTokenManager.getInstance(context).onUserSignedOut(oldUid, newGen)
            } catch (e: Exception) {
                Log.w(TAG, "Error cleaning up old session before binding new user: ${e.message}")
            }
            // Clear local user-scoped Room data so previous user's library/history never leaks into the new account
            clearUserScopedLocalData(context)
        }

        _activeUidFlow.value = uid
        Log.d(TAG, "Session created: uid=$uid, generation=$newGen")

        try {
            CloudSyncManager(context).startRealtimeSync(uid)
            FcmTokenManager.getInstance(context).onUserSignedIn(uid, newGen)
        } catch (e: Exception) {
            Log.w(TAG, "Error during session startup sync: ${e.message}")
        }
        newGen
    }

    /**
     * Executes the authoritative logout sequence.
     * 1. Invalidate current session generation
     * 2. Stop/cancel user-scoped async work & listeners
     * 3. Perform required FCM cleanup (while still authenticated in FirebaseAuth)
     * 4. Clear user-scoped local state (Room, DataStore, SharedPreferences, caches)
     * 5. End Firebase Auth session
     */
    suspend fun onUserLoggedOut(context: Context): Long = transitionMutex.withLock {
        val oldUid = _activeUidFlow.value ?: try { FirebaseAuth.getInstance().currentUser?.uid } catch (_: Exception) { null }
        val oldGen = sessionGeneration.incrementAndGet()
        _activeUidFlow.value = null
        Log.d(TAG, "Session invalidated for logout: oldUid=$oldUid, newGeneration=$oldGen")

        // 1. Cancel in-flight pending FCM sync jobs
        try {
            FcmTokenManager.getInstance(context).cancelPendingSync()
        } catch (e: Exception) {
            Log.w(TAG, "Error canceling pending FCM sync: ${e.message}")
        }

        // 2. Stop background listeners and cloud sync
        try {
            CloudSyncManager.stopRealtimeSync()
            UserSecurityManager.stopListening()
            UserSecurityManager.reset()
            com.example.data.repository.NotificationRepository.stopListeningAnnouncements()
            com.example.data.repository.EconomyConfigRepository.stopListening()
        } catch (e: Exception) {
            Log.w(TAG, "Error stopping listeners: ${e.message}")
        }

        // 3. Dissociate FCM token while still authenticated in FirebaseAuth
        if (!oldUid.isNullOrBlank()) {
            try {
                FcmTokenManager.getInstance(context).onUserSignedOut(oldUid, oldGen)
            } catch (e: Exception) {
                Log.w(TAG, "Error dissociating FCM token: ${e.message}")
            }
        }

        // 4. Clear all user-scoped local state
        clearUserScopedLocalData(context)

        // 5. Sign out from Firebase Auth
        try {
            FirebaseAuth.getInstance().signOut()
        } catch (e: Exception) {
            Log.w(TAG, "Error during FirebaseAuth signOut: ${e.message}")
        }

        // 6. Clear repository caches
        AuthRepository.currentUserFlow.value = null
        SocialRepository.clearCache()

        oldGen
    }

    /**
     * Executes atomic account switch from oldUid to newUid.
     * Prevents any state leakage between sessions.
     */
    suspend fun onAccountSwitched(context: Context, newUid: String): Long = transitionMutex.withLock {
        val oldUid = _activeUidFlow.value
        val newGen = sessionGeneration.incrementAndGet()
        Log.d(TAG, "Account switched: oldUid=$oldUid -> newUid=$newUid, generation=$newGen")

        try {
            FcmTokenManager.getInstance(context).cancelPendingSync()
        } catch (_: Exception) {}

        // Invalidate old session work & listeners
        CloudSyncManager.stopRealtimeSync()
        UserSecurityManager.stopListening()
        UserSecurityManager.reset()
        com.example.data.repository.NotificationRepository.stopListeningAnnouncements()
        com.example.data.repository.EconomyConfigRepository.stopListening()

        if (!oldUid.isNullOrBlank()) {
            try {
                FcmTokenManager.getInstance(context).onUserSignedOut(oldUid, newGen)
            } catch (_: Exception) {}
        }

        // Clear local state from old user
        clearUserScopedLocalData(context)
        AuthRepository.currentUserFlow.value = null
        SocialRepository.clearCache()

        // Bind new user
        _activeUidFlow.value = newUid
        try {
            CloudSyncManager(context).startRealtimeSync(newUid)
            FcmTokenManager.getInstance(context).onUserSignedIn(newUid, newGen)
            com.example.data.repository.EconomyConfigRepository.startListening()
        } catch (e: Exception) {
            Log.w(TAG, "Error initializing new user session: ${e.message}")
        }

        newGen
    }

    /**
     * Purges only USER-SCOPED local data.
     * Leaves APP-SHARED settings and public media caches intact.
     */
    suspend fun clearUserScopedLocalData(context: Context) {
        try {
            val db = AppDatabase.getDatabase(context)
            db.libraryDao().clearAll()
            db.historyDao().clearAll()
            db.watchedEpisodeDao().clearAll()
            db.notificationDao().clearAll()
            db.supportDao().clearMessages()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing Room tables: ${e.message}")
        }

        try {
            UserPreferencesRepository(context).clearUserScopedPreferences()
            NotificationPreferencesRepository(context).resetToDefaults()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing user preferences: ${e.message}")
        }

        try {
            val historyPrefs = context.getSharedPreferences("history_dismissed_prefs", Context.MODE_PRIVATE)
            historyPrefs.edit().clear().apply()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing history prefs: ${e.message}")
        }

        try {
            com.example.utils.LastPlaybackStore.clearAll(context)
            com.example.ui.screens.player.PlaybackSyncStore.clearAll()
        } catch (e: Exception) {
            Log.e(TAG, "Error clearing playback stores: ${e.message}")
        }

        SocialRepository.clearCache()
    }
}
