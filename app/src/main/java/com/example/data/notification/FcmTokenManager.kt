package com.example.data.notification

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.example.data.repository.AppStartupManager
import com.example.data.repository.dataStore
import com.example.data.session.SessionManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions
import com.google.firebase.messaging.FirebaseMessaging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await
import java.util.UUID

/**
 * Manages FCM registration tokens, persistent installation IDs,
 * local token caching via DataStore, and synchronization with Firestore
 * under `/users/{uid}/fcmTokens/{installationId}`.
 */
class FcmTokenManager private constructor(private val appContext: Context) {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var syncJob: Job? = null

    @androidx.annotation.VisibleForTesting
    var onPreFirestoreWriteHook: (suspend (uid: String, gen: Long) -> Unit)? = null

    @androidx.annotation.VisibleForTesting
    var firestoreWriterOverride: (suspend (uid: String, installationId: String, data: Map<String, Any>) -> Unit)? = null

    @androidx.annotation.VisibleForTesting
    var firestoreRollbackOverride: (suspend (uid: String, installationId: String) -> Unit)? = null

    @androidx.annotation.VisibleForTesting
    var firestoreDeleterOverride: (suspend (uid: String, installationId: String) -> Unit)? = null

    @androidx.annotation.VisibleForTesting
    fun resetTestHooks() {
        onPreFirestoreWriteHook = null
        firestoreWriterOverride = null
        firestoreRollbackOverride = null
        firestoreDeleterOverride = null
    }

    /**
     * Cancels any pending background sync jobs immediately (e.g. during logout).
     */
    fun cancelPendingSync() {
        syncJob?.cancel()
        syncJob = null
    }

    companion object {
        private const val TAG = "FcmTokenManager"
        private val KEY_INSTALLATION_ID = stringPreferencesKey("fcm_installation_id")
        private val KEY_CACHED_TOKEN = stringPreferencesKey("fcm_cached_token")

        @Volatile
        private var instance: FcmTokenManager? = null

        fun getInstance(context: Context): FcmTokenManager {
            return instance ?: synchronized(this) {
                instance ?: FcmTokenManager(context.applicationContext).also { instance = it }
            }
        }
    }

