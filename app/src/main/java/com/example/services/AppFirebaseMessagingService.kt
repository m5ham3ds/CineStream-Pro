package com.example.services

import android.util.Log
import com.example.R
import com.example.data.db.AppDatabase
import com.example.data.notification.FcmNotificationPayload
import com.example.data.notification.FcmPayloadParser
import com.example.data.notification.FcmTargetValidator
import com.example.data.notification.FcmTokenManager
import com.example.data.notification.NotificationDeduplicator
import com.example.utils.NotificationHelper
import com.example.data.model.NotificationPreferences
import com.example.data.notification.NotificationCategoryResolver
import com.example.data.repository.NotificationPreferencesRepository
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking

/**
 * Core Firebase Cloud Messaging service for the application.
 *
 * Responsibilities in Phase 2B:
 * - Listens for token refreshes (`onNewToken`) and forwards them to `FcmTokenManager`.
 * - Receives FCM Data Messages (`onMessageReceived`), parses them via `FcmPayloadParser`.
 * - Validates target (`target = all` vs `target = user`), rejecting mismatched or guest users.
 * - Performs multi-tier deduplication via [NotificationDeduplicator] across memory, disk, and Room.
 * - Persists valid notifications to Room ([AppDatabase]) to update Notification Center and prevent duplicate Firestore sync delivery.
 * - Posts a system notification tray alert via [NotificationHelper.showGeneralNotification].
 */
class AppFirebaseMessagingService : FirebaseMessagingService() {

    companion object {
        private const val TAG = "AppFirebaseMessaging"
    }

    override fun onNewToken(token: String) {
        super.onNewToken(token)
        Log.d(TAG, "New FCM registration token received.")
        // Forward to the Token Management architecture
        FcmTokenManager.getInstance(applicationContext).syncTokenAsync(token)
    }

    override fun onMessageReceived(remoteMessage: RemoteMessage) {
        super.onMessageReceived(remoteMessage)
        try {
            val payload = parseMessage(remoteMessage)
            Log.d(
                TAG,
                "FCM message received. id=${payload.notificationId}, type=${payload.type}, " +
                    "payloadType=${payload.payloadType}, isValid=${payload.isValid}"
            )
            handlePayload(payload)
        } catch (e: Exception) {
            Log.e(TAG, "Error handling FCM message: ${e.message}", e)
        }
    }

    /**
     * Parses the incoming [RemoteMessage] using the centralized [FcmPayloadParser].
     * Separated for easy inspection and unit testing.
     */
    fun parseMessage(remoteMessage: RemoteMessage): FcmNotificationPayload {
        return FcmPayloadParser.parse(remoteMessage)
    }