    /**
     * Obtains or generates a stable, persistent Installation ID for this device.
     * This ensures multi-device support: each physical device/app instance
     * has exactly one document under /users/{uid}/fcmTokens/{installationId}.
     */
    suspend fun getInstallationId(): String {
        return try {
            val existing = appContext.dataStore.data.map { it[KEY_INSTALLATION_ID] }.firstOrNull()
            if (!existing.isNullOrBlank()) {
                existing
            } else {
                val newId = UUID.randomUUID().toString()
                appContext.dataStore.edit { it[KEY_INSTALLATION_ID] = newId }
                newId
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read installationId from DataStore, generating fallback: ${e.message}")
            UUID.randomUUID().toString()
        }
    }

    /**
     * Reads the cached FCM token from DataStore.
     */
    suspend fun getCachedToken(): String? {
        return try {
            appContext.dataStore.data.map { it[KEY_CACHED_TOKEN] }.firstOrNull()
        } catch (e: Exception) {
            Log.w(TAG, "Failed to read cached token: ${e.message}")
            null
        }
    }

    /**
     * Saves the token locally in DataStore.
     */
    suspend fun saveCachedToken(token: String) {
        try {
            appContext.dataStore.edit { it[KEY_CACHED_TOKEN] = token }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to save cached token: ${e.message}")
        }
    }

    /**
     * Synchronizes the FCM token with the device cache and, if a user is authenticated,
     * uploads or updates the document at `/users/{uid}/fcmTokens/{installationId}`.
     *
     * @param token Optional token if already known (e.g. from onNewToken).
     *              If null, it fetches the current token from FirebaseMessaging.
     * @param sessionGeneration Generation epoch to prevent stale async mutations.
     * @param expectedUid The UID expected to own the session.
     */
    suspend fun syncToken(
        token: String? = null,
        sessionGeneration: Long? = null,
        expectedUid: String? = null
    ): Boolean {
        return try {
            val resolvedToken = if (!token.isNullOrBlank()) {
                token
            } else {
                try {
                    FirebaseMessaging.getInstance().token.await()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to retrieve token from FirebaseMessaging: ${e.message}")
                    getCachedToken()
                }
            }

            if (resolvedToken.isNullOrBlank()) {
                Log.d(TAG, "No valid FCM token available to sync.")
                return false
            }

            saveCachedToken(resolvedToken)

            val currentAuth = FirebaseAuth.getInstance().currentUser
            if (currentAuth == null || currentAuth.uid.isBlank()) {
                Log.d(TAG, "User is not logged in; token cached locally for future login.")
                return true
            }

            val targetUid = expectedUid ?: SessionManager.activeUid ?: currentAuth.uid
            if (targetUid != currentAuth.uid) {
                Log.w(TAG, "Mismatch between targetUid ($targetUid) and authenticated UID (${currentAuth.uid}), aborting syncToken")
                return false
            }

            val gen = sessionGeneration ?: SessionManager.currentGeneration
            if (!SessionManager.isSessionValid(targetUid, gen)) {
                Log.w(TAG, "Aborting stale FCM syncToken for $targetUid (generation=$gen mismatch)")
                return false
            }

            val installationId = getInstallationId()
            val appVersion = AppStartupManager.getCurrentVersionName(appContext)
            val deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()
            val osVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

            val doc = FcmTokenDocument(
                token = resolvedToken,
                installationId = installationId,
                platform = "android",
                deviceModel = deviceModel,
                osVersion = osVersion,
                appVersion = appVersion
            )

            // Test hook for suspension point right before Firestore write
            onPreFirestoreWriteHook?.invoke(targetUid, gen)

            if (firestoreWriterOverride != null) {
                // Test override models a first registration unless the test explicitly
                // supplies a pre-existing document through its own fixture.
                firestoreWriterOverride?.invoke(targetUid, installationId, doc.toFirestoreMap(includeCreatedAt = true))
            } else {
                val firestore = FirebaseFirestore.getInstance()
                val tokenRef = firestore.collection("users")
                    .document(targetUid)
                    .collection("fcmTokens")
                    .document(installationId)
                val tokenExists = tokenRef.get().await().exists()
                val payload = doc.toFirestoreMap(includeCreatedAt = !tokenExists)
                tokenRef.set(payload, SetOptions.merge()).await()
            }

            // Post-write verification: ensure session was not invalidated while write was pending
            if (!SessionManager.isSessionValid(targetUid, gen)) {
                Log.w(TAG, "Session invalidated during Firestore write for $targetUid, rolling back stale token record")
                if (firestoreRollbackOverride != null) {
                    firestoreRollbackOverride?.invoke(targetUid, installationId)
                } else {
                    try {
                        FirebaseFirestore.getInstance().collection("users")
                            .document(targetUid)
                            .collection("fcmTokens")
                            .document(installationId)
                            .delete()
                    } catch (_: Exception) {}
                }
                return false
            }

            Log.d(TAG, "Successfully synced FCM token for user $targetUid on installation $installationId (gen=$gen)")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error syncing FCM token: ${e.message}")
            false
        }
    }

    /**
     * Asynchronously triggers a sync from a non-coroutine context, bound to active session.
     */
    fun syncTokenAsync(token: String? = null) {
        val targetUid = SessionManager.activeUid ?: FirebaseAuth.getInstance().currentUser?.uid
        val currentGen = SessionManager.currentGeneration
        syncJob?.cancel()
        syncJob = scope.launch {
            syncToken(token, currentGen, targetUid)
        }
    }

    /**
     * Called when a user successfully signs in. Associates the current device's
     * FCM token with the newly authenticated user.
     */
    suspend fun onUserSignedIn(uid: String, sessionGen: Long? = null) {
        if (uid.isBlank()) return
        val gen = sessionGen ?: SessionManager.currentGeneration
        syncToken(sessionGeneration = gen, expectedUid = uid)
    }

    /**
     * Called when a user signs out.
     * Dissociates the device's token document from the old user in Firestore,
     * but retains the token locally so the next user or guest can use it.
     */
    suspend fun onUserSignedOut(oldUid: String, sessionGen: Long? = null) {
        if (oldUid.isBlank()) return
        cancelPendingSync()
        if (firestoreDeleterOverride != null) {
            val installationId = getInstallationId()
            firestoreDeleterOverride?.invoke(oldUid, installationId)
            return
        }
        val currentAuthUid = FirebaseAuth.getInstance().currentUser?.uid
        if (currentAuthUid.isNullOrBlank() || currentAuthUid != oldUid) {
            Log.w(TAG, "Cannot delete remote FCM token on signout: Auth UID mismatch or already signed out ($currentAuthUid vs $oldUid)")
            return
        }
        try {
            val installationId = getInstallationId()
            val firestore = FirebaseFirestore.getInstance()
            firestore.collection("users")
                .document(oldUid)
                .collection("fcmTokens")
                .document(installationId)
                .delete()
                .await()
            Log.d(TAG, "Successfully dissociated FCM token from user $oldUid for installation $installationId")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to dissociate FCM token on logout: ${e.message}")
        }
    }

    /**
     * Deletes the token both from FirebaseMessaging and locally, if a full reset is requested.
     */
    suspend fun deleteToken(): Boolean {
        return try {
            val currentUid = FirebaseAuth.getInstance().currentUser?.uid
            val installationId = getInstallationId()

            if (!currentUid.isNullOrBlank()) {
                try {
                    FirebaseFirestore.getInstance()
                        .collection("users")
                        .document(currentUid)
                        .collection("fcmTokens")
                        .document(installationId)
                        .delete()
                        .await()
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to delete token document from Firestore: ${e.message}")
                }
            }

            try {
                FirebaseMessaging.getInstance().deleteToken().await()
            } catch (e: Exception) {
                Log.w(TAG, "Failed to deleteToken from FirebaseMessaging: ${e.message}")
            }

            appContext.dataStore.edit { it.remove(KEY_CACHED_TOKEN) }
            Log.d(TAG, "FCM token deleted successfully.")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error deleting FCM token: ${e.message}")
            false
        }
    }

    /**
     * Marks a device token document as inactive in Firestore when unrecoverable
     * delivery errors occur (e.g. UNREGISTERED, INVALID_ARGUMENT, SENDER_ID_MISMATCH).
     */
    suspend fun markTokenInactive(uid: String, installationId: String, reason: String = "UNREGISTERED"): Boolean {
        if (uid.isBlank() || installationId.isBlank()) return false
        val currentAuthUid = FirebaseAuth.getInstance().currentUser?.uid
        if (currentAuthUid.isNullOrBlank() || currentAuthUid != uid) {
            Log.w(TAG, "Cannot mark token inactive: Auth UID mismatch ($currentAuthUid vs $uid)")
            return false
        }
        return try {
            val firestore = FirebaseFirestore.getInstance()
            firestore.collection("users")
                .document(uid)
                .collection("fcmTokens")
                .document(installationId)
                .update(
                    mapOf(
                        "isActive" to false,
                        "deactivationReason" to reason,
                        "updatedAt" to com.google.firebase.firestore.FieldValue.serverTimestamp(),
                        "lastSeenAt" to com.google.firebase.firestore.FieldValue.serverTimestamp()
                    )
                )
                .await()
            Log.d(TAG, "Marked token as inactive for user $uid on installation $installationId (reason=$reason)")
            true
        } catch (e: Exception) {
            Log.w(TAG, "Failed to mark token inactive: ${e.message}")
            false
        }
    }

    /**
     * Determines whether an FCM error code represents a recoverable network/transient issue
     * or a permanent invalidation that requires token deactivation.
     */
    fun isRecoverableFcmError(errorCode: String): Boolean {
        return when (errorCode.uppercase()) {
            "UNREGISTERED", "INVALID_ARGUMENT", "SENDER_ID_MISMATCH" -> false
            "QUOTA_EXCEEDED", "UNAVAILABLE", "INTERNAL" -> true
            else -> false
        }
    }
}