    /**
     * Processes and delivers a validated [FcmNotificationPayload].
     *
     * Execution Steps:
     * 1. Check validity: drops invalid payloads without crashing or notifying.
     * 2. Check content: ensures at least one of title or body is non-blank.
     * 3. Target validation: validates user targeting against current authenticated state.
     * 4. Preference Gate: checks granular user preferences; complete suppression if disabled (NO Room, NO tray).
     * 5. Deduplication: checks memory, disk SharedPreferences, and Room database.
     * 6. Record & Persist: inserts into Room NotificationDao for Notification Center display.
     * 7. Display: posts system notification tray alert via [NotificationHelper].
     *
     * @return `true` if notification was accepted and handled; `false` if dropped, rejected, or invalid.
     */
    fun handlePayload(
        payload: FcmNotificationPayload,
        currentUidOverride: String? = null,
        isAnonymousOverride: Boolean? = null,
        deduplicatorOverride: NotificationDeduplicator? = null,
        preferencesOverride: NotificationPreferences? = null,
        notificationDaoOverride: com.example.data.db.NotificationDao? = null
    ): Boolean {
        // 1. Check payload validity
        if (!payload.isValid) {
            Log.w(TAG, "Rejecting invalid FCM payload: ${payload.validationErrors.joinToString()}")
            return false
        }

        // 2. Check title/body content
        if (payload.title.isBlank() && payload.body.isBlank()) {
            Log.w(TAG, "Rejecting FCM payload: both title and body are empty.")
            return false
        }

        // 3. Resolve user auth state and validate targeting
        val (currentUid, isAnonymous) = if (currentUidOverride != null || isAnonymousOverride != null) {
            Pair(currentUidOverride, isAnonymousOverride ?: false)
        } else {
            resolveCurrentUser()
        }

        val isTargetValid = FcmTargetValidator.isTargetValid(
            target = payload.target,
            targetUid = payload.targetUid,
            currentUid = currentUid,
            isAnonymous = isAnonymous
        )

        if (!isTargetValid) {
            Log.w(TAG, "FCM payload targeting check failed. target='${payload.target}', targetUid='${payload.targetUid}'")
            return false
        }

        // 4. User preference gate: check granular user preferences BEFORE saving to Room or showing tray alert
        val preferences = preferencesOverride ?: resolveNotificationPreferences()
        val category = NotificationCategoryResolver.resolveCategory(
            type = payload.type,
            event = payload.event,
            isAnime = payload.isAnime
        )
        val isAllowed = NotificationCategoryResolver.isNotificationAllowed(category, preferences)

        val deduplicator = deduplicatorOverride ?: NotificationDeduplicator.getInstance(applicationContext)

        if (!isAllowed) {
            Log.d(TAG, "Notification '${payload.notificationId}' ($category) completely suppressed by user preferences. Not saved to Room, no tray alert.")
            // Mark in deduplicator so duplicate Firestore sync or FCM retry is safely ignored
            deduplicator.checkAndMarkProcessed(payload.notificationId)
            return true
        }

        // 5. Atomic multi-tier deduplication check and mark (thread-safe, avoids race conditions)
        if (deduplicator.checkAndMarkProcessed(payload.notificationId)) {
            Log.d(TAG, "Duplicate FCM message '${payload.notificationId}' detected. Skipping.")
            return false
        }

        // 6. Persist to local Room database (updates Notification Center)
        persistToRoom(payload, notificationDaoOverride)

        // 7. Deliver to system notification tray
        val displayTitle = if (payload.title.isNotBlank()) {
            payload.title.trim()
        } else {
            applicationContext.getString(R.string.app_name)
        }

        val displayBody = if (payload.body.isNotBlank()) {
            payload.body.trim()
        } else {
            displayTitle
        }

        NotificationHelper.showGeneralNotification(
            context = applicationContext,
            id = payload.notificationId,
            title = displayTitle,
            message = displayBody,
            navigateTo = payload.navigateTo,
            movieId = payload.movieId,
            seriesId = payload.seriesId,
            episodeId = payload.episodeId,
            type = payload.type
        )

        Log.d(TAG, "System notification tray alert delivered for id='${payload.notificationId}'")
        return true
    }

    private val serviceScope = kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.IO)

    private fun resolveNotificationPreferences(): NotificationPreferences {
        return try {
            val isMainThread = try {
                android.os.Looper.myLooper() == android.os.Looper.getMainLooper()
            } catch (_: Exception) { false }
            if (isMainThread) {
                NotificationPreferences()
            } else {
                runBlocking(Dispatchers.IO) {
                    NotificationPreferencesRepository(applicationContext).preferencesFlow.first()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve notification preferences: ${e.message}")
            NotificationPreferences()
        }
    }

    private fun resolveCurrentUser(): Pair<String?, Boolean> {
        return try {
            val user = FirebaseAuth.getInstance().currentUser
            if (user == null) {
                Pair(null, true)
            } else {
                val isAnon = user.isAnonymous
                val uid = if (isAnon) null else user.uid
                Pair(uid, isAnon)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Could not resolve FirebaseAuth user: ${e.message}")
            Pair(null, true)
        }
    }

    private fun persistToRoom(payload: FcmNotificationPayload, daoOverride: com.example.data.db.NotificationDao? = null) {
        serviceScope.launch {
            try {
                val dao = daoOverride ?: AppDatabase.getDatabase(applicationContext).notificationDao()
                val existing = dao.getNotificationById(payload.notificationId)
                if (existing == null) {
                    dao.insertNotification(payload.toNotificationItem())
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed inserting notification into Room: ${e.message}")
            }
        }
    }
}


